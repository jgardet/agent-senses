plugins {
    kotlin("jvm")
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
    api(project(":core"))
    implementation("halo.engine:kotlin")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testFixturesApi(project(":core"))
    testFixturesImplementation("halo.engine:kotlin")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    testFixturesImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.11.0")
    testImplementation(project(":orchestration"))
    testImplementation(testFixtures(project(":core")))
}
