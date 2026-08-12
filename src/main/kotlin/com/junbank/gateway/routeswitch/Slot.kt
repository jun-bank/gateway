package com.junbank.gateway.routeswitch

/** 블루-그린 slot. 외부(설정·API)와 주고받는 표기는 소문자 `blue` / `green` 이다. */
enum class Slot {
    BLUE,
    GREEN,
    ;

    val wireName: String get() = name.lowercase()

    companion object {
        fun parseOrNull(value: String?): Slot? =
            entries.firstOrNull { it.wireName == value?.trim()?.lowercase() }
    }
}
