plugins {
    kotlin("jvm") version "2.4.21"
    id("org.jetbrains.intellij.platform") version "2.20.0"
}

group = "com.thousandyearsofwar"

val riderHome: String = providers.gradleProperty("riderHome").get()
val pluginVersion: String = providers.gradleProperty("pluginVersion").getOrElse("0.1.0")
val pluginSinceBuild: String = providers.gradleProperty("pluginSinceBuild").getOrElse("262")

version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        // 本地安装的 Rider：2026.2 的产物在公开仓库里通常拿不到，直接指本机最稳
        local(riderHome)
    }
}

intellijPlatform {
    instrumentCode = false
    buildSearchableOptions = false

    pluginConfiguration {
        id = "com.thousandyearsofwar.ridenotes"
        name = "Sidecar Notes"
        version = pluginVersion

        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { "$pluginSinceBuild.*" }
        }
    }

    // 用本机 Rider 做兼容性校验（公开仓库里通常拿不到 2026.2 的产物）
    pluginVerification {
        ides {
            local(riderHome)
        }
    }
}
