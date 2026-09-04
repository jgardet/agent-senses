plugins {
    kotlin("jvm")
    java
    `java-test-fixtures`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")

    testFixturesCompileOnly("org.jetbrains.kotlin:kotlin-test-junit:2.3.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
}
