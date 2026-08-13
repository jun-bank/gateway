package com.junbank.gateway.routeswitch

import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.nio.charset.StandardCharsets

/**
 * canonical-v1 골든 벡터 + 규격 단위 테스트(Spring 없이). 아래 상수는 infra의 Go signer가
 * 산출한 것이며(internal/dispatch/internal_signing_test.go의 goldenVectors와 **같은 값**),
 * 이 테스트가 Kotlin 검증기로 동일 canonical 바이트·HMAC hex를 독립 재현한다 — 두 구현이
 * 바이트 동일함을 교차 repo로 못박는다(B2).
 */
class InternalCanonicalV1GoldenTest {

    private data class Vector(
        val name: String,
        val method: String,
        val path: String,
        val timestamp: String,
        val body: String,
        val digest: String,
        val canonical: String,
        val sig: String,
    )

    private val goldenKey = "golden-vector-key-not-a-secret".toByteArray(StandardCharsets.UTF_8)

    private val vectors = listOf(
        Vector(
            name = "GET status (empty body)",
            method = "GET",
            path = "/internal/routes/core",
            timestamp = "1755050000",
            body = "",
            digest = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonical = "GET\n/internal/routes/core\nsha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n1755050000",
            sig = "cd4e29bcaff88b9915f607c8ff3f9c89a7ff424c8a0d6e3f612a38bebcee71a1",
        ),
        Vector(
            name = "POST switch (json body)",
            method = "POST",
            path = "/internal/routes/core/switch",
            timestamp = "1755050000",
            body = """{"targetSlot":"green","fencingToken":7}""",
            digest = "sha256:13c0ade8002a2054041c551b3998c2ac1b93ef6e7218df154666a663b332fed6",
            canonical = "POST\n/internal/routes/core/switch\nsha256:13c0ade8002a2054041c551b3998c2ac1b93ef6e7218df154666a663b332fed6\n1755050000",
            sig = "b81aec98fe19ee02287a8063b6ba34729ea5987a4cd735ecb008cc76c924fabe",
        ),
        Vector(
            name = "POST empty-body boundary + ts=1",
            method = "POST",
            path = "/internal/routes/core/switch",
            timestamp = "1",
            body = "",
            digest = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            canonical = "POST\n/internal/routes/core/switch\nsha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855\n1",
            sig = "106a1e105151d164f02c8c5f0b938a39cd6d60e64e1277a3a64781ea47c96aed",
        ),
    )

    @Test
    fun `골든 벡터 canonical 바이트와 HMAC hex를 Kotlin이 그대로 재현한다`() {
        for (v in vectors) {
            val digest = InternalCanonicalV1.bodyDigest(v.body.toByteArray(StandardCharsets.UTF_8))
            assertThat(digest).describedAs(v.name + " digest").isEqualTo(v.digest)

            val canonical = InternalCanonicalV1.canonical(v.method, v.path, digest, v.timestamp)
            assertThat(String(canonical, StandardCharsets.UTF_8)).describedAs(v.name + " canonical").isEqualTo(v.canonical)

            val hex = InternalCanonicalV1.hex(InternalCanonicalV1.hmac(goldenKey, canonical))
            assertThat(hex).describedAs(v.name + " sig").isEqualTo(v.sig)
        }
    }

    @Test
    fun `빈 body digest는 sha256(빈 바이트) 고정 hex다`() {
        val want = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
        assertThat(InternalCanonicalV1.bodyDigest(ByteArray(0))).isEqualTo(want)
        assertThat(InternalCanonicalV1.EMPTY_BODY_DIGEST).isEqualTo(want)
    }

    @Test
    fun `canonical은 4필드 대문자 method 끝 개행 없음`() {
        val c = String(InternalCanonicalV1.canonical("get", "/p", "sha256:d", "123"), StandardCharsets.UTF_8)
        assertThat(c).isEqualTo("GET\n/p\nsha256:d\n123")
    }

    @Test
    fun `hex 디코드는 홀수 길이 비hex 대문자를 거절한다`() {
        assertThat(InternalCanonicalV1.decodeHexOrNull("abc")).isNull() // 홀수
        assertThat(InternalCanonicalV1.decodeHexOrNull("zz")).isNull() // 비hex
        assertThat(InternalCanonicalV1.decodeHexOrNull("AB")).isNull() // 대문자(서명은 소문자 고정)
        assertThat(InternalCanonicalV1.decodeHexOrNull("ab")).isEqualTo(byteArrayOf(0xAB.toByte()))
    }

    // 필터 생성자의 fail-closed: 키 없음·모드 화이트리스트 밖이면 예외(컨텍스트 기동 차단).
    @Test
    fun `키 없으면 필터 생성이 실패한다`() {
        assertThatThrownBy { InternalAuthWebFilter(InternalAuthProperties(mode = "enforce", hmacKey = "")) }
            .isInstanceOf(IllegalStateException::class.java)
        assertThatThrownBy { InternalAuthWebFilter(InternalAuthProperties(mode = "audit", hmacKey = "")) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `알 수 없는 모드는 필터 생성이 실패한다`() {
        assertThatThrownBy { InternalAuthWebFilter(InternalAuthProperties(mode = "on", hmacKey = "k")) }
            .isInstanceOf(IllegalStateException::class.java)
    }

    @Test
    fun `유효한 설정은 필터가 만들어진다`() {
        InternalAuthWebFilter(InternalAuthProperties(mode = "enforce", hmacKey = "k"))
        InternalAuthWebFilter(InternalAuthProperties(mode = "audit", hmacKey = "k"))
    }
}
