package com.junbank.gateway.routeswitch

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

/**
 * 활성 slot과 마지막으로 수락한 fencing token을 파일 하나에 지속화한다.
 * `CORE_STATE_FILE`(= `junbank.routes.core.state-file`)이 설정된 경우에만 동작하고,
 * 없으면 모든 연산이 no-op이다(= 기존 인메모리 동작).
 *
 * ## 왜 파일인가 — 재시작 창 닫기
 * 상태가 인메모리면 게이트웨이 재시작 직후 token이 0으로 리셋되어, 이미 지나간 낮은 token을
 * 든 stale 실행자의 전환이 한 번 수락된다(ADR-031 BG-4 위반). 파일에 {slot, token}을 남기면
 * 재시작이 그 창을 열지 않는다.
 *
 * ## 크래시 안전 — write-ahead 커밋 순서
 * [RouteSwitchService]는 ⑴ 파일에 목표 상태를 먼저 기록하고 ⑵ 그 다음에 라우트를 교체한다.
 * 즉 파일은 항상 라우트보다 앞서거나 같다. 어느 지점에서 죽어도 파일이 가리키는 slot은
 *   - "그 시점에 CD-1(green 헬스 통과)을 지난 전환 목표" 이거나
 *   - "전환 전의 기존 active"
 * 둘 중 하나다. 배포 절차상 blue를 내리는 것은 전환이 성공한 뒤 ⑨ 단계에서만 일어나므로,
 * 두 후보 모두 그 시점에 살아 있는 slot이다 — 재시작 복원이 죽은 slot을 가리키지 않는다.
 * (원복도 같은 순서를 지킨다: 파일을 이전 slot으로 되돌린 뒤 라우트를 되돌린다.)
 *
 * ## 원자성
 * 같은 디렉터리의 임시 파일에 쓰고 fsync 한 뒤 ATOMIC_MOVE 로 rename 한다 — 읽는 쪽은
 * 반쯤 쓰인 파일을 보지 않는다. 디렉터리 엔트리 자체의 fsync 는 하지 않으므로 OS 크래시
 * 직후 rename 이 유실될 수 있다(그 경우 파일은 직전 상태 = 여전히 살아 있는 slot을 가리킨다).
 *
 * 형식은 사람이 읽고 고칠 수 있는 두 줄이다:
 * ```
 * slot=green
 * token=12
 * ```
 */
@Component
class CoreStateStore(properties: CoreRouteProperties) {

    /** 파일에 남기는 정본 상태. token 은 int64 양수(≥1), 아직 전환이 없었다면 0. */
    data class State(val slot: Slot, val token: Long)

    private val log = LoggerFactory.getLogger(javaClass)

    private val path: Path? = properties.stateFile?.trim()
        ?.takeIf { it.isNotEmpty() }
        ?.let { Path.of(it).toAbsolutePath() }

    /** 지속화가 켜져 있나 — 꺼져 있으면 [read]는 null, [write]는 no-op. */
    val enabled: Boolean get() = path != null

    /** 저장된 상태. 지속화가 꺼져 있거나 파일이 없거나 파싱 불가면 null(= 호출자가 env 기본으로). */
    fun read(): State? {
        val file = path ?: return null
        if (!Files.isRegularFile(file)) return null
        return try {
            parse(Files.readString(file)).also {
                if (it == null) log.error("core state file is unreadable, falling back to env: {}", file)
            }
        } catch (e: IOException) {
            log.error("failed to read core state file {}, falling back to env", file, e)
            null
        }
    }

    /**
     * 상태를 원자적으로 덮어쓴다. 실패는 예외로 올린다 — 호출자는 라우트를 바꾸지 않거나
     * (write-ahead 단계) 전환 결과를 INDETERMINATE 로 격상해야 한다.
     */
    fun write(state: State) {
        val file = path ?: return
        val dir = file.parent ?: throw IOException("core state file has no parent directory: $file")
        Files.createDirectories(dir)
        val temp = Files.createTempFile(dir, ".core-state", ".tmp")
        try {
            FileChannel.open(temp, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
                channel.write(ByteBuffer.wrap(render(state).toByteArray()))
                channel.force(true)
            }
            Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Exception) {
            runCatching { Files.deleteIfExists(temp) }
            throw e
        }
    }

    private fun render(state: State) = "slot=${state.slot.wireName}\ntoken=${state.token}\n"

    private fun parse(text: String): State? {
        val fields = text.lineSequence()
            .map(String::trim)
            .filter { it.isNotEmpty() && !it.startsWith("#") }
            .mapNotNull { line ->
                val separator = line.indexOf('=').takeIf { it > 0 } ?: return@mapNotNull null
                line.substring(0, separator).trim() to line.substring(separator + 1).trim()
            }
            .toMap()

        val slot = Slot.parseOrNull(fields["slot"]) ?: return null
        val token = fields["token"]?.toLongOrNull()?.takeIf { it >= 0 } ?: return null
        return State(slot, token)
    }
}
