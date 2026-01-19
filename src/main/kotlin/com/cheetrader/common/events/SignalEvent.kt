package com.cheetrader.common.events

import java.time.Instant

data class SignalEvent(
    val eventType: SignalEventType,
    val signal: SignalDto,
    val timestamp: Instant = Instant.now()
)

enum class SignalEventType {
    OPENED,
    CLOSED,
    TP_HIT,
    SL_HIT,
    CANCELLED
}
