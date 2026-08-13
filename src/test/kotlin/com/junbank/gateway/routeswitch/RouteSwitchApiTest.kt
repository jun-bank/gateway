package com.junbank.gateway.routeswitch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.MethodOrderer
import org.junit.jupiter.api.Order
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestMethodOrder
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import kotlin.concurrent.thread

/**
 * 전환 API의 계약 검증.
 *
 * fencing 상태(lastAcceptedToken)는 애플리케이션 컨텍스트 하나를 공유하므로 테스트가
 * 순서에 의존한다 — token은 아래에서 아래로 단조 증가하도록 @Order로 고정한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["junbank.test-case=route-switch-api"],
)
@TestMethodOrder(MethodOrderer.OrderAnnotation::class)
class RouteSwitchApiTest {

    @Autowired
    private lateinit var client: WebTestClient

    @Autowired
    private lateinit var routeLocator: RouteLocator

    companion object {
        private val blue = StubBackend("blue")
        private val green = StubBackend("green")

        @JvmStatic
        @DynamicPropertySource
        fun coreSlots(registry: DynamicPropertyRegistry) {
            registry.add("junbank.routes.core.blue-uri") { blue.uri }
            registry.add("junbank.routes.core.green-uri") { green.uri }
            registry.add("junbank.routes.core.active-slot") { "blue" }
            // 이 테스트는 전환 계약(B6)만 본다 — 무서명 요청이 통과하도록 audit 모드로 둔다
            // (인가 필터 자체의 계약은 InternalAuthFilterTest가 별도로 검증한다). 키는 audit·
            // enforce 모두 필수라 여기서도 반드시 준다(없으면 컨텍스트가 기동하지 못한다).
            registry.add("junbank.internal-auth.mode") { "audit" }
            registry.add("junbank.internal-auth.hmac-key") { "test-internal-key-not-a-secret" }
        }

        @JvmStatic
        @AfterAll
        fun stopBackends() {
            blue.close()
            green.close()
        }
    }

    @Test
    @Order(1)
    fun `초기 상태는 CORE_ACTIVE_SLOT과 token 0`() {
        client.get().uri("/internal/routes/core")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.service").isEqualTo("core")
            .jsonPath("$.activeSlot").isEqualTo("blue")
            .jsonPath("$.uri").isEqualTo(blue.uri)
            .jsonPath("$.lastAcceptedToken").isEqualTo(0)
    }

    @Test
    @Order(2)
    fun `미지 서비스는 404`() {
        client.get().uri("/internal/routes/payments")
            .exchange()
            .expectStatus().isNotFound

        switchRequest("payments", """{"targetSlot":"green","fencingToken":1}""")
            .expectStatus().isNotFound
    }

    @Test
    @Order(3)
    fun `형식 오류와 token 없는 요청은 400`() {
        // targetSlot이 blue|green이 아니다
        switchRequest("core", """{"targetSlot":"red","fencingToken":1}""")
            .expectStatus().isBadRequest
        // token 없는 전환은 거절한다(fail-closed) — fencing이 꺼진 경로를 만들지 않는다
        switchRequest("core", """{"targetSlot":"green"}""")
            .expectStatus().isBadRequest
        // 양의 정수가 아니다
        switchRequest("core", """{"targetSlot":"green","fencingToken":0}""")
            .expectStatus().isBadRequest
        // 파싱 불가한 body
        switchRequest("core", """{"targetSlot":""")
            .expectStatus().isBadRequest
        // 빈 body
        client.post().uri("/internal/routes/core/switch")
            .contentType(MediaType.APPLICATION_JSON)
            .exchange()
            .expectStatus().isBadRequest

        // 거절된 요청은 상태를 건드리지 않았다
        expectStatus(activeSlot = "blue", uri = blue.uri, lastAcceptedToken = 0)
    }

    @Test
    @Order(4)
    fun `전환하면 라우팅 대상이 실제로 바뀐다`() {
        expectCorePingBody("blue")

        switchRequest("core", """{"targetSlot":"green","fencingToken":5}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.service").isEqualTo("core")
            .jsonPath("$.activeSlot").isEqualTo("green")
            .jsonPath("$.fencingToken").isEqualTo(5)

        expectCorePingBody("green")
        expectStatus(activeSlot = "green", uri = green.uri, lastAcceptedToken = 5)
    }

    @Test
    @Order(5)
    fun `낮은 token은 409로 거부되고 라우트는 그대로다`() {
        switchRequest("core", """{"targetSlot":"blue","fencingToken":3}""")
            .expectStatus().isEqualTo(409)
            .expectBody()
            .jsonPath("$.error").isEqualTo("stale fencing token")
            .jsonPath("$.lastAcceptedToken").isEqualTo(5)
            // 전환 시도 자체가 없었다 = 미전환 보증(호출자가 실패의 보증 수준을 구별한다)
            .jsonPath("$.state").isEqualTo("NOT_ATTEMPTED")

        // stale 실행자의 write는 최종 상태가 되지 않는다(ADR-031 BG-4 ⓐ 계약)
        expectCorePingBody("green")
        expectStatus(activeSlot = "green", uri = green.uri, lastAcceptedToken = 5)
    }

    @Test
    @Order(6)
    fun `같은 token 재요청은 멱등하게 수락된다`() {
        switchRequest("core", """{"targetSlot":"green","fencingToken":5}""")
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.activeSlot").isEqualTo("green")
            .jsonPath("$.fencingToken").isEqualTo(5)

        expectCorePingBody("green")
        expectStatus(activeSlot = "green", uri = green.uri, lastAcceptedToken = 5)
    }

    @Test
    @Order(7)
    fun `높은 token은 수락된다`() {
        switchRequest("core", """{"targetSlot":"blue","fencingToken":6}""")
            .expectStatus().isOk

        expectCorePingBody("blue")
        expectStatus(activeSlot = "blue", uri = blue.uri, lastAcceptedToken = 6)
    }

    @Test
    @Order(8)
    fun `settlement·ledger 정적 라우트는 그대로 남아 있다`() {
        // core만 프로그램 라우트로 옮겼다 — 나머지 정적 라우트가 함께 사라지지 않았는지 본다.
        val ids = routeLocator.routes.map { it.id }.collectList().block(Duration.ofSeconds(5))
        assertThat(ids).contains("core", "settlement", "ledger")
    }

    @Test
    @Order(9)
    fun `동시 전환 요청에도 최종 상태가 하나로 정해진다`() {
        val tokens = (10L..29L).toList()
        val maxToken = tokens.max()
        val statuses = CopyOnWriteArrayList<Int>()
        val start = CountDownLatch(1)

        // 최대 token만 green을 요청한다 — 어떤 실행 순서든 최대 token은 반드시 수락되고,
        // 그 뒤로는 더 작은 token이 모두 stale이 되므로 최종 상태는 green/29 하나뿐이다.
        val threads = tokens.map { token ->
            thread(name = "switch-$token") {
                val slot = if (token == maxToken) "green" else "blue"
                start.await()
                statuses += switchRequest("core", """{"targetSlot":"$slot","fencingToken":$token}""")
                    .returnResult(String::class.java).status.value()
            }
        }
        start.countDown()
        threads.forEach { it.join(30_000) }

        // 수락(200) 아니면 stale 거부(409)뿐 — 교체가 찢어졌다면 반영 확인이 실패해 500이 섞인다
        assertThat(statuses).hasSize(tokens.size).isSubsetOf(200, 409).contains(200)
        expectStatus(activeSlot = "green", uri = green.uri, lastAcceptedToken = maxToken)
        expectCorePingBody("green")
    }

    private fun switchRequest(service: String, body: String): WebTestClient.ResponseSpec =
        client.post().uri("/internal/routes/$service/switch")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private fun expectCorePingBody(expected: String) {
        client.get().uri("/core/ping")
            .exchange()
            .expectStatus().isOk
            .expectBody(String::class.java).isEqualTo(expected)
    }

    private fun expectStatus(activeSlot: String, uri: String, lastAcceptedToken: Long) {
        client.get().uri("/internal/routes/core")
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.activeSlot").isEqualTo(activeSlot)
            .jsonPath("$.uri").isEqualTo(uri)
            .jsonPath("$.lastAcceptedToken").isEqualTo(lastAcceptedToken)
    }
}
