package com.junbank.gateway.routeswitch

import org.slf4j.LoggerFactory
import org.springframework.core.Ordered
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DataBufferLimitException
import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.server.reactive.ServerHttpRequestDecorator
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Instant
import kotlin.math.abs

/** 인가 모드. audit = 무서명 통과(WARN)·서명은 검증. enforce = 무서명·불일치 401. */
enum class InternalAuthMode { AUDIT, ENFORCE }

/**
 * /internal 관리 표면 HMAC 인가 필터(R2 — 최우선순위 순수 WebFilter).
 *
 * 순서:
 *  1. `/internal` 이하 인가(N2): 핸들러 매핑과 **동일 소스**인 정규화된 RequestPath로 판정한다
 *     (인코딩·`..`·중복 슬래시 우회 차단). non-/internal(/core·/settlement·/ledger)은 body를
 *     건드리지 않고 즉시 통과(대용량 스트리밍 파손 방지).
 *  2. 무서명(N5 — X-Internal-Signature 부재)은 **body 읽기 전** 판정: enforce=401 / audit=WARN 통과.
 *  3. 서명 요청은 timestamp 필수(N5). 엄격-10진 검증 후 skew 창 검사 → 밖이면 401.
 *  4. **하드 크기 제한** 후 body join → 복사 → release → canonical-v1 재조립 → HMAC 재계산 →
 *     **상수시간 비교**(N4). 통과 시 캐시한 동일 raw byte를 [ServerHttpRequestDecorator]로 재공급
 *     (컨트롤러 @RequestBody가 같은 바이트를 읽는다).
 *  5. digest·검증·재주입 어느 오류에도 chain.filter 호출 금지(fail-closed).
 *
 * 기동 검증(fail-closed): 모드가 화이트리스트 밖이거나 키가 비어 있으면 생성자가 예외를 던져
 * 컨텍스트 기동을 막는다(R4 — audit·enforce 모두 키 필수).
 */
@Component
class InternalAuthWebFilter(props: InternalAuthProperties) : WebFilter, Ordered {

    private val log = LoggerFactory.getLogger(javaClass)

    private val mode: InternalAuthMode = parseMode(props.mode)
    // C4: 공백뿐인 키("   ")도 키 없음과 동일 취급(isBlank) — 길이만 보면 공백 키가 유효로 샌다.
    private val key: ByteArray = props.hmacKey
        .takeIf { it.isNotBlank() }
        ?.toByteArray(StandardCharsets.UTF_8)
        ?: throw IllegalStateException(
            "GATEWAY_INTERNAL_HMAC_KEY 미설정(또는 공백뿐) — /internal 인가 필터를 세울 수 없다(fail-closed). " +
                "audit·enforce 모두 키가 필수다(키 없으면 검증 못 하는 경계라 audit 성공 위장이 된다).",
        )
    private val skewSeconds: Long = props.skewSeconds
    private val maxBodyBytes: Int = props.maxBodyBytes

    // N2: 핸들러 매핑(RequestMappingHandlerMapping)과 같은 PathPattern·정규화 소스로 매칭한다.
    private val internalPattern: PathPattern = PathPatternParser.defaultInstance.parse("/internal/**")

    init {
        require(skewSeconds >= 0) { "GATEWAY_INTERNAL_SKEW_SECONDS는 음수일 수 없다: $skewSeconds" }
        require(maxBodyBytes > 0) { "GATEWAY_INTERNAL_MAX_BODY_BYTES는 >0 이어야 한다: $maxBodyBytes" }
        // 키 값은 절대 로그에 남기지 않는다(B3).
        log.info(
            "internal-auth filter active: mode={} skewSeconds={} maxBodyBytes={}",
            mode, skewSeconds, maxBodyBytes,
        )
    }

    override fun getOrder(): Int = Ordered.HIGHEST_PRECEDENCE

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        val request = exchange.request

        // (1) /internal 판정 — 정규화된 RequestPath로. non-/internal은 즉시 통과(body 무접촉).
        if (!internalPattern.matches(request.path.pathWithinApplication())) {
            return chain.filter(exchange)
        }

        val signature = request.headers.getFirst(SIGNATURE_HEADER)
        // (2) 무서명(N5) — body 읽기 전 판정.
        if (signature == null) {
            return when (mode) {
                InternalAuthMode.ENFORCE -> deny(exchange, HttpStatus.UNAUTHORIZED)
                InternalAuthMode.AUDIT -> {
                    log.warn(
                        "unsigned /internal call — LAN-only 과도기 통과(audit mode): {} {}",
                        request.method, request.path.value(),
                    )
                    chain.filter(exchange)
                }
            }
        }

        // (3) 서명 있음 → timestamp 필수(N5). 엄격-10진 검증 후 skew.
        val tsHeader = request.headers.getFirst(TIMESTAMP_HEADER)
            ?: return deny(exchange, HttpStatus.UNAUTHORIZED)
        if (!isStrictDecimal(tsHeader)) return deny(exchange, HttpStatus.UNAUTHORIZED)
        val ts = tsHeader.toLongOrNull() ?: return deny(exchange, HttpStatus.UNAUTHORIZED)
        val now = Instant.now().epochSecond
        if (abs(now - ts) > skewSeconds) return deny(exchange, HttpStatus.UNAUTHORIZED)

