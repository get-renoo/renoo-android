import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "dev.renoo.sdk"
    compileSdk = 36

    defaultConfig {
        minSdk = 24
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        // Robolectric tests (the facade smoke test) need the merged manifest and resources.
        unitTests.isIncludeAndroidResources = true
    }
}

kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // The only runtime dependency allowed besides the Kotlin stdlib (see CLAUDE.md).
    implementation(libs.billing)

    testImplementation(libs.junit)
    // The JVM test classpath only has throwing stubs for org.json; this is the real implementation.
    testImplementation(libs.json)
    testImplementation(libs.robolectric)
}
