plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cawzen.printserver"
    compileSdk = 35
    ndkVersion = "27.0.12077973"

    defaultConfig {
        // 新包名，与旧的 com.cawzen.printserver 并存互不干扰。
        // namespace 保持不动 —— 它只决定 R 类与 Manifest 里相对类名的解析，
        // 改了要搬整个源码树，收益为零。真正决定「装到手机上是哪个 App」的是这里。
        applicationId = "com.cawzen.directprint"
        minSdk = 29
        targetSdk = 35
        versionCode = 19
        versionName = "1.0"

        // Mi 9 SE 及绝大多数现代 Android 设备都是 arm64。加其它 ABI 只影响构建时间，
        // 等真正需要时再放开（见 README「已知限制」）。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-O2")
                arguments += listOf("-DANDROID_STL=c++_static")
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        compose = true
    }
    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.14"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")

    testImplementation("junit:junit:4.13.2")
}
