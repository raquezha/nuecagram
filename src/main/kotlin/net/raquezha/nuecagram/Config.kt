package net.raquezha.nuecagram

import java.net.URI
import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val name: String,
    val env: String,
    val host: String,
    val port: Int,
)

fun configuredPublicUrl(): String =
    normalizedPublicUrl(
        System.getProperty("nuecagram.publicUrl") ?: System.getenv("NUECAGRAM_PUBLIC_URL") ?: defaultPublicUrl(),
    )

fun configuredBasePath(): String =
    configuredPublicUrlPath()
        ?: legacyBasePath()

fun configuredRoute(path: String): String {
    require(path.startsWith('/')) { "Route path must start with '/'" }
    val basePath = configuredBasePath()
    return if (basePath.isEmpty()) path else "$basePath$path"
}

private fun configuredPublicUrlPath(): String? =
    publicUrlOverride()?.let { URI.create(normalizedPublicUrl(it)).path.normalizedBasePath() }

private fun publicUrlOverride(): String? =
    (System.getProperty("nuecagram.publicUrl") ?: System.getenv("NUECAGRAM_PUBLIC_URL"))
        ?.trim()
        ?.takeIf(String::isNotBlank)

private fun legacyBasePath(): String =
    (System.getProperty("nuecagram.basePath") ?: System.getenv("NUECAGRAM_BASE_PATH") ?: "/nuecagram")
        .trim()
        .normalizedBasePath()

private fun defaultPublicUrl(): String {
    val config = config("/application.json")
    val host = config.host.removeSuffix("/")
    return when {
        host.startsWith("http://") || host.startsWith("https://") -> "$host${legacyBasePath()}"
        host == "localhost" -> "http://$host:${config.port}${legacyBasePath()}"
        else -> "https://$host${legacyBasePath()}"
    }
}

private fun normalizedPublicUrl(raw: String): String {
    val uri = URI.create(raw.trim())
    require(uri.isAbsolute) { "NUECAGRAM_PUBLIC_URL must be absolute" }
    require(uri.scheme == "https" || uri.scheme == "http") { "NUECAGRAM_PUBLIC_URL must use http or https" }
    require(!uri.host.isNullOrBlank()) { "NUECAGRAM_PUBLIC_URL must include a host" }
    require(uri.rawQuery == null) { "NUECAGRAM_PUBLIC_URL must not include a query" }
    require(uri.rawFragment == null) { "NUECAGRAM_PUBLIC_URL must not include a fragment" }

    val path = uri.path.normalizedBasePath()
    return URI(
        uri.scheme,
        uri.userInfo,
        uri.host,
        uri.port,
        path.ifEmpty { "/" },
        null,
        null,
    ).toString().removeSuffix("/")
}

private fun String.normalizedBasePath(): String =
    trim()
        .ifBlank { "/" }
        .also { require(it.startsWith('/')) { "NUECAGRAM base path must start with '/'" } }
        .removeSuffix("/")
        .takeUnless { it.isEmpty() || it == "/" }
        .orEmpty()
