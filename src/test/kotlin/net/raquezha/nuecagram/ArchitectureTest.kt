package net.raquezha.nuecagram

import com.lemonappdev.konsist.api.Konsist
import com.lemonappdev.konsist.api.verify.assertFalse
import org.junit.Test

class ArchitectureTest {
    @Test
    fun `no mock fake or stub classes in production code`() {
        Konsist
            .scopeFromProduction()
            .classes()
            .assertFalse { clazz ->
                clazz.name.contains("Mock") ||
                    clazz.name.contains("Fake") ||
                    clazz.name.contains("Stub")
            }
    }

    @Test
    fun `exposed framework is only imported in db package`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertFalse { file ->
                file.packagee?.name != "net.raquezha.nuecagram.db" &&
                    file.imports.any { it.name.startsWith("org.jetbrains.exposed") }
            }
    }

    @Test
    fun `gitlab4j framework is only imported in webhook package`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertFalse { file ->
                file.packagee?.name != "net.raquezha.nuecagram.webhook" &&
                    file.imports.any { it.name.startsWith("org.gitlab4j") }
            }
    }

    @Test
    fun `ktor server is not imported in telegram package`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertFalse { file ->
                file.packagee?.name == "net.raquezha.nuecagram.telegram" &&
                    file.imports.any { it.name.startsWith("io.ktor.server") }
            }
    }

    @Test
    fun `koin dsl wiring is only used in di package`() {
        Konsist
            .scopeFromProduction()
            .files
            .assertFalse { file ->
                file.packagee?.name != "net.raquezha.nuecagram.di" &&
                    file.imports.any { it.name.startsWith("org.koin.dsl") }
            }
    }
}
