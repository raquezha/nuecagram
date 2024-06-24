@file:OptIn(ExperimentalHoplite::class)

package net.raquezha.nuecagram

import com.sksamuel.hoplite.ConfigLoaderBuilder
import com.sksamuel.hoplite.ExperimentalHoplite
import com.sksamuel.hoplite.addResourceSource
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import kotlinx.coroutines.runBlocking
import net.raquezha.nuecagram.di.appModule
import net.raquezha.nuecagram.plugins.configureRouting
import net.raquezha.nuecagram.plugins.configureSerialization
import org.koin.ktor.plugin.Koin
import org.koin.logger.slf4jLogger

fun main(): Unit =
    runBlocking {
        val config = config("/application.json")
        embeddedServer(
            Netty,
            watchPaths = listOf("nuecagram"),
            port = config.port,
            module = Application::module,
        ).start(true)
    }

fun config(filename: String): net.raquezha.nuecagram.ConfigWithSecrets {
    val config = ConfigLoaderBuilder.default()
        .addResourceSource(filename)
        .withExplicitSealedTypes()
        .build()
        .loadConfigOrThrow<net.raquezha.nuecagram.Config>()

    val botApi = System.getenv("TELEGRAM_BOT_TOKEN")
        ?: throw IllegalStateException("TELEGRAM_BOT_TOKEN environment variable is not set")
    val secretToken = System.getenv("NUECAGRAM_SECRET_TOKEN")
        ?: throw IllegalStateException("NUECAGRAM_SECRET_TOKEN environment variable is not set")

    return net.raquezha.nuecagram.ConfigWithSecrets(
        name = config.name,
        env = config.env,
        host = config.host,
        port = config.port,
        botApi = botApi,
        secretToken = secretToken
    )
}

fun Application.module() {
    install(Koin) {
        slf4jLogger()
        modules(appModule())
    }
    configureSerialization()
    configureRouting()
}
