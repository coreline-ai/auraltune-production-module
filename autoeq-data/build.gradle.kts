plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.coreline.autoeq"
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

    // Room schema export (Phase 4): committed under autoeq-data/schemas for migration tests.
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
    api(project(":audio-engine"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.datastore.preferences)

    // api: AutoEqRepository's constructor exposes OkHttpClient, so consumers (like :app)
    // need transitive access for the default-argument call site to compile.
    api(libs.okhttp)
    implementation(libs.okhttp.logging)

    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)

    // Room (Phase 4: DB-first catalog). room-ktx for coroutine/Flow DAO support.
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
}
