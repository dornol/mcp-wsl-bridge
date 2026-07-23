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
            <![CDATA[
            <p>Exposes IntelliJ's loopback-only MCP server to WSL through selected Windows network interfaces.</p>
            <p>Choose one or more NIC addresses, then MCP WSL Bridge transparently relays each connection to the IDE's active MCP port.</p>
            ]]>
        """.trimIndent()
        changeNotes = """
            <![CDATA[
            <h2>0.1.0</h2>
            <ul><li>Initial WSL network-interface proxy.</li></ul>
            ]]>
        """.trimIndent()
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

