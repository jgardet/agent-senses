plugins {
    id("com.android.library")
    kotlin("android")
}

android {
    namespace = "com.nyooran.agent.senses.android"
    compileSdk = 36

    defaultConfig {
        minSdk = 33
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
}

dependencies {
    api(project(":core"))
    api(project(":halo"))
    implementation("halo.engine:android")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
}
