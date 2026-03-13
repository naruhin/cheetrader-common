package com.cheetrader.common.logging

/**
 * Logger interface for exchange operations.
 * Platform-specific implementations:
 * - PC Client: KotlinLogging (SLF4J)
 * - Android: Timber
 */
interface ExchangeLogger {
    fun debug(message: () -> String)
    fun info(message: () -> String)
    fun warn(message: () -> String)
    fun error(message: () -> String)
    fun error(throwable: Throwable, message: () -> String)
}

/**
 * No-op logger for cases when logging is not needed
 */
object NoOpLogger : ExchangeLogger {
    override fun debug(message: () -> String) {}
    override fun info(message: () -> String) {}
    override fun warn(message: () -> String) {}
    override fun error(message: () -> String) {}
    override fun error(throwable: Throwable, message: () -> String) {}
}

/**
 * Simple console logger for debugging
 */
class ConsoleLogger(private val tag: String) : ExchangeLogger {
    override fun debug(message: () -> String) {
        println("[$tag] DEBUG: ${message()}")
    }

    override fun info(message: () -> String) {
        println("[$tag] INFO: ${message()}")
    }

    override fun warn(message: () -> String) {
        println("[$tag] WARN: ${message()}")
    }

    override fun error(message: () -> String) {
        System.err.println("[$tag] ERROR: ${message()}")
    }

    override fun error(throwable: Throwable, message: () -> String) {
        System.err.println("[$tag] ERROR: ${message()}")
        throwable.printStackTrace(System.err)
    }
}
