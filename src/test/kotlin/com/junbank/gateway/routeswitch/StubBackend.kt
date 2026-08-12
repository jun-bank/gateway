package com.junbank.gateway.routeswitch

import reactor.core.publisher.Mono
import reactor.netty.http.server.HttpServer
import java.time.Duration

/**
 * slot 백엔드 스텁 — 자기 이름(blue|green)을 돌려준다.
 *   GET /ping  즉시 응답
 *   GET /slow  [SLOW_RESPONSE] 뒤 응답 — 전환 순간에 실제로 "진행 중"인 요청을 만들기 위한 경로
 *
 * 게이트웨이가 이미 쓰는 reactor-netty로 띄운다(테스트용 모의 서버 라이브러리를 따로 들이지 않는다).
 */
class StubBackend(name: String) : AutoCloseable {

    private val server = HttpServer.create()
        .host("127.0.0.1")
        .port(0)
        .route { routes ->
            routes.get("/ping") { _, response -> response.sendString(Mono.just(name)) }
            routes.get("/slow") { _, response ->
                response.sendString(Mono.just(name).delayElement(SLOW_RESPONSE))
            }
        }
        .bindNow()

    val uri: String = "http://127.0.0.1:${server.port()}"

    override fun close() {
        server.disposeNow()
    }

    companion object {
        val SLOW_RESPONSE: Duration = Duration.ofMillis(150)
    }
}
