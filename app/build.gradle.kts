import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// 读取签名配置：默认用 SDK 自带的 debug keystore 签名，
// 保证 release 也能直接安装测试，且与 debug 版可互相覆盖升级。
val keystoreProperties = Properties().apply {
    val f = rootProject.file("app/keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.grok2api.gateway"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.grok2api.gateway"
        minSdk = 24
        targetSdk = 36
        versionCode = 17
        versionName = "1.5.3-test"
    }

    signingConfigs {
        create("grok") {
            storeFile = rootProject.file(keystoreProperties["storeFile"] as? String ?: "app/debug.keystore")
            storePassword = keystoreProperties["storePassword"] as? String ?: "android"
            keyAlias = keystoreProperties["keyAlias"] as? String ?: "androiddebugkey"
            keyPassword = keystoreProperties["keyPassword"] as? String ?: "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("grok")
        }
        debug {
            signingConfig = signingConfigs.getByName("grok")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")   // JVM 单测里补上 Android 才有的 org.json
}