        // canonical에 쓰는 값은 여기서 고정한다. path는 수신 raw 경로(쿼리 제외), timestamp는
        // 수신 헤더 verbatim(재포맷 금지 — N4). method는 canonical 조립에서 대문자 정규화.
        val method = request.method.name()
        val path = request.uri.rawPath

        // (4) 하드 크기 제한 후 body join → 복사 → release → 검증 → 통과 시 동일 바이트 재공급.
        return DataBufferUtils.join(request.body, maxBodyBytes)
            .map { buffer ->
                val bytes = ByteArray(buffer.readableByteCount())
                buffer.read(bytes)
                DataBufferUtils.release(buffer)
                bytes
            }
            .defaultIfEmpty(EMPTY_BYTES) // GET 등 빈 body는 명시적 빈 바이트로
            .flatMap { bytes ->
                val digest = InternalCanonicalV1.bodyDigest(bytes)
                val canonical = InternalCanonicalV1.canonical(method, path, digest, tsHeader)
                val expected = InternalCanonicalV1.hmac(key, canonical)
                val provided = InternalCanonicalV1.decodeHexOrNull(signature)
                // N4: hex 디코드 후 상수시간 바이트 비교(hex 문자열 equals 금지).
                if (provided == null || !MessageDigest.isEqual(expected, provided)) {
                    deny(exchange, HttpStatus.UNAUTHORIZED)
                } else {
                    if (mode == InternalAuthMode.AUDIT) {
                        // ③ 누락 방지: "서명된 호출을 실제로 받았다"를 양성으로 관측한다.
                        log.info(
                            "valid /internal signature accepted(audit mode · valid-signature 양성): {} {}",
                            method, request.path.value(),
                        )
                    }
                    chain.filter(decorate(exchange, bytes))
                }
            }
            // (5) 어떤 오류에도 chain.filter 금지 — fail-closed.
            .onErrorResume { e ->
                if (e is DataBufferLimitException) {
                    deny(exchange, HttpStatus.PAYLOAD_TOO_LARGE)
                } else {
                    log.warn("internal-auth 필터 오류(fail-closed 차단): {}", e.toString())
                    deny(exchange, HttpStatus.UNAUTHORIZED)
                }
            }
    }

    /** 통과한 서명 요청에 캐시한 동일 raw byte를 재공급한다(컨트롤러 @RequestBody가 같은 바이트를 읽음). */
    private fun decorate(exchange: ServerWebExchange, bytes: ByteArray): ServerWebExchange {
        val decorated = object : ServerHttpRequestDecorator(exchange.request) {
            override fun getBody(): Flux<DataBuffer> = Flux.defer {
                Flux.just(exchange.response.bufferFactory().wrap(bytes))
            }
        }
        return exchange.mutate().request(decorated).build()
    }

    /** 인증 실패 응답을 직접 완결한다(chain.filter 없이) — 키·사유 세부는 body에 싣지 않는다. */
    private fun deny(exchange: ServerWebExchange, status: HttpStatus): Mono<Void> {
        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_JSON
        val body = if (status == HttpStatus.PAYLOAD_TOO_LARGE) {
            """{"error":"payload too large"}"""
        } else {
            """{"error":"unauthorized"}"""
        }.toByteArray(StandardCharsets.UTF_8)
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body)))
    }

    private companion object {
        val EMPTY_BYTES = ByteArray(0)
        const val SIGNATURE_HEADER = "X-Internal-Signature"
        const val TIMESTAMP_HEADER = "X-Internal-Timestamp"

        // 정확 비교(원문 그대로 · 대소문자 구분 · 주변 공백 불허). trim·lowercase 하면
        // ` AUDIT `·`AUDIT`이 audit로 접혀 무서명이 열린다(codex 재현 — 실 fail-open). 계약은
        // **정확히 "audit" 또는 "enforce"**, 그 밖(대소문자 변형·공백 포함·미지값·빈값) 전부 기동 거부.
        fun parseMode(raw: String): InternalAuthMode = when (raw) {
            "audit" -> InternalAuthMode.AUDIT
            "enforce" -> InternalAuthMode.ENFORCE
            else -> throw IllegalStateException(
                "GATEWAY_INTERNAL_AUTH_MODE는 정확히 'audit' 또는 'enforce' 여야 한다(대소문자 구분·주변 공백 불허·미설정 기본 enforce) — 받은 값='$raw'. " +
                    "변형(예: 'AUDIT'·' audit ')을 조용히 수락하면 무서명이 열린다(fail-closed).",
            )
        }

        /** 엄격 10진: 앞자리 0·부호·소수점·공백 금지(서명자 strconv.FormatInt와 갈리지 않게). */
        val STRICT_DECIMAL = Regex("^[1-9][0-9]{0,18}$")
        fun isStrictDecimal(s: String): Boolean = STRICT_DECIMAL.matches(s)
    }
}
