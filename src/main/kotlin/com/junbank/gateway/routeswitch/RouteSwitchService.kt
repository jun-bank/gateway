package com.junbank.gateway.routeswitch

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.event.RefreshRoutesEvent
import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.net.URI
import java.time.Duration

/**
 * core 라우트의 blue↔green 전환. fencing token 검증 → 상태 기록 → 원자 교체 → 반영 확인 →
 * 실패 시 원복.
 *
 * ADR-031 BG-4 ⓐ (sink-side 조건부 갱신): 전환을 지시하는 실행자가 stale일 수 있으므로,
 * 게이트웨이(=sink)가 token을 직접 검증해 거부한다. 계약은 "stale 실행자의 라우트 write가
 * 최종 상태가 되지 않는다" — 그래서 검증·교체·확인이 한 임계구역 안에 있어야 한다.
 * DO-20 ⑷: 반영 전 검증 → 원자 교체 → 실패 시 원복.
 *
 * token 계약: **int64 양수(≥1)**. 마지막으로 수락한 값보다 작으면 거부하고, 같은 값은 멱등
 * 재시도로 수락한다. **원복도 token을 후퇴시키지 않는다** — 실패한 시도의 token도 이미 수락
 * 판정을 통과한 값이라, 되돌리면서 낮추면 그 사이 token을 든 stale 실행자가 나중에 수락된다.
 *
 * 커밋 순서(write-ahead, [CoreStateStore] 설정 시): ⑴ 파일에 목표 {slot, token} 기록 →
 * ⑵ registry 교체 + 반영 확인 → ⑶ 실패 시 파일·라우트를 이전 slot으로 되돌린다.
 * 파일은 항상 라우트보다 앞서므로, 어느 지점에 죽어도 파일이 가리키는 slot은 살아 있다.
 *
 * [switchTo]는 라우트 반영을 폴링으로 기다리므로 블로킹이다. 호출자(컨트롤러)가
 * 이벤트 루프 밖(boundedElastic)에서 호출한다 — 전환 중에도 프록시 처리는 멈추지 않아야 한다.
 */
