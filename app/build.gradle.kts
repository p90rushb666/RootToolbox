plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "dev.mochi.rootbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.mochi.rootbox"
        minSdk = 29
        targetSdk = 36
        versionCode = 2
        versionName = "1.1.0"
    }

    buildTypes {
        release {
            // 和 mochi 官方 demo 一样发优化包：debug 包是解释执行 + 无 R8，启动和滚动都会明显更慢。
            isMinifyEnabled = true
            // 图标资源按名字动态读取，别裁资源。
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // 用 debug 签名，方便直接安装（分发时再换正式签名）。
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // mochi-compose 0.2.0 is built against Compose 1.10; keep the whole Compose stack on that line.
    val composeVersion = "1.10.6"
    implementation("androidx.compose.ui:ui:$composeVersion")
    implementation("androidx.compose.ui:ui-graphics:$composeVersion")
    implementation("androidx.compose.foundation:foundation:$composeVersion")
    implementation("androidx.compose.runtime:runtime:$composeVersion")
    implementation("androidx.activity:activity-compose:1.12.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-collections-immutable:0.5.2")

    // Play Integrity：客户端只负责取加密 token，解码必须由服务端完成。
    implementation("com.google.android.play:integrity:1.4.0")

    implementation("io.github.feice11:mochi-compose:0.2.0")
    implementation("io.github.feice11:mochi-icons:0.2.0")
}
