package com.junbank.gateway.routeswitch

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.nio.charset.StandardCharsets
import java.time.Instant

/** 테스트 공용 canonical-v1 서명 헬퍼(게이트웨이 검증기와 같은 InternalCanonicalV1를 쓴다). */
internal object TestSigner {
    const val KEY = "test-internal-key-not-a-secret"

    fun sign(key: String, method: String, path: String, body: ByteArray, ts: Long): Pair<String, String> {
        val tsStr = ts.toString()
        val digest = InternalCanonicalV1.bodyDigest(body)
        val canonical = InternalCanonicalV1.canonical(method, path, digest, tsStr)
        val sig = InternalCanonicalV1.hex(InternalCanonicalV1.hmac(key.toByteArray(StandardCharsets.UTF_8), canonical))
        return sig to tsStr
    }

    fun now(): Long = Instant.now().epochSecond
}

private fun slotProps(registry: DynamicPropertyRegistry) {
    // 실제 백엔드 없이도 status GET·switch(URI 일치 확인)는 돈다 — 더미 URI로 충분하다.
    registry.add("junbank.routes.core.blue-uri") { "http://blue.invalid:8080" }
    registry.add("junbank.routes.core.green-uri") { "http://green.invalid:8080" }
    registry.add("junbank.routes.core.active-slot") { "blue" }
}

/**
 * enforce 모드 — 무서명·불일치·skew밖·timestamp누락은 401, 유효 서명만 통과. body 바꿔치기는
 * 헤더 digest를 믿지 않고 수신 본문에서 재계산하므로 401. 경로 우회 변형은 필터를 통과하지 못한다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["junbank.test-case=internal-auth-enforce"],
)
class InternalAuthEnforceTest {

    @Autowired
    private lateinit var client: WebTestClient

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            slotProps(registry)
            registry.add("junbank.internal-auth.mode") { "enforce" }
            registry.add("junbank.internal-auth.hmac-key") { TestSigner.KEY }
            registry.add("junbank.internal-auth.skew-seconds") { "30" }
        }
    }

    @Test
    fun `무서명 GET은 401`() {
        client.get().uri("/internal/routes/core").exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `무서명 POST는 401`() {
        client.post().uri("/internal/routes/core/switch")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"targetSlot":"green","fencingToken":1}""")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효 서명 GET은 통과한다`() {
        val (sig, ts) = TestSigner.sign(TestSigner.KEY, "GET", "/internal/routes/core", ByteArray(0), TestSigner.now())
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", sig)
            .header("X-Internal-Timestamp", ts)
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.activeSlot").exists()
    }

    @Test
    fun `위조 서명은 401`() {
        val (_, ts) = TestSigner.sign("wrong-key", "GET", "/internal/routes/core", ByteArray(0), TestSigner.now())
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", "cd4e29bcaff88b9915f607c8ff3f9c89a7ff424c8a0d6e3f612a38bebcee71a1")
            .header("X-Internal-Timestamp", ts)
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `서명은 있는데 timestamp가 없으면 401`() {
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", "cd4e29bcaff88b9915f607c8ff3f9c89a7ff424c8a0d6e3f612a38bebcee71a1")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `skew 창 밖 timestamp는 401`() {
        val old = TestSigner.now() - 3600 // 30s 창 밖
        val (sig, ts) = TestSigner.sign(TestSigner.KEY, "GET", "/internal/routes/core", ByteArray(0), old)
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", sig)
            .header("X-Internal-Timestamp", ts)
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `엄격 10진 아닌 timestamp는 401`() {
        val (sig, _) = TestSigner.sign(TestSigner.KEY, "GET", "/internal/routes/core", ByteArray(0), TestSigner.now())
        // 헤더로 전송 가능한 비-엄격10진 값만(공백·제어문자는 클라이언트가 거절해 별개 경로다).
        for (bad in listOf("+100", "0123", "1.0", "abc", "-5", "0")) {
            client.get().uri("/internal/routes/core")
                .header("X-Internal-Signature", sig)
                .header("X-Internal-Timestamp", bad)
                .exchange().expectStatus().isUnauthorized
        }
    }

    @Test
    fun `POST body 바꿔치기는 401 (헤더 digest 불신)`() {
        val signedBody = """{"targetSlot":"green","fencingToken":5}""".toByteArray(StandardCharsets.UTF_8)
        val (sig, ts) = TestSigner.sign(TestSigner.KEY, "POST", "/internal/routes/core/switch", signedBody, TestSigner.now())
        // 서명은 signedBody로 만들고 실제 전송은 다른 body — 수신 본문에서 재계산하므로 불일치.
        client.post().uri("/internal/routes/core/switch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Internal-Signature", sig)
            .header("X-Internal-Timestamp", ts)
            .bodyValue("""{"targetSlot":"blue","fencingToken":9}""")
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효 서명 POST는 컨트롤러가 같은 body를 읽고 전환한다`() {
        // 통과 후 캐시한 raw byte 재공급이 정확한지 — 컨트롤러가 targetSlot·token을 그대로 읽어
        // 전환에 성공하는 것으로 증명한다. token은 순서 무관하게 수락되도록 큰 값을 쓴다.
        val body = """{"targetSlot":"green","fencingToken":100000}""".toByteArray(StandardCharsets.UTF_8)
        val (sig, ts) = TestSigner.sign(TestSigner.KEY, "POST", "/internal/routes/core/switch", body, TestSigner.now())
        client.post().uri("/internal/routes/core/switch")
            .contentType(MediaType.APPLICATION_JSON)
            .header("X-Internal-Signature", sig)
            .header("X-Internal-Timestamp", ts)
            .bodyValue(body)
            .exchange()
            .expectStatus().isOk
            .expectBody()
            .jsonPath("$.activeSlot").isEqualTo("green")
            .jsonPath("$.fencingToken").isEqualTo(100000)
    }

    @Test
    fun `경로 우회 변형도 필터를 통과하지 못한다 (무서명 401)`() {
        // 정규화하면 /internal/routes/core 로 라우팅되는 변형들 — 인가 없이 컨트롤러에 닿으면 안 된다.
        // (클라이언트/서버 어느 쪽에서 정규화되든, 이 변형들이 무서명 200으로 새면 안 된다.)
        for (raw in listOf(
            "/internal/routes/../routes/core",
            "/internal/routes/./core",
            "/internal//routes/core",
        )) {
            client.get().uri(raw).exchange().expectStatus().isUnauthorized
        }
    }

    @Test
    fun `non-internal 경로는 인가 필터가 건드리지 않는다`() {
        // /settlement 는 프록시 대상 — 백엔드가 없어 5xx로 끝나더라도 401·413이면 안 된다(무접촉).
        val status = client.get().uri("/settlement/health").exchange().returnResult(String::class.java).status.value()
        assert(status != 401 && status != 413) { "non-internal 경로가 인가 필터에 걸렸다: status=$status" }
    }
}

/** audit 모드 — 무서명은 통과(WARN), 서명이 있으면 검증(위조는 401). */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["junbank.test-case=internal-auth-audit"],
)
class InternalAuthAuditTest {

