import io.ktor.plugin.features.DockerImageRegistry.Companion.dockerHub
import io.ktor.plugin.features.DockerPortMapping
import io.ktor.plugin.features.DockerPortMappingProtocol.TCP

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinter)
}

group = "net.raquezha"
version = "0.0.3"

ktor {
    fatJar {
        archiveFileName.set("fat.jar")
    }
    docker {
        jreVersion.set(JavaVersion.VERSION_17)
        localImageName.set("nuecagram-docker-image")
        imageTag.set(version.toString())
        portMappings.set(listOf(
            DockerPortMapping(
                80,
                8080,
                TCP
            )
        ))
        externalRegistry.set(
            dockerHub(
                appName = provider { "nuecagram" },
                username = providers.environmentVariable("DOCKER_HUB_USERNAME"),
                password = providers.environmentVariable("DOCKER_HUB_PASSWORD")
            )
        )
    }
}
application {
    mainClass.set("io.ktor.server.netty.EngineMain")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}
repositories {
    google()
    mavenCentral()
    maven {
        setUrl("https://jitpack.io")
        content {
            includeGroup("com.github.gitlab4j")
        }
    }
}
kotlinter {
    ignoreFailures = false
    reporters = arrayOf("checkstyle", "plain")

}
dependencies {
    implementation(libs.coroutines)
    implementation(libs.logback.classic)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.servlet)
    implementation(libs.ktor.server.config.yaml)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.test.host)
    implementation(libs.ktor.serialization.json)
    implementation(libs.ktor.serialization.gson)
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.client.json)
    implementation(libs.logback.classic)
    implementation(libs.gitlab4j.api)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)
    implementation(libs.koin.core)
    implementation(libs.koin.annotation)
    implementation(libs.kotlin.logging)
    implementation(libs.hoplite)
    implementation(libs.hoplite.json)

    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.google.truth)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.mockk)
}


