package com.junbank.gateway.routeswitch

import org.springframework.cloud.gateway.route.Route
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.context.ApplicationEventPublisher
import reactor.core.publisher.Flux
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 실패 경로를 만들기 위한 게이트웨이 조립 — 스프링 컨텍스트 없이 진짜 구현체
 * (registry · service · controller · state store)를 그대로 쓰고, 라우트 캐시만 흉내 낸다.
 *
 * 실제 SCG에서는 RefreshRoutesEvent가 CachingRouteLocator를 다시 채운다. 여기서는 발행된
 * 이벤트를 받아 [routeUri]를 registry 상태로 맞춰 그 동작을 재현하고,
 *   [refreshApplies] = false → 갱신이 영영 반영되지 않는 상황(원복 경로)
 *   [publishThrows]        → 이벤트 발행 중 예외(전 구간 예외 경로)
 * 를 만들 수 있게 한다.
 */
class FakeGateway(
    private val stateFilePath: String? = null,
    activeSlot: String = "blue",
) {
    val properties = CoreRouteProperties(BLUE_URI, GREEN_URI, activeSlot, stateFilePath)
    val store = CoreStateStore(properties)
    val registry = CoreRouteRegistry(properties, store)

    @Volatile
    var refreshApplies: Boolean = true

    @Volatile
    var publishThrows: RuntimeException? = null

    /** 이벤트 발행 시점의 상태 파일 내용 — write-ahead 순서(파일이 라우트보다 앞선다)를 관측한다. */
    val stateFileAtPublish = CopyOnWriteArrayList<String>()

    /** 라우트가 실제로 가리킨 적 있는 URI 이력. */
    val routeUris = CopyOnWriteArrayList<String>()

    @Volatile
    private var routeUri: URI = registry.uriOf(registry.snapshot().slot)

    private val routeLocator = RouteLocator {
        Flux.just(Route.async().id(CORE_ROUTE_ID).uri(routeUri).predicate { true }.build())
    }

    private val publisher = object : ApplicationEventPublisher {
        override fun publishEvent(event: Any) {
            stateFilePath?.let { path ->
                stateFileAtPublish += runCatching { Files.readString(Path.of(path)) }.getOrDefault("<none>")
            }
            publishThrows?.let { throw it }
            if (refreshApplies) {
                routeUri = registry.uriOf(registry.snapshot().slot)
                routeUris += routeUri.toString()
            }
        }
    }

    val service = RouteSwitchService(registry, routeLocator, publisher, store)
    val controller = InternalRouteController(registry, service)

    /** 지금 요청이 실제로 실려 가는 곳(= 라우트 캐시의 core URI). */
    fun activeRouteUri(): String = routeUri.toString()

    companion object {
        const val BLUE_URI = "http://blue.test:8080"
        const val GREEN_URI = "http://green.test:8080"
    }
}
