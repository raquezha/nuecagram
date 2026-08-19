package net.raquezha.nuecagram.testing

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

object TelegramWebAppTestUtils {
    private fun hmacSha256(key: ByteArray, data: String): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data.toByteArray(StandardCharsets.UTF_8))
    }

    private fun bytesToHex(bytes: ByteArray): String =
        bytes.joinToString("") { "%02x".format(it) }

    fun buildTestInitData(
        botToken: String,
        authDate: Long = System.currentTimeMillis() / 1000,
        userId: Long = 12345L,
        extraParams: Map<String, String> = emptyMap(),
    ): String {
        val secretKey = hmacSha256("WebAppData".toByteArray(StandardCharsets.UTF_8), botToken)
        val baseParams = mutableMapOf(
            "auth_date" to authDate.toString(),
            "query_id" to "AAH12345",
            "user" to """{"id":$userId,"first_name":"Test","last_name":"User"}""",
        )
        baseParams.putAll(extraParams)

        val dataCheckString = baseParams.entries
            .sortedBy { it.key }
            .joinToString("\n") { "${it.key}=${it.value}" }

        val hash = bytesToHex(hmacSha256(secretKey, dataCheckString))
        val queryParams = baseParams + ("hash" to hash)
        return queryParams.entries.joinToString("&") { (k, v) ->
            "${URLEncoder.encode(k, "UTF-8")}=${URLEncoder.encode(v, "UTF-8")}"
        }
    }
}
