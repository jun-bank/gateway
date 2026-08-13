package com.junbank.gateway.routeswitch

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * /internal 관리 표면 인가의 서명 규격 `canonical-v1`. infra의 signer(dispatch/internal_signing.go)와
 * **바이트 동일**한 canonical·HMAC hex를 만들어야 한다(B2 — 한쪽만 바꾸면 전건 불일치).
 * 두 구현(Go·Kotlin)이라 진짜 단일 출처가 아니므로, 공유 golden vector fixture로 계약을 닫는다
 * (InternalCanonicalV1GoldenTest — 양 repo가 같은 상수를 재현).
 *
 * 규격(고정):
 * ```
 * <METHOD>\n<path>\n<bodyDigest>\n<timestamp>
 * ```
 * - METHOD 대문자 정규화. path = 요청 경로(쿼리 제외). bodyDigest = "sha256:" + 소문자 hex(sha256(raw)).
 * - 빈 body digest = [EMPTY_BODY_DIGEST]. timestamp = 수신 헤더 문자열 verbatim(엄격-10진 검증 후).
 * - 필드 사이만 개행("\n" 하드코딩 — 플랫폼 separator 금지) · 끝 개행 없음. 전부 UTF-8.
 *
 * ⚠️ N4: 서명 비교는 hex 문자열 equals가 아니라 hex 디코드 후 상수시간 바이트 비교
 * (MessageDigest.isEqual)로 한다. bodyDigest는 raw 수신 바이트에서 재계산한다(jackson 재인코딩 금지).
 */
object InternalCanonicalV1 {

    /** 빈 body의 bodyDigest — sha256(빈 바이트)의 고정 hex. */
    const val EMPTY_BODY_DIGEST = "sha256:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

    private const val HMAC_ALG = "HmacSHA256"
    private val HEX = "0123456789abcdef".toCharArray()

    /** raw 수신 바이트의 canonical-v1 bodyDigest. */
    fun bodyDigest(raw: ByteArray): String {
        if (raw.isEmpty()) return EMPTY_BODY_DIGEST
        val sha = MessageDigest.getInstance("SHA-256").digest(raw)
        return "sha256:" + hex(sha)
    }

    /** 서명 대상 바이트열(4필드 · method 대문자 · 필드 사이만 "\n" · 끝 개행 없음). */
    fun canonical(method: String, path: String, bodyDigest: String, timestamp: String): ByteArray {
        val text = method.uppercase() + "\n" + path + "\n" + bodyDigest + "\n" + timestamp
        return text.toByteArray(StandardCharsets.UTF_8)
    }

    /** canonical 바이트열의 HMAC-SHA256(raw 바이트 반환 — 비교 전 hex 디코드된 서명과 상수시간 대조). */
    fun hmac(key: ByteArray, canonical: ByteArray): ByteArray {
        val mac = Mac.getInstance(HMAC_ALG)
        mac.init(SecretKeySpec(key, HMAC_ALG))
        return mac.doFinal(canonical)
    }

    /** 소문자 hex 인코드. */
    fun hex(bytes: ByteArray): String {
        val out = CharArray(bytes.size * 2)
        for (i in bytes.indices) {
            val v = bytes[i].toInt() and 0xFF
            out[i * 2] = HEX[v ushr 4]
            out[i * 2 + 1] = HEX[v and 0x0F]
        }
        return String(out)
    }

    /**
     * 소문자 hex 문자열을 바이트로 디코드한다. 홀수 길이·hex 아닌 문자·대문자는 null(불일치로 취급).
     * 서명 헤더는 소문자 hex 고정이므로 대문자도 거절해 형태를 좁힌다.
     */
    fun decodeHexOrNull(s: String): ByteArray? {
        if (s.isEmpty() || s.length % 2 != 0) return null
        val out = ByteArray(s.length / 2)
        for (i in out.indices) {
            val hi = hexVal(s[i * 2])
            val lo = hexVal(s[i * 2 + 1])
            if (hi < 0 || lo < 0) return null
            out[i] = ((hi shl 4) or lo).toByte()
        }
        return out
    }

    private fun hexVal(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        else -> -1 // 대문자 'A'..'F' 포함 거절 — 서명은 소문자 hex 고정
    }
}