    @Autowired
    private lateinit var client: WebTestClient

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            slotProps(registry)
            registry.add("junbank.internal-auth.mode") { "audit" }
            registry.add("junbank.internal-auth.hmac-key") { TestSigner.KEY }
            registry.add("junbank.internal-auth.skew-seconds") { "30" }
        }
    }

    @Test
    fun `무서명 GET은 통과한다 (과도기)`() {
        client.get().uri("/internal/routes/core")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.activeSlot").exists()
    }

    @Test
    fun `서명이 있는데 위조면 audit에서도 401`() {
        val (_, ts) = TestSigner.sign(TestSigner.KEY, "GET", "/internal/routes/core", ByteArray(0), TestSigner.now())
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", "00000000000000000000000000000000000000000000000000000000000000ff")
            .header("X-Internal-Timestamp", ts)
            .exchange().expectStatus().isUnauthorized
    }

    @Test
    fun `유효 서명 GET은 통과한다`() {
        val (sig, ts) = TestSigner.sign(TestSigner.KEY, "GET", "/internal/routes/core", ByteArray(0), TestSigner.now())
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", sig)
            .header("X-Internal-Timestamp", ts)
            .exchange().expectStatus().isOk
    }
}

/**
 * R5 교차 실행 스모크 — infra의 **Go signer가 산출한** 서명(golden vector 1)을 Kotlin 필터가
 * 그대로 통과시킨다. 고정 timestamp(1755050000)를 쓰므로 이 컨텍스트만 skew를 넓게 둔다.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["junbank.test-case=internal-auth-golden"],
)
class InternalAuthGoldenInteropTest {

    @Autowired
    private lateinit var client: WebTestClient

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun props(registry: DynamicPropertyRegistry) {
            slotProps(registry)
            registry.add("junbank.internal-auth.mode") { "enforce" }
            registry.add("junbank.internal-auth.hmac-key") { "golden-vector-key-not-a-secret" }
            registry.add("junbank.internal-auth.skew-seconds") { "100000000000" } // 고정 ts 허용
        }
    }

    @Test
    fun `Go signer 출력이 Kotlin verifier를 통과한다`() {
        // golden vector 1(GET /internal/routes/core · ts=1755050000)의 Go 산출 서명.
        client.get().uri("/internal/routes/core")
            .header("X-Internal-Signature", "cd4e29bcaff88b9915f607c8ff3f9c89a7ff424c8a0d6e3f612a38bebcee71a1")
            .header("X-Internal-Timestamp", "1755050000")
            .exchange()
            .expectStatus().isOk
            .expectBody().jsonPath("$.activeSlot").isEqualTo("blue")
    }
}
