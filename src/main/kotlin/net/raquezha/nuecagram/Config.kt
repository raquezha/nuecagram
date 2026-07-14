package net.raquezha.nuecagram

import kotlinx.serialization.Serializable

@Serializable
data class Config(
    val name: String,
    val env: String,
    val host: String,
    val port: Int,
)
