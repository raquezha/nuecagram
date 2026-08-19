package net.raquezha.nuecagram.telegram

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

private const val MAX_AUTH_AGE_SECONDS = 86400L // 24 hours

@Serializable
data class TelegramWebAppUser(
    val id: Long,
    @SerialName("first_name")
    val firstName: String? = null,
    @SerialName("last_name")
    val lastName: String? = null,
    val username: String? = null,
)

data class VerifiedTelegramWebAppData(
    val user: TelegramWebAppUser,
    val authDate: Long,
    val queryId: String?,
    val startParam: String?,
)

object TelegramWebAppAuth {
    private val json = Json { ignoreUnknownKeys = true }

    @Suppress("ReturnCount")
    fun verifyInitData(
        initData: String,
        botToken: String,
        maxAgeSeconds: Long = MAX_AUTH_AGE_SECONDS,
    ): VerifiedTelegramWebAppData? {

        if (initData.isBlank() || botToken.isBlank()) return null

        val pairs = parseQueryString(initData)
        val hash = pairs["hash"] ?: return null
        if (!verifyHmac(pairs - "hash", hash, botToken)) return null

        val authDate = pairs["auth_date"]?.toLongOrNull() ?: return null
        if (!verifyAuthDate(authDate, maxAgeSeconds)) return null

        val userJson = pairs["user"] ?: return null
        val user = runCatching { json.decodeFromString<TelegramWebAppUser>(userJson) }.getOrNull() ?: return null

        return VerifiedTelegramWebAppData(
            user = user,
            authDate = authDate,
            queryId = pairs["query_id"],
            startParam = pairs["start_param"],
        )
    }

    private fun verifyHmac(
        remainingPairs: Map<String, String>,
        receivedHash: String,
        botToken: String,
    ): Boolean {
        val dataCheckString = remainingPairs.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

        val webAppDataBytes = "WebAppData".toByteArray(StandardCharsets.UTF_8)
        val secretKey = hmacSha256(webAppDataBytes, botToken.toByteArray(StandardCharsets.UTF_8))
        val calculatedHash = bytesToHex(hmacSha256(secretKey, dataCheckString.toByteArray(StandardCharsets.UTF_8)))

        return constantTimeEquals(receivedHash.lowercase(), calculatedHash.lowercase())
    }

    private fun verifyAuthDate(authDate: Long, maxAgeSeconds: Long): Boolean {
        val now = System.currentTimeMillis() / 1000
        return authDate <= now && (now - authDate) <= maxAgeSeconds
    }

    private fun parseQueryString(query: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (part in query.split("&")) {
            val idx = part.indexOf('=')
            if (idx > 0) {
                val key = URLDecoder.decode(part.substring(0, idx), "UTF-8")
                val value = URLDecoder.decode(part.substring(idx + 1), "UTF-8")
                result[key] = value
            }
        }
        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    private fun constantTimeEquals(a: String, b: String): Boolean =
        MessageDigest.isEqual(a.toByteArray(StandardCharsets.UTF_8), b.toByteArray(StandardCharsets.UTF_8))
}
