package net.raquezha.nuecagram.telegram

interface TokenProvider {
    fun getBotToken(): String

    fun getSecretToken(): String
}
