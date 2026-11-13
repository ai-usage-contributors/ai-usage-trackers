plugins {
    kotlin("jvm") version "2.4.0"
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "local.aiusagetrackers"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    implementation("org.xerial:sqlite-jdbc:3.54.1.0") {
        exclude(group = "org.slf4j", module = "slf4j-api")
    }
    intellijPlatform {
        intellijIdea("2026.2.3")
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

tasks.test {
    exclude("**/PerformanceBaselineTest.class")
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
    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.2.3")
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2026.2.4")
            providers.gradleProperty("verificationIdePath").orNull?.let { local(file(it)) }
        }
    }
    pluginConfiguration {
        ideaVersion {
            sinceBuild = "262"
            untilBuild = "263.*"
        }
    }
}
