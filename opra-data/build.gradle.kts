plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.coreline.auraltune.opra"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()

        consumerProguardFiles("consumer-rules.pro")
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

    // Room schema export — committed under opra-data/schemas for migration tests (Phase 3).
    ksp {
        arg("room.schemaLocation", "$projectDir/schemas")
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // OPRA module is fully isolated from :autoeq-data. It only depends on :audio-engine
    // for the engine filter-type enum (EqFilterType) used by the supported-filter check.
    // The OpraProfile -> engine-model adapter lives in :app, not here.
    api(project(":audio-engine"))

    implementation(libs.androidx.core.ktx)

    // OPRA fetch (debug: GitHub raw; release: AuralTune mirror/cache or bundled snapshot).
    api(libs.okhttp)
    implementation(libs.okhttp.logging)

    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    // Room — OPRA-only DB/cache (Phase 3), separate from :autoeq-data's database.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
