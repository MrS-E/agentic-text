plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

group = "dev.agentictext"
version = "0.1.0"

kotlin {
    jvmToolchain(21)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testImplementation(libs.mockk)
    testImplementation(libs.kotlinx.coroutines.test)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
}

// Runs the end-to-end pipeline example against an OpenAI-compatible endpoint configured through
// OPENAI_MODEL (required), OPENAI_BASE_URL, and OPENAI_API_KEY (see the example's KDoc).
tasks.register<JavaExec>("runOpenAiCompatibleExample") {
    group = "application"
    description = "Runs the WritingPipeline end to end against an OpenAI-compatible chat completions endpoint."
    mainClass.set("dev.agentictext.examples.OpenAiCompatiblePipelineExampleKt")
    classpath = sourceSets["main"].runtimeClasspath
}