package com.thousandyearsofwar.ridenotes.util

/**
 * 插件版本号。
 *
 * 之前用 `PluginManagerCore.getPlugin(...)` 读，但那是 @Internal API，插件验证器会报
 * INTERNAL_API_USAGES 并判失败。这里改成读取构建期写入的资源文件，零内部 API。
 */
object PluginInfo {

    val version: String by lazy { load() }

    private fun load(): String = runCatching {
        PluginInfo::class.java.getResourceAsStream("/ridenotes-version.properties")?.use { stream ->
            java.util.Properties().apply { load(stream) }.getProperty("version").orEmpty().trim()
        }.orEmpty()
    }.getOrDefault("")
}
