import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Release signing: keystore.properties locally, or LF_KEYSTORE_* env vars in CI.
// Without either the release build falls back to the debug key.
val keyProps = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun key(name: String): String? = keyProps.getProperty(name) ?: System.getenv("LF_" + name.uppercase())

android {
    namespace = "com.jaryah.localflow"
    compileSdk = 35
    ndkVersion = "27.2.12479018"

    defaultConfig {
        applicationId = "com.jaryah.localflow"
        minSdk = 26
        targetSdk = 35
        versionCode = (System.getenv("LF_VERSION_CODE") ?: "2").toInt()
        versionName = System.getenv("LF_VERSION_NAME") ?: "1.1.0"
        ndk { abiFilters += "arm64-v8a" }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild { cmake { arguments += listOf("-DANDROID_STL=c++_static") } }
    }

    signingConfigs {
        create("release") {
            val store = key("keystore_file")
            if (store != null) {
                storeFile = file(store)
                storePassword = key("keystore_password")
                keyAlias = key("key_alias")
                keyPassword = key("key_password")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (key("keystore_file") != null) signingConfigs.getByName("release")
                            else signingConfigs.getByName("debug")
        }
    }

    externalNativeBuild {
        cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
