plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// 签名说明 (v3.23.4, 共享签名机制已撤销 —— 违背项目密钥原则):
// 两 App 的 IPC 入口由 signature 级权限互锁, 签名证书必须一致。
// 开发期: Engine 与 Vault 在同一台机器构建 (共用 ~/.android/debug.keystore),
// 签名天然一致, 无需任何配置。
// 换机/重装/签名变化导致的身份找回: 走 Vault 的「迁移」功能
// (指纹门 + 二维码光学通道转移绑定, 私钥全程不离用户授权)。
// AppBIpcClient.diagnoseVaultChannel() 会在签名不一致时给出定向指引。

android {
    namespace = "com.engine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.engine"
        minSdk = 26
        targetSdk = 34
        // v3.23.4: 撤销共享签名机制 (违背密钥原则), 失败指引改指 Vault 迁移通道
        versionCode = 16
        versionName = "3.23.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // v2: 中继服务器地址经 -PrelayUrl=wss://your-vps/ws 覆盖 (详见 README 部署章节)
        val relayUrl: String = (project.findProperty("relayUrl") as String?)
            ?: "ws://10.0.2.2:8080"   // 模拟器默认: 本机中继
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

    buildFeatures {
        compose = true
        // v2: 生成 BuildConfig.RELAY_URL
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.8"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 核心共享模块
    implementation(project(":core:core-protocol"))
    implementation(project(":core:core-crypto"))
    implementation(project(":core:core-ipc"))

    // Compose BOM
    val composeBom = platform(libs.androidx.compose.bom)
    implementation(composeBom)
    androidTestImplementation(composeBom)

    // Compose
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    // Networking
    implementation(libs.okhttp)

    // QR Code
    implementation(libs.zxing.core)

    // Kotlin
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso)
}
