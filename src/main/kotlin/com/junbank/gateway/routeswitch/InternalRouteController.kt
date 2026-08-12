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
 * v1은 인증 계층을 두지 않는다: 접근 통제는 LAN 경계, 오작동 방지는 fencing token이 맡는다
 * (별도 인증은 후속 결정).
 */
@RestController
@RequestMapping("/internal/routes")
class InternalRouteController(
    private val registry: CoreRouteRegistry,
    private val switchService: RouteSwitchService,
) {

    @GetMapping("/{service}")
    fun status(@PathVariable service: String): ResponseEntity<Any> {
        if (service != CORE_ROUTE_ID) return unknownService(service)
        val snapshot = registry.snapshot()
        return ResponseEntity.ok(
            RouteStatusResponse(
                service = CORE_ROUTE_ID,
                activeSlot = snapshot.slot.wireName,
                uri = registry.uriOf(snapshot.slot).toString(),
                lastAcceptedToken = snapshot.lastAcceptedToken,
            ),
        )
    }

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
        if (token <= 0) return Mono.just(badRequest("fencingToken must be a positive integer"))

        // switchTo는 라우트 반영을 기다리는 블로킹 호출 — 이벤트 루프 밖에서 실행한다.
        return Mono.fromCallable { switchService.switchTo(target, token) }
            .subscribeOn(Schedulers.boundedElastic())
            .map { result -> toResponse(result) }
    }

    private fun toResponse(result: RouteSwitchService.Result): ResponseEntity<Any> = when (result) {
        is RouteSwitchService.Result.Applied -> ResponseEntity.ok(
            SwitchAcceptedResponse(CORE_ROUTE_ID, result.slot.wireName, result.token),
        )

        is RouteSwitchService.Result.Stale -> ResponseEntity.status(HttpStatus.CONFLICT)
            .body(StaleTokenResponse(lastAcceptedToken = result.lastAcceptedToken))

        is RouteSwitchService.Result.RollbackedFailure -> ResponseEntity
            .status(HttpStatus.INTERNAL_SERVER_ERROR)
            .body(ErrorResponse(result.reason))
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

data class SwitchRequest(val targetSlot: String? = null, val fencingToken: Long? = null)

data class RouteStatusResponse(
    val service: String,
    val activeSlot: String,
    val uri: String,
    val lastAcceptedToken: Long,
)

data class SwitchAcceptedResponse(val service: String, val activeSlot: String, val fencingToken: Long)

data class StaleTokenResponse(val error: String = "stale fencing token", val lastAcceptedToken: Long)

data class ErrorResponse(val error: String)
