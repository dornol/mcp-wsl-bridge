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
    testImplementation("org.junit.jupiter:junit-jupiter:5.12.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation(kotlin("test"))
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
        <p>Choose one or more NIC addresses, then MCP WSL Bridge transparently routes multiple HTTP MCP servers through one WSL-facing port using separate paths.</p>
        <h2>Usage</h2>
        <ol>
          <li>Enable IntelliJ's built-in MCP server in <b>Settings | Tools | MCP Server</b>.</li>
          <li>Open <b>Settings | Tools | MCP WSL Bridge</b>, enable the bridge, and select the <code>vEthernet (WSL)</code> IPv4 address.</li>
          <li>Apply the settings. The bridge starts automatically with IntelliJ while enabled.</li>
          <li>Add one route per MCP server, then use the Codex, Claude Code, or GitHub Copilot CLI action to configure all enabled routes.</li>
        </ol>
        <p>The bridge automatically rebinds after WSL interface address changes and updates configured WSL client endpoints.</p>
        <p><b>Security:</b> select only network interfaces you intend to expose. The bridge has no authentication and should not be bound to Wi-Fi, Ethernet, or VPN addresses unless required.</p>
        """.trimIndent()
        changeNotes = """
            <h2>0.1.10</h2>
            <ul>
              <li>Preserve loopback Origin and Streamable HTTP response framing for IntelliJ MCP approval flows.</li>
              <li>Preserve MCP session and SSE approval exchanges through the WSL bridge.</li>
              <li>Add safe diagnostics for MCP request, response, SSE, and timing events.</li>
              <li>Add regression coverage for server-initiated approval requests and responses.</li>
              <li>Recycle the bridge endpoint when IntelliJ MCP restarts so clients can establish fresh sessions.</li>
              <li>Virtualize MCP sessions and recreate IntelliJ upstream sessions after restarts or connection failures.</li>
              <li>Keep bridge listeners alive during transient IntelliJ MCP target loss.</li>
              <li>Claude Code may require <code>/mcp reconnect</code> after an IntelliJ restart if its elicitation state is not recovered.</li>
            </ul>
            <h2>0.1.9</h2>
            <ul>
              <li>Add multi-server MCP routing, including the IDE Index MCP preset.</li>
              <li>Add WSL client configuration and removal actions for Codex, Claude Code, and GitHub Copilot CLI.</li>
              <li>Redesign MCP server settings with a native list, detail panel, templates, and popup editing.</li>
              <li>Improve bridge status reporting, automatic WSL endpoint refresh, and HTTP routing compatibility.</li>
            </ul>
            <h2>0.1.8</h2>
            <ul>
              <li>Start the bridge automatically when an IntelliJ project opens.</li>
              <li>Add an adaptive status-bar widget for bridge state and endpoint actions.</li>
              <li>Remove deprecated and internal IntelliJ API usages reported by Plugin Verifier.</li>
            </ul>
            <h2>0.1.7</h2>
            <ul>
              <li>Use the public application-frame lifecycle callback for automatic startup.</li>
            </ul>
            <h2>0.1.6</h2>
            <ul>
              <li>Replace deprecated and internal IntelliJ startup, settings-path, and clipboard APIs.</li>
            </ul>
            <h2>0.1.5</h2>
            <ul>
              <li>Start the bridge after IntelliJ initialization using the supported application listener.</li>
            </ul>
            <h2>0.1.4</h2>
            <ul>
              <li>Start the bridge automatically when enabled in IntelliJ.</li>
              <li>Rebind listeners after selected WSL interface addresses change.</li>
              <li>Refresh Codex, Claude Code, and GitHub Copilot CLI across configured WSL distributions.</li>
              <li>Add unit and socket integration tests for bridge startup, relay, and failure paths.</li>
            </ul>
            <h2>0.1.3</h2>
            <ul>
              <li>Prevent interactive shell startup scripts from blocking WSL client configuration.</li>
              <li>Show progress and explicit failure or timeout feedback when applying client settings.</li>
              <li>Move WSL discovery and bridge restart work off IntelliJ's UI thread.</li>
            </ul>
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
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }

    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    wrapper {
        gradleVersion = "9.1.0"
        distributionType = Wrapper.DistributionType.BIN
    }
}
