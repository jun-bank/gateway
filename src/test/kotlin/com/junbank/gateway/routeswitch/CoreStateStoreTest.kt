package com.junbank.gateway.routeswitch

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path

/**
 * {slot, token} 지속화의 왕복: 기록 → (재기동 시뮬 = 새 store·registry 생성) → 복원.
 * 이게 닫는 창은 "재시작 직후 token 0 이라 이미 지나간 낮은 token이 한 번 수락되는" 구멍이다.
 */
class CoreStateStoreTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `기록한 상태는 재기동 후 복원되고 CORE_ACTIVE_SLOT 보다 우선한다`() {
        val stateFile = tempDir.resolve("state/core-state") // 없는 디렉터리도 만들어 쓴다
        write(stateFile, Slot.GREEN, 12)

        // 재기동 시뮬: env는 여전히 blue인데, 파일이 있으므로 green/12로 올라와야 한다.
        val restored = registryOf(stateFile, activeSlot = "blue")

        assertThat(restored.snapshot()).isEqualTo(CoreRouteRegistry.Snapshot(Slot.GREEN, 12))
        // 임시 파일을 남기지 않는다(temp+rename 뒤처리).
        assertThat(Files.list(stateFile.parent).use { it.toList() }).containsExactly(stateFile)
    }

    @Test
    fun `전환으로 남긴 상태도 그대로 복원된다`() {
        val stateFile = tempDir.resolve("core-state")
        val gateway = FakeGateway(stateFilePath = stateFile.toString(), activeSlot = "blue")
        assertThat(gateway.service.switchTo(Slot.GREEN, 8))
            .isEqualTo(RouteSwitchService.Result.Applied(Slot.GREEN, 8))

        val restarted = registryOf(stateFile, activeSlot = "blue")

        assertThat(restarted.snapshot()).isEqualTo(CoreRouteRegistry.Snapshot(Slot.GREEN, 8))
    }

    @Test
    fun `파일이 없거나 읽을 수 없으면 env 기본으로 뜬다`() {
        val missing = tempDir.resolve("absent")
        assertThat(registryOf(missing, activeSlot = "green").snapshot())
            .isEqualTo(CoreRouteRegistry.Snapshot(Slot.GREEN, 0))

        val corrupt = tempDir.resolve("corrupt")
        Files.writeString(corrupt, "slot=purple\ntoken=oops\n")
        assertThat(registryOf(corrupt, activeSlot = "blue").snapshot())
            .isEqualTo(CoreRouteRegistry.Snapshot(Slot.BLUE, 0))
    }

    @Test
    fun `CORE_STATE_FILE 미설정이면 지속화를 하지 않는다`() {
        val gateway = FakeGateway(stateFilePath = null, activeSlot = "blue")
        assertThat(gateway.store.enabled).isFalse()
        assertThat(gateway.store.read()).isNull()

        assertThat(gateway.service.switchTo(Slot.GREEN, 5))
            .isEqualTo(RouteSwitchService.Result.Applied(Slot.GREEN, 5))

        // 기존 인메모리 동작 — 재기동하면 env 기본으로 돌아간다(알려진 리스크).
        assertThat(registryOf(null, activeSlot = "blue").snapshot())
            .isEqualTo(CoreRouteRegistry.Snapshot(Slot.BLUE, 0))
        assertThat(Files.list(tempDir).use { it.toList() }).isEmpty()
    }

    @Test
    fun `덮어쓰기는 마지막 기록만 남긴다`() {
        val stateFile = tempDir.resolve("core-state")
        write(stateFile, Slot.GREEN, 3)
        write(stateFile, Slot.BLUE, 9)

        assertThat(storeOf(stateFile).read()).isEqualTo(CoreStateStore.State(Slot.BLUE, 9))
        assertThat(Files.readString(stateFile)).isEqualTo("slot=blue\ntoken=9\n")
    }

    private fun write(stateFile: Path, slot: Slot, token: Long) =
        storeOf(stateFile).write(CoreStateStore.State(slot, token))

    private fun storeOf(stateFile: Path?) = CoreStateStore(propertiesOf(stateFile, "blue"))

    private fun registryOf(stateFile: Path?, activeSlot: String): CoreRouteRegistry {
        val properties = propertiesOf(stateFile, activeSlot)
        return CoreRouteRegistry(properties, CoreStateStore(properties))
    }

    private fun propertiesOf(stateFile: Path?, activeSlot: String) = CoreRouteProperties(
        blueUri = FakeGateway.BLUE_URI,
        greenUri = FakeGateway.GREEN_URI,
        activeSlot = activeSlot,
        stateFile = stateFile?.toString(),
    )
}
