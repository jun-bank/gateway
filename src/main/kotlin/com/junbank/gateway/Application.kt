package com.junbank.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication

/**
 * jun-bank 내부 API 게이트웨이.
 *
 * 현재는 라우팅 + 블루-그린 전환만 담당한다(도메인 서버 core·settlement·ledger로 프록시).
 * 채널별 라우팅·인증/인가 등은 후속 작업에서 이 위에 얹는다.
 * settlement·ledger 라우팅 규칙은 application.yml(spring.cloud.gateway.routes)에 선언하고,
 * 전환 대상인 core 라우트만 프로그램 라우트로 관리한다(routeswitch 패키지).
 */
@SpringBootApplication
@ConfigurationPropertiesScan
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
