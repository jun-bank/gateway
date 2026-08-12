package com.junbank.gateway.routeswitch

import org.slf4j.LoggerFactory
import org.springframework.cloud.gateway.filter.FilterDefinition
import org.springframework.cloud.gateway.handler.predicate.PredicateDefinition
import org.springframework.cloud.gateway.route.RouteDefinition
import org.springframework.cloud.gateway.route.RouteDefinitionRepository
import org.springframework.stereotype.Component
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI

/** core 라우트의 id — 정적 yml 시절과 같은 값을 유지한다(로그·actuator 식별자 호환). */
const val CORE_ROUTE_ID = "core"

/**
 * core 라우트의 정본 상태(활성 slot + 마지막으로 수락한 fencing token)를 들고,
 * 그 상태로부터 Spring Cloud Gateway 라우트 정의를 만들어 주는 저장소.
 *
 * ADR-031 BG-1: "라우트 실제 상태의 정본 = SCG 설정 한 곳" — 그 한 곳이 여기다.
 * 게이트웨이는 이 스냅샷 하나만 보고 라우트를 만들고, /internal API도 이 스냅샷을 읽어
 * 답한다(따로 캐시한 사본을 만들지 않는다).
 *
 * `lastAcceptedToken` 계약: fencing token 은 **int64 양수(≥1)** 이고, 아직 전환이 없었음을
 * 뜻하는 0 만이 예외다. 전환은 단조 증가 방향으로만 수락된다(같은 값 재요청은 멱등).
 *
 * 기동 시 상태: `CORE_STATE_FILE`이 설정돼 있고 파일이 읽히면 그 {slot, token}으로 복원하고
 * (env `CORE_ACTIVE_SLOT`보다 우선), 아니면 activeSlot = CORE_ACTIVE_SLOT · token = 0 이다.
 *
 * 알려진 잔여(지속화를 끈 경우 = `CORE_STATE_FILE` 미설정): 상태가 인메모리라 게이트웨이가
 * 재시작하면 activeSlot = CORE_ACTIVE_SLOT, lastAcceptedToken = 0 으로 리셋된다. 즉 재시작
 * 직후의 짧은 창에서는 이미 지나간 낮은 token을 든 stale 실행자의 write가 한 번 수락될 수 있다.
 */
@Component
class CoreRouteRegistry(
    private val properties: CoreRouteProperties,
    private val stateStore: CoreStateStore,
) : RouteDefinitionRepository {

    data class Snapshot(val slot: Slot, val lastAcceptedToken: Long)

    private val log = LoggerFactory.getLogger(javaClass)

    // 읽기는 요청 스레드 여러 곳에서, 쓰기는 RouteSwitchService의 단일 지점에서만 일어난다.
    @Volatile
    private var snapshot: Snapshot = initialSnapshot()

    private fun initialSnapshot(): Snapshot {
        // 파일로 복원하더라도 env 값은 검증한다 — 잘못된 CORE_ACTIVE_SLOT은 설정 오류로 즉시 실패.
        val configured = Slot.parseOrNull(properties.activeSlot)
            ?: error("CORE_ACTIVE_SLOT must be 'blue' or 'green' but was '${properties.activeSlot}'")

        val restored = stateStore.read()
        if (restored != null) {
            log.info(
                "core route state restored from file: slot={} lastAcceptedToken={}",
                restored.slot.wireName, restored.token,
            )
            return Snapshot(restored.slot, restored.token)
        }
        if (stateStore.enabled) {
            log.info("no core route state file yet; starting from CORE_ACTIVE_SLOT={}", configured.wireName)
        }
        return Snapshot(configured, 0L)
    }

    fun snapshot(): Snapshot = snapshot

    fun uriOf(slot: Slot): URI = URI.create(
        when (slot) {
            Slot.BLUE -> properties.blueUri
            Slot.GREEN -> properties.greenUri
        },
    )

    /** 원자 교체 지점. 동시 write 직렬화는 [RouteSwitchService]가 책임진다(단일 쓰기 지점). */
    fun replace(next: Snapshot) {
        snapshot = next
    }

    override fun getRouteDefinitions(): Flux<RouteDefinition> = Flux.just(definitionOf(snapshot))

    // 정적 yml 시절의 core 라우트와 같은 술어·필터를 slot URI만 바꿔 재현한다.
    //   Path=/core/** -> 활성 slot URI (StripPrefix=1 로 `/core` 세그먼트 제거)
    private fun definitionOf(snapshot: Snapshot) = RouteDefinition().apply {
        id = CORE_ROUTE_ID
        uri = uriOf(snapshot.slot)
        predicates = listOf(PredicateDefinition("Path=/core/**"))
        filters = listOf(FilterDefinition("StripPrefix=1"))
    }

    // 라우트 변경은 /internal/routes/core/switch 한 경로로만 들어온다 — 임의 write는 막는다.
    override fun save(route: Mono<RouteDefinition>): Mono<Void> =
        Mono.error(UnsupportedOperationException("core route is switched via /internal/routes/core/switch"))

    override fun delete(routeId: Mono<String>): Mono<Void> =
        Mono.error(UnsupportedOperationException("core route cannot be deleted"))
}
