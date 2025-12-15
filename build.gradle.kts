@file:Suppress("UnstableApiUsage")

import io.ktor.plugin.features.DockerImageRegistry.Companion.dockerHub
import io.ktor.plugin.features.DockerPortMapping
import io.ktor.plugin.features.DockerPortMappingProtocol.TCP

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.kotlinter)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ksp)
}

group = "net.raquezha"
version = file("version.txt").readText().trim()


tasks.formatKotlinMain {
    exclude { it.file.path.contains("generated/")}
}

tasks.lintKotlinMain {
    exclude { it.file.path.contains("generated/")}
}

tasks.lintKotlinTest {
    exclude { it.file.path.contains("generated/") }
}

tasks.formatKotlinTest {
    exclude { it.file.path.contains("generated/") }
}

ktor {
    fatJar {
        archiveFileName.set("nuecagram-fat.jar")
    }
    jib {
        outputPaths {
            tar = "${rootDir}/build/jib/nuecagram-jib-image.tar"
            digest = "${rootDir}/build/jib/nuecagram-jib-image.digest"
            imageId = "${rootDir}/build/jib/nuecagram-jib-image.id"
            imageJson = "${rootDir}/build/jib/nuecagram-jib-image.json"
        }
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

detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom("$projectDir/detekt.yml")
    baseline = file("$projectDir/detekt-baseline.xml")
    source.setFrom(
        "src/main/kotlin",
        "src/test/kotlin"
    )
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    exclude { it.file.path.contains("generated/") }
    reports {
        html.required.set(true)
        xml.required.set(true)
        sarif.required.set(false)
    }
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
    implementation(libs.ktor.serialization.jackson)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.logging)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.gitlab4j.api)
    implementation(libs.koin.ktor)
    implementation(libs.koin.logger)
    implementation(libs.koin.core)
    implementation(libs.koin.annotation)
    implementation(libs.kotlin.logging)
    implementation(libs.hoplite)
    implementation(libs.hoplite.json)
    implementation(libs.vendeli.telegram.bot)
    "ksp"(libs.vendeli.ksp)

    testImplementation(libs.ktor.server.tests)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.google.truth)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.mockk)

}


tasks.register<Copy>("installHooks") {
    val gitHookPreCommit = File(rootProject.rootDir, ".git/hooks/pre-commit")
    val gitHookPrePush = File(rootProject.rootDir, ".git/hooks/pre-push")
    if (!(gitHookPreCommit.exists() && gitHookPrePush.exists())) {
        logger.info("Installing Git hooks...")
        from(File(rootProject.rootDir, ".githooks/pre-commit"), File(rootProject.rootDir, ".githooks/pre-push"))
        into { File(rootProject.rootDir, ".git/hooks") }
        filePermissions {
            user {
                read = true
                execute = true
            }
            other.execute = false
            dirPermissions {
                // Kotlin doesn't support local numeric literals: instead of mode = 0755
                // need to write as mode = "755.toInt(radix = 8)
                // rwxr-xr-x means (0755) read, write and execute for owner
                // read and execute for group
                // read and execute for other
                unix("rwxr-xr-x")
            }
            println(
                """Checking the following settings helps avoid miscellaneous issues:
          * Settings -> Editor -> General -> Remove trailing spaces on: Modified lines
          * Settings -> Editor -> General -> Ensure every file ends with a line break
          * Settings -> Editor -> General -> Auto Import -> Optimize imports on the fly (for both Kotlin and Java)"""
            )
        }
    }
}

tasks.named("prepareKotlinBuildScriptModel") {
    dependsOn("installHooks")
}
