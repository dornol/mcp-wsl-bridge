import org.jetbrains.intellij.platform.gradle.TestFrameworkType
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.2.21"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

group = providers.gradleProperty("pluginGroup").get()
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea(providers.gradleProperty("platformVersion").get())
        testFramework(TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        name = providers.gradleProperty("pluginName")
        version = providers.gradleProperty("pluginVersion")
        ideaVersion {
            sinceBuild = providers.gradleProperty("platformSinceBuild")
        }
        vendor {
            name = "dornol"
            url = "https://github.com/dornol"
        }
        description = """
            <p>Exposes IntelliJ's loopback-only MCP server to WSL through selected Windows network interfaces.</p>
            <p>Choose one or more NIC addresses, then MCP WSL Bridge transparently relays each connection to the IDE's active MCP port.</p>
        """.trimIndent()
        changeNotes = """
            <h2>0.1.2</h2>
            <ul>
              <li>Use a Windows HTTP reverse proxy as the primary WSL client endpoint.</li>
              <li>Remove the WSL-local Node proxy requirement for Claude Code and Codex.</li>
              <li>Recover selected WSL NIC addresses after network changes.</li>
            </ul>
            <h2>0.1.1</h2>
            <ul>
              <li>Start a WSL loopback relay automatically when configuring Codex or Claude Code.</li>
              <li>Fix Claude Code HTTP 403 errors caused by IntelliJ MCP loopback validation.</li>
              <li>Run WSL client commands through the user's login shell.</li>
            </ul>
            <h2>0.1.0</h2>
            <ul><li>Initial WSL network-interface proxy.</li></ul>
        """.trimIndent()
    }
    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    wrapper {
        gradleVersion = "9.1.0"
        distributionType = Wrapper.DistributionType.BIN
    }
}
