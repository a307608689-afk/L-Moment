plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.lmcamera.app"
    compileSdk = 34
    // 预编译 ncnn（20261020）用较新的 NDK 构建，其静态库引用 NDK r26+ 才有的
    // libc++ 符号（std::__hash_memory）。留在工具链默认的 r25 会链接失败，故显式钉版本；
    // 缺失的符号由 LibcxxShim.cpp 补齐。
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "com.lmcamera.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 10500
        versionName = "1.5.0"
        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 仅分享用途：用自动生成的 debug 密钥签名，使 release 包可直接安装
            //（不走商店，不需要自建 keystore）。如需上架再换成正式签名。
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
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
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.exifinterface:exifinterface:1.3.7")
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.18.0")
}
