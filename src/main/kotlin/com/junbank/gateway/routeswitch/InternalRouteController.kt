package com.junbank.gateway.routeswitch

import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebInputException
import reactor.core.publisher.Mono
import reactor.core.scheduler.Schedulers

/**
 * 블루-그린 전환 관리 표면.
 *
 * `/internal` 프리픽스는 어떤 라우트의 Path 술어(core·settlement·ledger 프리픽스)에도
 * 걸리지 않으므로 외부로 프록시되지 않는다 — 게이트웨이 자신이 처리하는 관리 API다.
 * v1은 인증 계층을 두지 않는다: `/internal`은 LAN 경계(호스트 방화벽·NAT)가 지키는 표면이며,
 * fencing token은 stale 실행자의 순서를 결박할 뿐 인가(authorization)가 아니다
 * (별도 인증은 후속 결정).
 *
 * 실패 응답 계약: 호출자(배포 agent)는 "미전환이 보증되는 실패"와 "실상태 불명 실패"를
 * 구별해야 한다 — 불명인데 미전환으로 오판하면 서비스 중인 slot을 내리게 된다. 그래서 실패
 * body에 `state`를 싣는다: `NOT_ATTEMPTED`(409) · `ROLLED_BACK`(500) 은 미전환 보증,
 * `INDETERMINATE`(500) 은 GET으로 재확인이 필요하다는 뜻이다. 성공(200) 형태는 그대로다.
 */
@RestController
@RequestMapping("/internal/routes")
class InternalRouteController(
    private val registry: CoreRouteRegistry,
    private val switchService: RouteSwitchService,
) {

    /**
     * 현재 상태. 전환과 같은 락에서 직렬화해 읽는다 — 전환 진행 중에는 완료(또는 원복) 후의
     * 상태만 보인다(잠정 상태 노출 금지). 락 대기가 이벤트 루프를 잡지 않도록 boundedElastic에서.
     */
    @GetMapping("/{service}")
    fun status(@PathVariable service: String): Mono<ResponseEntity<Any>> {
        if (service != CORE_ROUTE_ID) return Mono.just(unknownService(service))
        return Mono.fromCallable { switchService.currentState() }
            .subscribeOn(Schedulers.boundedElastic())
            .map { snapshot -> statusResponse(snapshot) }
    }

    private fun statusResponse(snapshot: CoreRouteRegistry.Snapshot): ResponseEntity<Any> =
        ResponseEntity.ok(
            RouteStatusResponse(
                service = CORE_ROUTE_ID,
                activeSlot = snapshot.slot.wireName,
                uri = registry.uriOf(snapshot.slot).toString(),
                lastAcceptedToken = snapshot.lastAcceptedToken,
            ),
        )

    @PostMapping("/{service}/switch")
    fun switch(
        @PathVariable service: String,
        @RequestBody(required = false) request: SwitchRequest?,
    ): Mono<ResponseEntity<Any>> {
        if (service != CORE_ROUTE_ID) return Mono.just(unknownService(service))

        val target = Slot.parseOrNull(request?.targetSlot)
            ?: return Mono.just(badRequest("targetSlot must be 'blue' or 'green'"))
        // token 없는 요청은 거절한다(fail-closed) — fencing이 꺼진 전환 경로를 만들지 않는다.
        val token = request?.fencingToken
            ?: return Mono.just(badRequest("fencingToken is required"))
        // 계약: fencingToken 은 int64 양수(≥1).
        if (token <= 0) return Mono.just(badRequest("fencingToken must be a positive int64 (>= 1)"))

        // switchTo는 라우트 반영을 기다리는 블로킹 호출 — 이벤트 루프 밖에서 실행한다.
        return Mono.fromCallable { switchService.switchTo(target, token) }
            .subscribeOn(Schedulers.boundedElastic())
            .map { result -> toResponse(result) }
    }

    private fun toResponse(result: RouteSwitchService.Result): ResponseEntity<Any> = when (result) {
        is RouteSwitchService.Result.Applied -> ResponseEntity.ok(
            SwitchAcceptedResponse(CORE_ROUTE_ID, result.slot.wireName, result.token),
        )

        // 거부 = 전환 시도 자체가 없었다 → 미전환 보증.
        is RouteSwitchService.Result.Stale -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(StaleTokenResponse(lastAcceptedToken = result.lastAcceptedToken))

        // 원복이 실제 반영까지 확인됐다 → 미전환 보증.
        is RouteSwitchService.Result.RolledBack -> ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(SwitchFailureResponse(result.reason, SwitchState.ROLLED_BACK))

        // 실상태 불명 → 호출자는 GET으로 재확인하고, 어느 slot도 성급히 내리면 안 된다.
        is RouteSwitchService.Result.Indeterminate -> ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(SwitchFailureResponse(result.reason, SwitchState.INDETERMINATE))
    }

    /** 파싱 불가한 body(잘못된 JSON·타입 불일치)도 형식 오류로 400을 준다. */
    @ExceptionHandler(ServerWebInputException::class)
    fun handleMalformedBody(e: ServerWebInputException): ResponseEntity<Any> =
        badRequest("malformed request body")

    private fun badRequest(message: String): ResponseEntity<Any> =
        ResponseEntity.badRequest().body(ErrorResponse(message))

    private fun unknownService(service: String): ResponseEntity<Any> =
        ResponseEntity.status(HttpStatus.NOT_FOUND).body(ErrorResponse("unknown service '$service'"))
}

/** 전환 요청. fencingToken 계약 = int64 양수(≥1) — 없거나 0 이하면 400. */
data class SwitchRequest(val targetSlot: String? = null, val fencingToken: Long? = null)

/** 실패 응답의 `state` — 호출자가 "내려도 되는 상태인가"를 판단하는 값. */
object SwitchState {
    /** 전환을 시도하지 않았다(stale token 거부) — 미전환 보증. */
    const val NOT_ATTEMPTED = "NOT_ATTEMPTED"

    /** 시도했으나 원복했고, 원복 반영까지 확인했다 — 미전환 보증. */
    const val ROLLED_BACK = "ROLLED_BACK"

    /** 실제 라우트 상태를 보증할 수 없다 — GET으로 재확인 필요. */
    const val INDETERMINATE = "INDETERMINATE"
}

data class RouteStatusResponse(
    val service: String,
    val activeSlot: String,
    val uri: String,
    val lastAcceptedToken: Long,
)

data class SwitchAcceptedResponse(val service: String, val activeSlot: String, val fencingToken: Long)

data class StaleTokenResponse(
    val error: String = "stale fencing token",
    val lastAcceptedToken: Long,
    val state: String = SwitchState.NOT_ATTEMPTED,
)

/** 전환 실패(500) 응답 — 실패 사유 + 실제 라우트 상태의 보증 수준. */
data class SwitchFailureResponse(val error: String, val state: String)

data class ErrorResponse(val error: String)
