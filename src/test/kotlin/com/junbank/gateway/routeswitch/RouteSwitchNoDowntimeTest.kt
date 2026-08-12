package com.junbank.gateway.routeswitch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * BG-5 / IV-44 판정 입력: 라우트 갱신이 진행 중 요청을 끊지 않는지 실측한다.
 *
 * 부하를 걸어 둔 채 blue→green→blue로 두 번 전환하고, 실패 건수가 0인지 +
 * 부하 도중 두 slot의 응답이 모두 관측되는지(=전환이 실제로 일어났는지)를 본다.
 * 부하는 두 종류다:
 *   빠른 요청(`/core/ping`) — 전환 전후로 대량 통과하는지
 *   느린 요청(`/core/slow`, 백엔드가 150ms 뒤 응답) — 전환 순간에 정말로 진행 중인 요청이
 *     존재하도록 만든다. 빠른 요청만으로는 교체 순간이 요청 사이 틈에 떨어질 수 있다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["junbank.test-case=route-switch-no-downtime"],
)
class RouteSwitchNoDowntimeTest {

    @LocalServerPort
    private var port: Int = 0

    companion object {
        private val blue = StubBackend("blue")
        private val green = StubBackend("green")

        @JvmStatic
        @DynamicPropertySource
        fun coreSlots(registry: DynamicPropertyRegistry) {
            registry.add("junbank.routes.core.blue-uri") { blue.uri }
            registry.add("junbank.routes.core.green-uri") { green.uri }
            registry.add("junbank.routes.core.active-slot") { "blue" }
        }

        @JvmStatic
        @AfterAll
        fun stopBackends() {
            blue.close()
            green.close()
        }

        private const val FAST_WORKERS = 4
        private const val SLOW_WORKERS = 3
    }

    @Test
    fun `연속 요청 중 전환해도 실패가 0이다`() {
        val client = WebClient.create("http://127.0.0.1:$port")
        val stop = AtomicBoolean(false)
        val fast = Counters()
        val slow = Counters()

        val workers = (1..FAST_WORKERS).map { load(client, "/core/ping", fast, stop, "fast-$it") } +
            (1..SLOW_WORKERS).map { load(client, "/core/slow", slow, stop, "slow-$it") }

        try {
            Thread.sleep(300)
            switchTo(client, "green", token = 1)
            Thread.sleep(300)
            switchTo(client, "blue", token = 2)
            Thread.sleep(300)
        } finally {
            stop.set(true)
            workers.forEach { it.join(10_000) }
        }

        println("[no-downtime] fast=$fast slow=$slow")
        assertThat(fast.failures.get()).isZero()
        assertThat(slow.failures.get()).isZero()
        assertThat(fast.total.get()).isGreaterThan(100)
        // 느린 요청(150ms)이 전환 2회를 감쌀 만큼 충분히 오래 떠 있었다
        assertThat(slow.total.get()).isGreaterThan(SLOW_WORKERS * 2)
        // 부하 도중 실제로 전환이 일어났음을 증명한다(둘 다 응답한 적이 있어야 한다).
        assertThat(fast.observed).containsExactlyInAnyOrder("blue", "green")
        assertThat(slow.observed).containsExactlyInAnyOrder("blue", "green")
    }

    private class Counters {
        val total = AtomicInteger()
        val failures = AtomicInteger()
        val observed: MutableSet<String> = ConcurrentHashMap.newKeySet()
        override fun toString() = "requests=${total.get()} failures=${failures.get()} observedSlots=$observed"
    }

    private fun load(
        client: WebClient,
        path: String,
        counters: Counters,
        stop: AtomicBoolean,
        name: String,
    ) = thread(name = "load-$name") {
        while (!stop.get()) {
            counters.total.incrementAndGet()
            try {
                val body = client.get().uri(path)
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block(Duration.ofSeconds(5))
                if (body == "blue" || body == "green") {
                    counters.observed.add(body)
                } else {
                    counters.failures.incrementAndGet()
                }
            } catch (e: Exception) {
                counters.failures.incrementAndGet()
            }
        }
    }

    private fun switchTo(client: WebClient, slot: String, token: Long) {
        val response = client.post().uri("/internal/routes/core/switch")
            .bodyValue(mapOf("targetSlot" to slot, "fencingToken" to token))
            .retrieve()
            .toEntity(String::class.java)
            .block(Duration.ofSeconds(10))
        assertThat(response?.statusCode?.value()).isEqualTo(200)
    }
}
