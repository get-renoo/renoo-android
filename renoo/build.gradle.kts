import com.vanniktech.maven.publish.AndroidSingleVariantLibrary
import com.vanniktech.maven.publish.JavadocJar
import com.vanniktech.maven.publish.SourcesJar
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.maven.publish)
}

// Single source of truth for the version: Renoo.SDK_VERSION.
val sdkVersion: String = Regex("""SDK_VERSION: String = "([^"]+)"""")
    .find(file("src/main/kotlin/dev/renoo/sdk/Renoo.kt").readText())
    ?.groupValues?.get(1)
    ?: error("Renoo.SDK_VERSION not found")

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

mavenPublishing {
    // Upload only. A deployment is published by hand in the Central Portal (versions can't be deleted).
    publishToMavenCentral(automaticRelease = false)

    // Credentials come from ORG_GRADLE_PROJECT_signingInMemoryKey* in CI. Without a key
    // (e.g. publishToMavenLocal on a laptop) artifacts are left unsigned.
    if (providers.gradleProperty("signingInMemoryKey").isPresent) {
        signAllPublications()
    }

    configure(
        AndroidSingleVariantLibrary(
            variant = "release",
            sourcesJar = SourcesJar.Sources(),
            javadocJar = JavadocJar.Empty(),
        ),
    )

    coordinates("dev.renoo", "renoo-android", sdkVersion)

    pom {
        name.set("Renoo Android SDK")
        description.set("Captures in-app behaviour for subscription apps and surfaces retention offers. Zero runtime permissions.")
        inceptionYear.set("2026")
        url.set("https://github.com/get-renoo/renoo-android")
        licenses {
            license {
                name.set("The Apache License, Version 2.0")
                url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                distribution.set("repo")
            }
        }
        developers {
            developer {
                id.set("get-renoo")
                name.set("Renoo")
                email.set("dev@renoo.dev")
                url.set("https://renoo.dev")
            }
        }
        scm {
            url.set("https://github.com/get-renoo/renoo-android")
            connection.set("scm:git:https://github.com/get-renoo/renoo-android.git")
            developerConnection.set("scm:git:ssh://git@github.com/get-renoo/renoo-android.git")
        }
    }
}
