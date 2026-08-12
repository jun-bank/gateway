package com.junbank.gateway.routeswitch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import kotlin.concurrent.thread

/**
 * 전환이 **실패했을 때**의 계약: 원복해도 fencing이 후퇴하지 않고(S2), 호출자가 실패의
 * 보증 수준을 응답으로 구별할 수 있어야 한다(S3). 상태 파일은 라우트보다 앞서 기록된다(S5).
 *
 * 실패 경로는 스프링 컨텍스트로 만들기 어려워(라우트 캐시가 항상 갱신된다) [FakeGateway]로
 * 라우트 캐시만 흉내 내고, 나머지는 진짜 구현체를 그대로 쓴다.
 */
class RouteSwitchFailureTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `반영 실패로 원복하면 ROLLED_BACK 이고 시도한 token 아래는 계속 stale 이다`() {
        val stateFile = tempDir.resolve("core-state")
        val gateway = FakeGateway(stateFilePath = stateFile.toString())
        gateway.refreshApplies = false // 라우트 캐시가 갱신되지 않는 상황

        val failed = gateway.controller.switch("core", SwitchRequest("green", 10)).block(TIMEOUT)!!

        // 미전환이 "보증"되는 실패다 — 원복이 실제 라우트에 반영된 것까지 확인했다.
        assertThat(failed.statusCode.value()).isEqualTo(500)
        val body = failed.body as SwitchFailureResponse
        assertThat(body.state).isEqualTo("ROLLED_BACK")
        assertThat(body.error).isEqualTo("route refresh did not take effect")
        assertThat(gateway.activeRouteUri()).isEqualTo(FakeGateway.BLUE_URI)

        // S2: slot만 되돌리고 token은 max(이전, 시도)로 유지한다 — 후퇴시키면 아래 9가 수락된다.
        assertThat(gateway.registry.snapshot()).isEqualTo(CoreRouteRegistry.Snapshot(Slot.BLUE, 10))
        assertThat(Files.readString(stateFile)).contains("slot=blue").contains("token=10")

        gateway.refreshApplies = true
        val stale = gateway.controller.switch("core", SwitchRequest("green", 9)).block(TIMEOUT)!!
        assertThat(stale.statusCode.value()).isEqualTo(409)
        val staleBody = stale.body as StaleTokenResponse
        assertThat(staleBody.lastAcceptedToken).isEqualTo(10)
        assertThat(staleBody.state).isEqualTo("NOT_ATTEMPTED")
        assertThat(gateway.activeRouteUri()).isEqualTo(FakeGateway.BLUE_URI)
    }

    @Test
    fun `전환 도중 예외가 새면 INDETERMINATE 로 답한다`() {
        val gateway = FakeGateway()
        gateway.publishThrows = IllegalStateException("event bus down")

        val failed = gateway.controller.switch("core", SwitchRequest("green", 4)).block(TIMEOUT)!!

        assertThat(failed.statusCode.value()).isEqualTo(500)
        val body = failed.body as SwitchFailureResponse
        // 실상태 불명 — 호출자는 미전환으로 단정하면 안 된다(서비스 중인 slot을 내리는 사고).
        assertThat(body.state).isEqualTo("INDETERMINATE")
        assertThat(body.error).contains("IllegalStateException")
        // 예외 경로에서도 원복은 시도한다 — 잠정 상태(green)를 남기지 않는다.
        assertThat(gateway.registry.snapshot()).isEqualTo(CoreRouteRegistry.Snapshot(Slot.BLUE, 4))
    }

    @Test
    fun `상태를 파일에 남기지 못하면 라우트를 건드리지 않고 INDETERMINATE 다`() {
        // 디렉터리로 쓸 수 없는 경로(중간 요소가 일반 파일) → write-ahead 기록이 실패한다.
        val blocker = Files.createFile(tempDir.resolve("blocker"))
        val gateway = FakeGateway(stateFilePath = blocker.resolve("core-state").toString())

        val failed = gateway.controller.switch("core", SwitchRequest("green", 3)).block(TIMEOUT)!!

        assertThat(failed.statusCode.value()).isEqualTo(500)
        assertThat((failed.body as SwitchFailureResponse).state).isEqualTo("INDETERMINATE")
        // write-ahead 계약: 파일에 못 남겼으면 라우트는 목표를 가리킨 적이 없다.
        assertThat(gateway.routeUris).doesNotContain(FakeGateway.GREEN_URI)
        assertThat(gateway.activeRouteUri()).isEqualTo(FakeGateway.BLUE_URI)
        assertThat(gateway.registry.snapshot()).isEqualTo(CoreRouteRegistry.Snapshot(Slot.BLUE, 3))
    }

    @Test
    fun `상태 파일은 라우트 갱신보다 먼저 기록된다`() {
        val stateFile = tempDir.resolve("core-state")
        val gateway = FakeGateway(stateFilePath = stateFile.toString())

        val ok = gateway.controller.switch("core", SwitchRequest("green", 7)).block(TIMEOUT)!!

        assertThat(ok.statusCode.value()).isEqualTo(200)
        // 라우트 갱신 이벤트가 나간 그 시점에 파일은 이미 목표 상태를 담고 있었다(⑴→⑵ 순서).
        assertThat(gateway.stateFileAtPublish).isNotEmpty
        assertThat(gateway.stateFileAtPublish.first()).contains("slot=green").contains("token=7")
        assertThat(gateway.activeRouteUri()).isEqualTo(FakeGateway.GREEN_URI)
        assertThat(Files.readString(stateFile)).contains("slot=green").contains("token=7")
    }

    @Test
    fun `전환 진행 중 상태 조회는 잠정 상태를 보지 않는다`() {
        val gateway = FakeGateway()
        gateway.refreshApplies = false // 전환이 실패로 끝날 때까지 임계구역을 붙잡는다

        val switching = thread { gateway.service.switchTo(Slot.GREEN, 10) }
        Thread.sleep(200) // 전환이 임계구역에 들어가기를 기다린다

        val startedAt = System.nanoTime()
        val status = gateway.controller.status("core").block(TIMEOUT)!!
        val waitedMillis = (System.nanoTime() - startedAt) / 1_000_000
        switching.join(TIMEOUT.toMillis())

        // 전환과 같은 락에서 직렬화됐다 — 조회가 전환 완료를 기다렸다.
        assertThat(waitedMillis).isGreaterThan(100)
        val body = status.body as RouteStatusResponse
        // 잠정 상태(green)가 아니라 원복이 끝난 상태만 보인다.
        assertThat(body.activeSlot).isEqualTo("blue")
        assertThat(body.uri).isEqualTo(FakeGateway.BLUE_URI)
        assertThat(body.lastAcceptedToken).isEqualTo(10)
    }

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}
