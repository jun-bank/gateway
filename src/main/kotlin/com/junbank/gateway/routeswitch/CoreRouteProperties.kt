package com.junbank.gateway.routeswitch

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * core 라우트의 slot(blue|green) → URI 매핑.
 *
 * 이 매핑의 소유자는 배포 환경(환경변수)이다 — 게이트웨이는 어느 slot이 어떤 주소인지
 * 스스로 정하지 않고, 지금 어느 slot이 활성인지만 관리한다(ADR-031 BG-1).
 *   CORE_BLUE_URI / CORE_GREEN_URI / CORE_ACTIVE_SLOT — 바인딩은 application.yml 참조.
 */
@ConfigurationProperties(prefix = "junbank.routes.core")
data class CoreRouteProperties(
    val blueUri: String,
    val greenUri: String,
    /** 게이트웨이 기동 시점의 활성 slot. 이후 전환은 /internal API가 인메모리로 관리한다. */
    val activeSlot: String,
)
