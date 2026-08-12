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
 * core 라우트의 blue↔green 전환. fencing token 검증 → 원자 교체 → 반영 확인 → 실패 시 원복.
 *
 * ADR-031 BG-4 ⓐ (sink-side 조건부 갱신): 전환을 지시하는 실행자가 stale일 수 있으므로,
 * 게이트웨이(=sink)가 token을 직접 검증해 거부한다. 계약은 "stale 실행자의 라우트 write가
 * 최종 상태가 되지 않는다" — 그래서 검증·교체·확인이 한 임계구역 안에 있어야 한다.
 * DO-20 ⑷: 반영 전 검증 → 원자 교체 → 실패 시 원복.
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
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** 라우트 갱신을 직렬화하는 단일 쓰기 지점 — 동시 switch 요청은 여기서 줄을 선다. */
    private val writeLock = Any()

    sealed interface Result {
        data class Applied(val slot: Slot, val token: Long) : Result

        /** token이 마지막 수락값보다 작다 — stale 실행자로 보고 거부. */
        data class Stale(val lastAcceptedToken: Long) : Result

        /** 교체는 했으나 라우트에 반영되지 않아 원복했다. */
        data class RollbackedFailure(val reason: String) : Result
    }

    fun switchTo(target: Slot, token: Long): Result = synchronized(writeLock) {
        val before = registry.snapshot()
        // 단조 조건: 같은 token 재요청은 멱등 재시도로 수락하고, 작은 token만 거부한다.
        if (token < before.lastAcceptedToken) {
            log.warn("stale fencing token rejected: got={} lastAccepted={}", token, before.lastAcceptedToken)
            return Result.Stale(before.lastAcceptedToken)
        }

        registry.replace(CoreRouteRegistry.Snapshot(target, token))
        val expected = registry.uriOf(target)
        if (refreshAndAwait(expected)) {
            log.info("core route switched: slot={} uri={} token={}", target.wireName, expected, token)
            return Result.Applied(target, token)
        }

        // 반영 확인 실패 → 이전 상태로 되돌린다. 원복까지 실패하면 그 사실을 로그로 남긴다
        // (그 경우 라우트 실제 상태 = SCG 캐시가 여전히 정본이므로 GET으로 재확인이 필요하다).
        registry.replace(before)
        val restored = refreshAndAwait(registry.uriOf(before.slot))
        log.error(
            "core route switch failed to take effect (target={}, token={}); rolled back, restoreVerified={}",
            target.wireName, token, restored,
        )
        return Result.RollbackedFailure("route refresh did not take effect")
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
