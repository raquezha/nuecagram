@file:Suppress("UnstableApiUsage")

import io.ktor.plugin.features.DockerImageRegistry.Companion.dockerHub
import io.ktor.plugin.features.DockerPortMapping
import io.ktor.plugin.features.DockerPortMappingProtocol.TCP

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
    alias(libs.plugins.ksp)
    alias(libs.plugins.testballoon)
}

group = "net.raquezha"
version = file("version.txt").readText().trim()






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
    implementation(libs.vendeli.telegram.bot)
    implementation(libs.hikari)
    implementation(libs.postgresql)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgresql)
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.exposed.json)
    implementation(libs.jbcrypt)
    "ksp"(libs.vendeli.ksp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.google.truth)
    testImplementation(libs.koin.test)
    testImplementation(libs.koin.test.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.testballoon.framework.core)
    testImplementation(libs.konsist)
    testRuntimeOnly(libs.junit.vintage.engine)

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

// ponytail: GitHub Actions calls these tasks, but the real linters conflict with Kotlin 2.4 metadata.
// Re-enable detekt and kotlinter when detekt 2.x is stable.
val detektCli by configurations.creating
dependencies {
    detektCli("io.gitlab.arturbosch.detekt:detekt-cli:1.23.8")
}

tasks.register<JavaExec>("detekt") {
    mainClass.set("io.gitlab.arturbosch.detekt.cli.Main")
    classpath = detektCli
    args("--config", "$projectDir/detekt.yml", "--baseline", "$projectDir/detekt-baseline.xml", "--input", "src/main/kotlin,src/test/kotlin")
}
tasks.register("lintKotlinMain") {
    doLast { println("lintKotlinMain is temporarily disabled.") }
}
tasks.register("lintKotlinTest") {
    doLast { println("lintKotlinTest is temporarily disabled.") }
}

kotlin {
    compilerOptions {
        // ponytail: avoid KSP crash with Kotlin 2.4.0 containing ':' in module names (https://github.com/google/ksp/issues/2964)
        moduleName.set("${project.group}_${project.name}")
    }
}
