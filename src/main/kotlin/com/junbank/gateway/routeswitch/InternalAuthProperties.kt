package com.junbank.gateway.routeswitch

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * /internal 관리 표면 HMAC 인가 설정. 값은 전부 환경에서 온다(하드코딩 0 · 키는 로그·응답 미노출).
 *   GATEWAY_INTERNAL_AUTH_MODE / GATEWAY_INTERNAL_HMAC_KEY / GATEWAY_INTERNAL_SKEW_SECONDS
 *   — 바인딩·기본값은 application.yml 참조.
 *
 * 유효성(모드 화이트리스트·키 필수)은 [InternalAuthWebFilter] 생성 시 검증해 fail-closed로
 * 기동을 막는다(잘못된 값·키 없음이면 컨텍스트가 뜨지 않는다).
 */
@ConfigurationProperties(prefix = "junbank.internal-auth")
data class InternalAuthProperties(
    /** audit | enforce. 미설정 기본 = enforce, 그 밖의 값 = 기동 거부. */
    val mode: String = "enforce",
    /** HMAC 공유 비밀(raw 바이트). audit·enforce 모두 비어 있으면 기동 거부. */
    val hmacKey: String = "",
    /** 신선도 skew 창(초). |now - timestamp| 가 이 값을 넘으면 401. */
    val skewSeconds: Long = 30,
    /** 서명 요청 body 하드 크기 제한(바이트). 인증 전 무제한 join = 메모리 DoS. */
    val maxBodyBytes: Int = 65536,
)
