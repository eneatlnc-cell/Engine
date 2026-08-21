import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.serialization)
}

// ---- v3.23.3: 可选共享签名 (Engine 与 Vault 必须同证书) ------------------
//
// 根因背景: 两 App 的全部 IPC 入口由 signature 级自定义权限互锁
// (com.vault.permission.VAULT_IPC / com.engine.permission.ENGINE_CALLBACK),
// 签名证书不一致时: ①跨应用唤起直接 SecurityException (表现为
// "无法唤起 Vault"); ②无法覆盖安装, 更新必须卸载重装 → 应用数据全清
// → "应用更新造成用户身份丢失"。
//
// 机制: 仓库根目录放一份 signing.properties (已被 .gitignore 排除,
// 密钥绝不入库), 两工程填同一份密钥库信息 → 任何机器的构建产物
// 签名一致, 更新可原地覆盖、IPC 常通。未配置时回退构建机默认
// debug keystore —— 此时两应用必须在同一台机器构建才能同签名。
//
// 配置方法见 signing.properties.example。
val signingPropsFile = rootProject.file("signing.properties")
val signingProps = Properties().apply {
    if (signingPropsFile.exists()) {
        signingPropsFile.inputStream().use { load(it) }
    }
}

android {
    namespace = "com.engine"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.engine"
        minSdk = 26
        targetSdk = 34
        // v3.23.3: IPC 签名诊断 (唤起失败根因可见化) + 可选共享签名机制
        versionCode = 15
        versionName = "3.23.3"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // v2: 中继服务器地址经 -PrelayUrl=wss://your-vps/ws 覆盖 (详见 README 部署章节)
        val relayUrl: String = (project.findProperty("relayUrl") as String?)
            ?: "ws://10.0.2.2:8080"   // 模拟器默认: 本机中继
        buildConfigField("String", "RELAY_URL", "\"$relayUrl\"")
    }

    signingConfigs {
        if (signingPropsFile.exists()) {
            create("sharedIpc") {
                storeFile = rootProject.file(signingProps.getProperty("storeFile"))
                storePassword = signingProps.getProperty("storePassword")
                keyAlias = signingProps.getProperty("keyAlias")
                keyPassword = signingProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("sharedIpc")
            }
        }
        release {
            if (signingPropsFile.exists()) {
                signingConfig = signingConfigs.getByName("sharedIpc")
            }
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
