package com.thousandyearsofwar.ridenotes

import com.intellij.DynamicBundle
import org.jetbrains.annotations.PropertyKey

private const val BUNDLE_NAME = "messages.RiderNotesBundle"

/**
 * 插件文案资源。默认英文，`_zh_CN` 为简体中文。
 *
 * plugin.xml 里用 `%key` 引用（已声明 `<resource-bundle>`），
 * 代码里用 [RiderNotesBundle.message]。
 */
object RiderNotesBundle : DynamicBundle(BUNDLE_NAME) {

    fun message(@PropertyKey(resourceBundle = BUNDLE_NAME) key: String, vararg params: Any): String =
        getMessage(key, *params)
}
