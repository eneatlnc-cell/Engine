pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "engine"

// Engine 仓库范围：
// - app-a        : Engine 聊天应用（联网端，作为 Vault 签名服务的使用方）
// - core/*       : 共享基础模块（Engine 使用全部三个；core-crypto / core-ipc 与 Vault 仓库保持同步）
// - relay-server : WebSocket 中继服务（部署于自有 VPS，与 app-a 使用同一套协议定义）
include(":core:core-protocol")
include(":core:core-crypto")
include(":core:core-ipc")
include(":app-a")
include(":relay-server")