@Service
class RouteSwitchService(
    private val registry: CoreRouteRegistry,
    // @Primary RouteLocator = CachingRouteLocator. 요청을 실제로 태우는 바로 그 캐시를 읽어
    // 확인해야 "라우트가 정말 갱신됐나"를 검증한 것이 된다(우리 상태만 다시 읽는 건 자기증명).
    private val routeLocator: RouteLocator,
    private val events: ApplicationEventPublisher,
    private val stateStore: CoreStateStore,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 라우트 갱신을 직렬화하는 단일 쓰기 지점 — 동시 switch 요청은 여기서 줄을 선다. */
    private val writeLock = Any()

    sealed interface Result {
        data class Applied(val slot: Slot, val token: Long) : Result

        /** token이 마지막 수락값보다 작다 — stale 실행자로 보고 거부(전환 시도 자체가 없었다). */
        data class Stale(val lastAcceptedToken: Long) : Result

        /** 교체는 했으나 반영되지 않았고, **원복이 반영된 것까지 확인**했다 — 미전환이 보증된다. */
        data class RolledBack(val reason: String) : Result

        /**
         * 실제 라우트 상태를 보증할 수 없다 — 원복이 확인되지 않았거나 도중에 예외가 났다.
         * 호출자는 미전환으로 단정하지 말고 GET으로 재확인해야 한다(서비스 중인 slot을 내리면 사고).
         */
        data class Indeterminate(val reason: String) : Result
    }

    fun switchTo(target: Slot, token: Long): Result = synchronized(writeLock) {
        val before = registry.snapshot()
        // 단조 조건: 같은 token 재요청은 멱등 재시도로 수락하고, 작은 token만 거부한다.
        if (token < before.lastAcceptedToken) {
            log.warn("stale fencing token rejected: got={} lastAccepted={}", token, before.lastAcceptedToken)
            return Result.Stale(before.lastAcceptedToken)
        }

        return try {
            attemptSwitch(before, target, token)
        } catch (e: Exception) {
            // 상태 기록·이벤트 발행·반영 확인 어디서든 예외가 새면 잠정 상태가 남을 수 있다.
            // 되돌릴 수 있는 만큼 되돌리되, 어디까지 진행됐는지는 보증하지 못하므로 불명으로 답한다.
            log.error("core route switch threw (target={}, token={})", target.wireName, token, e)
            val restored = rollback(before, token)
            Result.Indeterminate("switch failed: ${e.javaClass.simpleName}; rollbackVerified=$restored")
        }
    }

    /** 전환 임계구역 안에서 읽은 상태 — 진행 중인 전환의 잠정 상태를 밖으로 흘리지 않는다. */
    fun currentState(): CoreRouteRegistry.Snapshot = synchronized(writeLock) { registry.snapshot() }

    private fun attemptSwitch(
        before: CoreRouteRegistry.Snapshot,
        target: Slot,
        token: Long,
    ): Result {
        // ⑴ write-ahead: 라우트를 바꾸기 전에 목표 상태를 먼저 남긴다(기록 실패 = 전환 시작 안 함).
        stateStore.write(CoreStateStore.State(target, token))

        // ⑵ 원자 교체 + 반영 확인
        registry.replace(CoreRouteRegistry.Snapshot(target, token))
        val expected = registry.uriOf(target)
        if (refreshAndAwait(expected)) {
            log.info("core route switched: slot={} uri={} token={}", target.wireName, expected, token)
            return Result.Applied(target, token)
        }

        // ⑶ 반영 확인 실패 → 이전 slot으로 되돌린다. 원복까지 확인돼야 "미전환 보증"을 답할 수 있다.
        val reason = "route refresh did not take effect"
        return if (rollback(before, token)) {
            Result.RolledBack(reason)
        } else {
            Result.Indeterminate("$reason; rollback not verified")
        }
    }

    /**
     * slot만 이전으로 되돌린다 — token은 `max(이전 수락값, 시도한 값)`을 유지해 단조성을 깨지 않는다.
     * @return 파일 재기록과 라우트 반영이 **둘 다** 확인되면 true(= 미전환 보증 가능).
     */
    private fun rollback(before: CoreRouteRegistry.Snapshot, attemptedToken: Long): Boolean {
        val restored = CoreRouteRegistry.Snapshot(
            slot = before.slot,
            lastAcceptedToken = maxOf(before.lastAcceptedToken, attemptedToken),
        )

        val persisted = try {
            stateStore.write(CoreStateStore.State(restored.slot, restored.lastAcceptedToken))
            true
        } catch (e: Exception) {
            log.error("failed to rewrite core state file during rollback (slot={})", restored.slot.wireName, e)
            false
        }

        registry.replace(restored)
        val refreshed = try {
            refreshAndAwait(registry.uriOf(restored.slot))
        } catch (e: Exception) {
            log.error("rollback refresh threw (slot={})", restored.slot.wireName, e)
            false
        }

        log.error(
            "core route rolled back to slot={} token={} (statePersisted={}, refreshVerified={})",
            restored.slot.wireName, restored.lastAcceptedToken, persisted, refreshed,
        )
        return persisted && refreshed
    }

    /** RefreshRoutesEvent는 비동기로 캐시를 다시 채운다 — 실제 라우트 URI가 바뀔 때까지 확인한다. */
    private fun refreshAndAwait(expected: URI): Boolean {
        events.publishEvent(RefreshRoutesEvent(this))
        val deadline = System.nanoTime() + REFRESH_TIMEOUT.toNanos()
        while (true) {
            if (currentCoreRouteUri() == expected) return true
            if (System.nanoTime() >= deadline) return false
            Thread.sleep(REFRESH_POLL_INTERVAL_MS)
        }
    }

    private fun currentCoreRouteUri(): URI? =
        routeLocator.routes
            .filter { it.id == CORE_ROUTE_ID }
            .next()
            .map(Route::getUri)
            .block(REFRESH_TIMEOUT)

    private companion object {
        val REFRESH_TIMEOUT: Duration = Duration.ofSeconds(3)
        const val REFRESH_POLL_INTERVAL_MS = 10L
    }
}
