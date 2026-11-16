import org.jetbrains.intellij.platform.gradle.tasks.RunIdeTask

plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.aiusagetrackers"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://cache-redirector.jetbrains.com/repo1.maven.org/maven2") {
        name = "JetBrainsSigningToolMirror"
        content { includeModule("org.jetbrains", "marketplace-zip-signer") }
    }
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.54.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    intellijPlatform {
        val developmentIdePath = providers.gradleProperty("runIdePath").orNull
        if (developmentIdePath != null) local(file(developmentIdePath)) else intellijIdea("2026.3")
        pluginVerifier("1.411")
        zipSigner("0.1.45")
    }
    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(25)
    compilerOptions {
        jvmDefault.set(org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode.NO_COMPATIBILITY)
    }
}

tasks.processResources {
    from("LICENSE")
    from("LICENSE")
    from("THIRD_PARTY_NOTICES.txt")
    from("tools/quota-capture") { into("quota-capture"); exclude("test_*.py", "__pycache__/**") }
}

tasks.named("verifyPlugin") {
    mustRunAfter(tasks.test)
}

tasks.named("verifyPluginSignature") {
    mustRunAfter("signPlugin")
}

tasks.named("verifyPluginSignature") {
    mustRunAfter("signPlugin")
}

tasks.test {
    exclude("**/PerformanceBaselineTest.class")
}

val runIde20263 by intellijPlatformTesting.runIde.registering {
    version = "2026.3"
    localPath = providers.gradleProperty("runIdePath").map { layout.projectDirectory.dir(it) }
    sandboxDirectory = layout.projectDirectory.dir(".intellijPlatform/sandbox/ai-usage-trackers/IU-2026.3")
    task {
        argumentProviders.add(CommandLineArgumentProvider {
            tasks.named<RunIdeTask>("runIde").get().args
        })
    }
}

tasks.named<RunIdeTask>("runIde") {
    // Preserve the standard command and --args option while using a separate runtime SDK.
    enabled = false
    setDependsOn(listOf(runIde20263))
}

tasks.register<Test>("performanceBaseline") {
    description = "Measures synthetic usage workloads; does not use personal logs."
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    include("**/PerformanceBaselineTest.class")
    maxHeapSize = "2g"
    maxParallelForks = 1
    outputs.upToDateWhen { false }
    testLogging.showStandardStreams = true
}

intellijPlatform {
    signing {
        privateKey.set(providers.environmentVariable("PRIVATE_KEY"))
        certificateChain.set(providers.environmentVariable("CERTIFICATE_CHAIN"))
        password.set(providers.environmentVariable("PRIVATE_KEY_PASSWORD"))
    }
    pluginVerification {
        ides {
            val verificationIdePath = providers.gradleProperty("verificationIdePath").orNull
            if (verificationIdePath != null) local(file(verificationIdePath)) else
                create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.3")
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "263"
            untilBuild = "263.*"
        }
    }
}
