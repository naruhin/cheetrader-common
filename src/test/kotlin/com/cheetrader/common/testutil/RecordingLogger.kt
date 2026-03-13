package com.cheetrader.common.testutil

import com.cheetrader.common.logging.ExchangeLogger

class RecordingLogger : ExchangeLogger {
    data class LogEntry(val level: String, val message: String, val throwable: Throwable? = null)

    val entries = mutableListOf<LogEntry>()

    override fun debug(message: () -> String) { entries.add(LogEntry("DEBUG", message())) }
    override fun info(message: () -> String) { entries.add(LogEntry("INFO", message())) }
    override fun warn(message: () -> String) { entries.add(LogEntry("WARN", message())) }
    override fun error(message: () -> String) { entries.add(LogEntry("ERROR", message())) }
    override fun error(throwable: Throwable, message: () -> String) { entries.add(LogEntry("ERROR", message(), throwable)) }

    fun clear() = entries.clear()
    fun hasLevel(level: String) = entries.any { it.level == level }
    fun hasMessageContaining(text: String) = entries.any { text in it.message }
}
