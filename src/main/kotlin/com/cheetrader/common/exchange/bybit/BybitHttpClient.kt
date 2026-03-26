package com.cheetrader.common.exchange.bybit

import com.cheetrader.common.logging.ExchangeLogger
import com.cheetrader.common.logging.NoOpLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class BybitHttpClient(
    private val apiKey: String,
    private val secretKey: String,
    private val baseUrl: String,
    private val logger: ExchangeLogger = NoOpLogger
) {
    private val userAgent = "TradingClient/1.0"
    private val recvWindow = "5000"
    private val json = Json { ignoreUnknownKeys = true }

    @Volatile
    private var timeOffset: Long = 0

    suspend fun syncTime() {
        try {
            val response = executePublicGet(BybitConstants.Endpoints.SERVER_TIME) ?: return

            val obj = json.parseToJsonElement(response).jsonObject
            val result = obj["result"]?.jsonObject
            val timeSecond = result?.get("timeSecond")?.jsonPrimitive?.content?.toLongOrNull()
            val timeNano = result?.get("timeNano")?.jsonPrimitive?.content?.toLongOrNull()

            val serverTimeMs = when {
                timeNano != null -> timeNano / 1_000_000
                timeSecond != null -> timeSecond * 1000
                else -> return
            }

            val localTime = System.currentTimeMillis()
            timeOffset = serverTimeMs - localTime
            logger.debug { "Bybit time synced. Offset: ${timeOffset}ms" }
        } catch (e: Exception) {
            logger.warn { "Bybit time sync failed: ${e.message}" }
        }
    }

    private fun getTimestamp(): String {
        return (System.currentTimeMillis() + timeOffset).toString()
    }

    private fun sign(message: String): String {
        return try {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(secretKey.toByteArray(), "HmacSHA256"))
            mac.doFinal(message.toByteArray())
                .joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            logger.error(e) { "Bybit HMAC generation error" }
            ""
        }
    }

    private fun buildQueryString(params: Map<String, String>): String {
        return params.toSortedMap().entries.joinToString("&") {
            "${it.key}=${URLEncoder.encode(it.value, "UTF-8")}"
        }
    }

    suspend fun executePublicGet(path: String, params: Map<String, String> = emptyMap()): String? {
        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val queryString = if (params.isNotEmpty()) buildQueryString(params) else ""
                val fullUrl = if (queryString.isNotEmpty()) {
                    "$baseUrl$path?$queryString"
                } else {
                    "$baseUrl$path"
                }

                val url = URL(fullUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("User-Agent", userAgent)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "Bybit GET $path failed: ${conn.responseCode} - $error" }
                    null
                }
            } catch (e: Exception) {
                logger.error(e) { "Bybit GET $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    suspend fun executeSignedGet(path: String, params: Map<String, String>): String? {
        val timestamp = getTimestamp()
        val queryString = buildQueryString(params)
        val signature = sign(timestamp + apiKey + recvWindow + queryString)

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val fullUrl = "$baseUrl$path?$queryString"
                val url = URL(fullUrl)
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "GET"
                conn.setRequestProperty("X-BAPI-API-KEY", apiKey)
                conn.setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
                conn.setRequestProperty("X-BAPI-SIGN", signature)
                conn.setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
                conn.setRequestProperty("X-BAPI-SIGN-TYPE", "2")
                conn.setRequestProperty("User-Agent", userAgent)
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "Bybit signed GET $path failed: ${conn.responseCode} - $error" }
                    error
                }
            } catch (e: Exception) {
                logger.error(e) { "Bybit signed GET $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }

    suspend fun executeSignedPost(path: String, jsonBody: String): String? {
        val timestamp = getTimestamp()
        val signature = sign(timestamp + apiKey + recvWindow + jsonBody)

        return withContext(Dispatchers.IO) {
            var conn: HttpURLConnection? = null
            try {
                val url = URL("$baseUrl$path")
                conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("X-BAPI-API-KEY", apiKey)
                conn.setRequestProperty("X-BAPI-TIMESTAMP", timestamp)
                conn.setRequestProperty("X-BAPI-SIGN", signature)
                conn.setRequestProperty("X-BAPI-RECV-WINDOW", recvWindow)
                conn.setRequestProperty("X-BAPI-SIGN-TYPE", "2")
                conn.setRequestProperty("User-Agent", userAgent)
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.connectTimeout = 10000
                conn.readTimeout = 10000

                conn.outputStream.use { os ->
                    os.write(jsonBody.toByteArray(Charsets.UTF_8))
                    os.flush()
                }

                if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                    conn.inputStream.bufferedReader().use { it.readText() }
                } else {
                    val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                    logger.error { "Bybit POST $path failed: ${conn.responseCode} - $error" }
                    error
                }
            } catch (e: Exception) {
                logger.error(e) { "Bybit POST $path error" }
                null
            } finally {
                conn?.disconnect()
            }
        }
    }
}
