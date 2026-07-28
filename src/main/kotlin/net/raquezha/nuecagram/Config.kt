package net.raquezha.nuecagram

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val name: String,
    val env: String,
    val host: String,
    val port: Int,
)

fun configuredBasePath(): String =
    (System.getProperty("nuecagram.basePath") ?: System.getenv("NUECAGRAM_BASE_PATH") ?: "/nuecagram")
        .trim()
        .removeSuffix("/")
        .ifBlank { "/nuecagram" }
        .also {
            require(it.startsWith('/')) { "NUECAGRAM_BASE_PATH must start with '/'" }
        }
