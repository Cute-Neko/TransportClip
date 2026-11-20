import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.catgirl.transportclip"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.catgirl.transportclip"
        minSdk = 30
        targetSdk = 36
        versionCode = 10
        versionName = "0.3.4"
    }

    signingConfigs {
        create("release") {
            val ksFile = rootProject.file("keystore.properties")
            if (ksFile.exists()) {
                val props = Properties().apply { ksFile.inputStream().use { load(it) } }
                storeFile = file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Jetpack Ink 1.0.0(稳定版):低延迟手写渲染 + 运动预测
    implementation("androidx.ink:ink-authoring:1.0.0")
    implementation("androidx.ink:ink-brush:1.0.0")
    implementation("androidx.ink:ink-strokes:1.0.0")
    implementation("androidx.ink:ink-rendering:1.0.0")
    implementation("androidx.input:input-motionprediction:1.0.1")
}
