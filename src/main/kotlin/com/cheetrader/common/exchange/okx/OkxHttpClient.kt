package com.cheetrader.common.exchange.okx

import com.cheetrader.common.logging.ExchangeLogger
import com.cheetrader.common.logging.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Base64
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class OkxHttpClient(
    private val apiKey: String,
    private val secretKey: String,
    private val passphrase: String,
    private val baseUrl: String,
    private val testnet: Boolean,
    private val logger: ExchangeLogger = NoOpLogger
) {
    private val userAgent = "TradingClient/1.0"
    private val timestampFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC)

    private fun buildQueryString(params: Map<String, String>): String {
        return params.toSortedMap().entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    private fun sign(timestamp: String, method: String, requestPath: String, body: String): String {
        val prehash = timestamp + method + requestPath + body
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA256"))
            Base64.getEncoder().encodeToString(mac.doFinal(prehash.toByteArray()))
        } catch (e: Exception) {
            logger.error(e) { "OKX HMAC generation error" }
            ""
        }
    }

    private fun buildTimestamp(): String {
        return timestampFormatter.format(Instant.now())
    }

    suspend fun executeSignedGet(path: String, params: Map<String, String> = emptyMap()): String? {
        val timestamp = buildTimestamp()
        val queryString = if (params.isNotEmpty()) buildQueryString(params) else ""
        val requestPath = if (queryString.isNotEmpty()) "$path?$queryString" else path
        val signature = sign(timestamp, "GET", requestPath, "")

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$requestPath")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("OK-ACCESS-KEY", apiKey)
                conn.setRequestProperty("OK-ACCESS-SIGN", signature)
                conn.setRequestProperty("OK-ACCESS-TIMESTAMP", timestamp)
                conn.setRequestProperty("OK-ACCESS-PASSPHRASE", passphrase)
                conn.setRequestProperty("User-Agent", userAgent)
                if (testnet) {
                    conn.setRequestProperty("x-simulated-trading", "1")
                }
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "OKX signed GET $path failed: ${conn.responseCode} - $error" }
                    null
                }
            } catch (e: Exception) {
                logger.error(e) { "OKX signed GET $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    suspend fun executePublicGet(path: String, params: Map<String, String> = emptyMap()): String? {
        val queryString = if (params.isNotEmpty()) buildQueryString(params) else ""
        val requestPath = if (queryString.isNotEmpty()) "$path?$queryString" else path

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$requestPath")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", userAgent)
                if (testnet) {
                    conn.setRequestProperty("x-simulated-trading", "1")
                }
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "OKX public GET $path failed: ${conn.responseCode} - $error" }
                    null
                }
            } catch (e: Exception) {
                logger.error(e) { "OKX public GET $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    suspend fun executeSignedPost(path: String, body: String): String? {
        val timestamp = buildTimestamp()
        val signature = sign(timestamp, "POST", path, body)

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$path")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("OK-ACCESS-KEY", apiKey)
                conn.setRequestProperty("OK-ACCESS-SIGN", signature)
                conn.setRequestProperty("OK-ACCESS-TIMESTAMP", timestamp)
                conn.setRequestProperty("OK-ACCESS-PASSPHRASE", passphrase)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.setRequestProperty("User-Agent", userAgent)
                if (testnet) {
                    conn.setRequestProperty("x-simulated-trading", "1")
                }
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                conn.outputStream.use { os ->
                    os.write(body.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "OKX POST $path failed: ${conn.responseCode} - $error" }
                    null
                }
            } catch (e: Exception) {
                logger.error(e) { "OKX POST $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
