plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    `maven-publish`
}

android {
    namespace   = "dev.hush.sync"
    compileSdk  = 34

    defaultConfig {
        minSdk = 24
        // Only ship the ABIs we cross-compile in build-android.sh
        ndk {
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }

    // Generated UniFFI bindings live in src/main/java (package uniffi.*)
    sourceSets {
        getByName("main") {
            java.srcDirs("src/main/java")
            kotlin.srcDirs("src/main/kotlin")
            jniLibs.srcDirs("src/main/jniLibs")
        }
    }

    publishing {
        singleVariant("release") {
            withSourcesJar()
        }
    }
}

dependencies {
    // JNA — UniFFI generated code uses JNA to call into the .so
    api(libs.jna.android)

    // Coroutines — public API uses Flow
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

publishing {
    publications {
        register<MavenPublication>("release") {
            groupId    = "dev.hush"
            artifactId = "hush-sync-kotlin"
            version    = findProperty("VERSION_NAME")?.toString() ?: "0.1.0"

            afterEvaluate {
                from(components["release"])
            }

            pom {
                name.set("hush-sync-kotlin")
                description.set("Android SDK for hush-sync — E2EE local-first sync.")
                url.set("https://github.com/buenomini/hush-sync-kotlin")
                licenses {
                    license {
                        name.set("MIT")
                        url.set("https://opensource.org/licenses/MIT")
                    }
                }
            }
        }
    }
}
