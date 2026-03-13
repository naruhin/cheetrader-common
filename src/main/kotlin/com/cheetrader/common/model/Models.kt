package com.cheetrader.common.model

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Smart price formatting: adapts decimal places to the price magnitude.
 *
 * - BTC  ~60000   → "60000.12"        (2 decimals)
 * - ETH  ~3000    → "3000.12"         (2 decimals)
 * - DOGE ~0.15    → "0.1534"          (4 decimals)
 * - SHIB ~0.00002 → "0.00002134"      (8 decimals)
 */
fun formatPrice(price: Double): String {
    if (price == 0.0) return "0"
    val absPrice = abs(price)
    val decimals = when {
        absPrice >= 100 -> 2
        absPrice >= 1 -> 3
        absPrice >= 0.01 -> 4
        absPrice >= 0.0001 -> 6
        else -> 8
    }
    return "%.${decimals}f".format(price).trimEnd('0').trimEnd('.')
        .let { if ('.' in it && it.substringAfter('.').length < 2) it + "0".repeat(2 - it.substringAfter('.').length) else it }
}

/**
 * Safe price formatting without scientific notation (e.g. 1.23E-4)
 */
fun formatPricePlain(price: Double): String {
    return java.math.BigDecimal.valueOf(price).stripTrailingZeros().toPlainString()
}

// ============================================
// Exchange Configuration
// ============================================

@Serializable
enum class Exchange(val displayName: String, val baseUrl: String, val testnetUrl: String) {
    BINANCE("Binance", "https://fapi.binance.com", "https://testnet.binancefuture.com"),
    BINGX("BingX", "https://open-api.bingx.com", "https://open-api-vst.bingx.com"),
    OKX("OKX", "https://www.okx.com", "https://www.okx.com"),
    BYBIT("Bybit", "https://api.bybit.com", "https://api-testnet.bybit.com")
}

@Serializable
data class ExchangeConfig(
    val exchange: Exchange = Exchange.BINANCE,
    val apiKey: String = "",
    val secretKey: String = "",
    val passphrase: String = "", // OKX passphrase
    val testnet: Boolean = true
)

@Serializable
data class TradingConfig(
    val autoExecute: Boolean = false,
    val balancePercent: Double = 5.0,
    val leverage: Int = 20,
    val crossMargin: Boolean = true,
    val confirmBeforeExecute: Boolean = true,
    val maxDailyLossPct: Double = 5.0,
    val maxOpenPositions: Int = 3,
    val maxSlippageBps: Int = 25,
    val minRiskReward: Double = 1.5,
    val allowedSymbolsCsv: String = "",
    val tradingWindow: String = "00:00-23:59"
)

// ============================================
// Trading Signal
// ============================================

data class Signal(
    val id: String,
    val symbol: String,
    val type: SignalType,
    val entryPrice: Double,
    val takeProfit: Double?,
    val stopLoss: Double?,
    val timeframe: Timeframe?,
    val orderType: OrderType,
    val strategyName: String,
    val timestamp: Long,
    val eventType: SignalEventType,
    val metadata: Map<String, String> = emptyMap()
) {
    val formattedTime: String
        get() = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("HH:mm:ss"))

    /** True only for actionable signals that can be traded (OPENED). */
    val isExecutable: Boolean
        get() = eventType == SignalEventType.OPENED

    /** True for informational events: TP hit, SL hit, closed, cancelled. */
    val isNotification: Boolean
        get() = eventType != SignalEventType.OPENED
                && eventType != SignalEventType.KEEPALIVE
                && eventType != SignalEventType.UNKNOWN
}

enum class SignalType(val displayName: String, val color: Long) {
    LONG("LONG", 0xFF4ADE80),   // Green
    SHORT("SHORT", 0xFFEF4444)  // Red
}

enum class OrderType {
    MARKET, LIMIT
}

enum class Timeframe(val displayName: String) {
    M1("1m"), M5("5m"), M15("15m"), M30("30m"),
    H1("1h"), H4("4h"), D1("1d"), W1("1w");

    companion object {
        fun fromString(value: String): Timeframe {
            return when (value.lowercase()) {
                "1m", "m1", "1min" -> M1
                "5m", "m5", "5min" -> M5
                "15m", "m15", "15min" -> M15
                "30m", "m30", "30min" -> M30
                "1h", "h1", "1hour" -> H1
                "4h", "h4", "4hour" -> H4
                "1d", "d1", "1day" -> D1
                "1w", "w1", "1week" -> W1
                else -> throw IllegalArgumentException("Unknown timeframe: $value")
            }
        }
    }
}

enum class SignalEventType(val displayName: String, val color: Long) {
    OPENED("OPEN", 0xFF4ADE80),
    CLOSED("CLOSED", 0xFF60A5FA),
    TP_HIT("TP", 0xFFF59E0B),
    SL_HIT("SL", 0xFFEF4444),
    CANCELLED("CANCEL", 0xFF5B5B80),
    KEEPALIVE("KEEPALIVE", 0xFF5B5B80),
    UNKNOWN("UNKNOWN", 0xFF5B5B80)
}

// ============================================
// Order Execution
// ============================================

data class OrderExecution(
    val signalId: String,
    val status: ExecutionStatus,
    val exchangeOrderId: String? = null,
    val executedPrice: Double? = null,
    val executedQuantity: Double? = null,
    val executedAt: Long = System.currentTimeMillis(),
    val errorMessage: String? = null
) {
    val formattedTime: String
        get() = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(executedAt),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("HH:mm:ss"))
}

enum class ExecutionStatus(val displayName: String, val color: Long) {
    SUCCESS("Success", 0xFF4ADE80),
    PARTIAL("Partial", 0xFFFBBF24),
    FAILED("Failed", 0xFFEF4444),
    SKIPPED("Skipped", 0xFF5B5B80)
}

// ============================================
// Connection Status
// ============================================

enum class ConnectionStatus(val displayName: String, val color: Long) {
    DISCONNECTED("Disconnected", 0xFF5B5B80),
    CONNECTING("Connecting...", 0xFFFBBF24),
    CONNECTED("Connected", 0xFF4ADE80),
    ERROR("Error", 0xFFEF4444),
    /** JWT expired — reconnecting will never help; user must re-login. */
    AUTH_EXPIRED("Session expired", 0xFFEF4444)
}

// ============================================
// API Profiles
// ============================================

@Serializable
data class ApiProfile(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val exchange: Exchange,
    val apiKey: String,
    val secretKey: String,
    val passphrase: String = "",
    val testnet: Boolean = true,
    val isDefault: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
)

// ============================================
// Logging
// ============================================

data class LogEntry(
    val timestamp: Long = System.currentTimeMillis(),
    val level: LogLevel,
    val message: String
) {
    val formattedTime: String
        get() = LocalDateTime.ofInstant(
            Instant.ofEpochMilli(timestamp),
            ZoneId.systemDefault()
        ).format(DateTimeFormatter.ofPattern("HH:mm:ss.SSS"))
}

enum class LogLevel(val color: Long) {
    INFO(0xFF60A5FA),
    SUCCESS(0xFF4ADE80),
    WARNING(0xFFFBBF24),
    ERROR(0xFFEF4444)
}
