plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.coreline.audio"
    compileSdk = libs.versions.compileSdk.get().toInt()
    ndkVersion = libs.versions.ndk.get()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")

        ndk {
            // Phase 0 ABI policy: arm64-v8a + x86_64 (no armeabi-v7a unless required).
            abiFilters += listOf("arm64-v8a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf(
                    "-std=c++17",
                    "-O3",
                    // Critical: must NOT enable -ffast-math / -ffinite-math-only or NaN guard
                    // is dead-coded by the optimizer.
                    "-fno-finite-math-only",
                    "-ffunction-sections",
                    "-fdata-sections",
                )
                arguments += listOf(
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "consumer-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        // Strip unused sections (we compiled with -ffunction-sections / -fdata-sections).
        jniLibs {
            useLegacyPackaging = false
        }
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)

    // Unit tests — Tier B-1 builder validation needs JUnit + mockk (constructor +
    // AudioTrack mocking) + Robolectric to no-op System.loadLibrary on the host JVM.
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.robolectric)
}
