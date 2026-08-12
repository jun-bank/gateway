package com.junbank.gateway

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.runApplication

/**
 * jun-bank 내부 API 게이트웨이.
 *
 * 현재는 순수 라우팅만 담당한다(도메인 서버 core·settlement·ledger로 프록시).
 * 채널별 라우팅·인증/인가 등은 후속 작업에서 이 위에 얹는다.
 * 라우팅 규칙은 코드가 아니라 application.yml(spring.cloud.gateway.routes)에 선언한다.
 */
@SpringBootApplication
class GatewayApplication

fun main(args: Array<String>) {
    runApplication<GatewayApplication>(*args)
}
