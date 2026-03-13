package com.cheetrader.common.exchange.bingx

import com.cheetrader.common.logging.ExchangeLogger
import com.cheetrader.common.logging.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * HTTP client for BingX API
 */
class BingXHttpClient(
    private val apiKey: String,
    private val secretKey: String,
    private val baseUrl: String,
    private val logger: ExchangeLogger = NoOpLogger
) {
    private val userAgent = "TradingClient/1.0"

    @Volatile
    private var timeOffset: Long = 0

    /**
     * Sync time with server
     */
    suspend fun syncTime() {
        try {
            val response = executeGet(BingXConstants.Endpoints.SERVER_TIME)
            if (response != null) {
                val regex = """"serverTime"\s*:\s*(\d+)""".toRegex()
                val match = regex.find(response)
                if (match != null) {
                    val serverTime = match.groupValues[1].toLong()
                    val localTime = System.currentTimeMillis()
                    timeOffset = serverTime - localTime
                    logger.debug { "BingX time synced. Offset: ${timeOffset}ms" }
                }
            }
        } catch (e: Exception) {
            logger.warn { "BingX time sync failed: ${e.message}" }
        }
    }

    /**
     * Get adjusted timestamp
     */
    fun getTimestamp(): String {
        return (System.currentTimeMillis() + timeOffset).toString()
    }

    /**
     * Generate HMAC-SHA256 signature (uppercase)
     */
    fun sign(message: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA256"))
            mac.doFinal(message.toByteArray())
                .joinToString("") { "%02X".format(it) }
        } catch (e: Exception) {
            logger.error(e) { "BingX HMAC generation error" }
            ""
        }
    }

    /**
     * GET request
     */
    suspend fun executeGet(path: String, params: Map<String, String> = emptyMap()): String? {
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val queryString = if (params.isNotEmpty()) {
                    params.entries.joinToString("&") { "${it.key}=${it.value}" }
                } else ""

                val fullUrl = if (queryString.isNotEmpty()) {
                    "$baseUrl$path?$queryString"
                } else {
                    "$baseUrl$path"
                }

                val url = URL(fullUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-BX-APIKEY", apiKey)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "BingX GET $path failed: ${conn.responseCode} - $error" }
                    null
                }
            } catch (e: Exception) {
                logger.error(e) { "BingX GET $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Signed GET request
     */
    suspend fun executeSignedGet(path: String, params: MutableMap<String, String> = mutableMapOf()): String? {
        params["timestamp"] = getTimestamp()
        val queryString = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = sign(queryString)

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val fullUrl = "$baseUrl$path?$queryString&signature=$signature"
                val url = URL(fullUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-BX-APIKEY", apiKey)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "BingX signed GET $path failed: ${conn.responseCode} - $error" }
                    error
                }
            } catch (e: Exception) {
                logger.error(e) { "BingX signed GET $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Signed POST request
     */
    suspend fun executeSignedPost(path: String, params: MutableMap<String, String>): String? {
        params["timestamp"] = getTimestamp()
        val queryString = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = sign(queryString)
        val body = "$queryString&signature=$signature"

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$path")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-BX-APIKEY", apiKey)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
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
                    logger.error { "BingX POST $path failed: ${conn.responseCode} - $error" }
                    error
                }
            } catch (e: Exception) {
                logger.error(e) { "BingX POST $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    /**
     * Signed DELETE request
     */
    suspend fun executeSignedDelete(path: String, params: MutableMap<String, String>): String? {
        params["timestamp"] = getTimestamp()
        val queryString = params.toSortedMap().entries.joinToString("&") { "${it.key}=${it.value}" }
        val signature = sign(queryString)
        val body = "$queryString&signature=$signature"

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$path")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "DELETE"
                conn.setRequestProperty("X-BX-APIKEY", apiKey)
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
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
                    logger.error { "BingX DELETE $path failed: ${conn.responseCode} - $error" }
                    error
                }
            } catch (e: Exception) {
                logger.error(e) { "BingX DELETE $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
