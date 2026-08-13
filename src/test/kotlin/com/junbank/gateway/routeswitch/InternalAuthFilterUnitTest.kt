package com.junbank.gateway.routeswitch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import org.springframework.http.HttpStatus
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.util.concurrent.atomic.AtomicInteger

/**
 * 필터 직접 단위 테스트(Spring 컨텍스트 없이 MockServerWebExchange). R2① "인증 전 body를 읽지
 * 않는다"를 **구독 카운트로 실증**한다 — 결과 상태(401)만 보면 body를 먼저 다 읽고 401해도
 * 초록이라 그린 위장이다. 여기서는 body publisher 구독 0회 + chain.filter 0회를 직접 단언한다.
 */
class InternalAuthFilterUnitTest {

    private val factory = DefaultDataBufferFactory()

    private fun countingBody(subs: AtomicInteger): Flux<DataBuffer> {
        val buffer: DataBuffer = factory.wrap("""{"targetSlot":"green","fencingToken":1}""".toByteArray(StandardCharsets.UTF_8))
        return Flux.just(buffer).doOnSubscribe { subs.incrementAndGet() }
    }

    @Test
    fun `enforce 무서명 POST는 body를 구독하지 않고 chain을 부르지 않고 401`() {
        val bodySubs = AtomicInteger()
        val chainCalls = AtomicInteger()
        val filter = InternalAuthWebFilter(InternalAuthProperties(mode = "enforce", hmacKey = "k", skewSeconds = 30))

        val request = MockServerHttpRequest.post("/internal/routes/core/switch").body(countingBody(bodySubs))
        val exchange: ServerWebExchange = MockServerWebExchange.from(request)
        val chain = WebFilterChain { chainCalls.incrementAndGet(); Mono.empty() }

        filter.filter(exchange, chain).block()

        assertThat(exchange.response.statusCode).describedAs("status").isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(chainCalls.get()).describedAs("chain.filter 호출 수").isZero()
        assertThat(bodySubs.get()).describedAs("body 구독 수 — 인증 전 body 미독").isZero()
    }

    @Test
    fun `audit 무서명 POST는 body를 구독하지 않고 chain으로 통과한다`() {
        val bodySubs = AtomicInteger()
        val chainCalls = AtomicInteger()
        val filter = InternalAuthWebFilter(InternalAuthProperties(mode = "audit", hmacKey = "k", skewSeconds = 30))

        val request = MockServerHttpRequest.post("/internal/routes/core/switch").body(countingBody(bodySubs))
        val exchange: ServerWebExchange = MockServerWebExchange.from(request)
        val chain = WebFilterChain { chainCalls.incrementAndGet(); Mono.empty() }

        filter.filter(exchange, chain).block()

        // audit 무서명은 통과(WARN) — 필터가 body를 건드리지 않고 그대로 넘긴다(하류가 읽는다).
        assertThat(chainCalls.get()).describedAs("chain.filter 호출 수").isEqualTo(1)
        assertThat(bodySubs.get()).describedAs("필터는 body를 구독하지 않는다").isZero()
    }

    @Test
    fun `non-internal 경로는 body를 구독하지 않고 즉시 통과한다`() {
        val bodySubs = AtomicInteger()
        val chainCalls = AtomicInteger()
        val filter = InternalAuthWebFilter(InternalAuthProperties(mode = "enforce", hmacKey = "k", skewSeconds = 30))

        val request = MockServerHttpRequest.post("/settlement/pay").body(countingBody(bodySubs))
        val exchange: ServerWebExchange = MockServerWebExchange.from(request)
        val chain = WebFilterChain { chainCalls.incrementAndGet(); Mono.empty() }

        filter.filter(exchange, chain).block()

        assertThat(chainCalls.get()).describedAs("chain.filter 호출 수").isEqualTo(1)
        assertThat(bodySubs.get()).describedAs("non-internal body 무접촉").isZero()
    }
}
