package com.junbank.gateway.routeswitch

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * core 라우트의 slot(blue|green) → URI 매핑 + 상태 지속화 위치.
 *
 * 이 매핑의 소유자는 배포 환경(환경변수)이다 — 게이트웨이는 어느 slot이 어떤 주소인지
 * 스스로 정하지 않고, 지금 어느 slot이 활성인지만 관리한다(ADR-031 BG-1).
 *   CORE_BLUE_URI / CORE_GREEN_URI / CORE_ACTIVE_SLOT / CORE_STATE_FILE
 *   — 바인딩·기본값은 application.yml 참조.
 */
@ConfigurationProperties(prefix = "junbank.routes.core")
data class CoreRouteProperties(
    val blueUri: String,
    val greenUri: String,
    /** 상태 파일이 없을 때의 기동 시점 활성 slot(파일이 있으면 파일이 우선한다). */
    val activeSlot: String,
    /**
     * {활성 slot, 마지막 수락 token}을 남길 파일 경로. 비어 있으면 지속화를 끄고
     * 기존 인메모리 동작을 유지한다(재시작 시 token 리셋 — [CoreStateStore] 참조).
     */
    val stateFile: String? = null,
)
