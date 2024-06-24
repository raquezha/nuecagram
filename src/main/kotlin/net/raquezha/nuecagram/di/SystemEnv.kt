package net.raquezha.nuecagram.di

interface SystemEnv {
    fun getBotApi(): String
    fun getSecretToken(): String
}
