package com.canglan.cyancodex.ui.liquidglass

/*
 * 搬运自官方示例工程 (逐字, 仅改 package):
 *   https://github.com/Kyant0/AndroidLiquidGlass
 *   app/src/commonMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt          (expect)
 *   app/src/androidMain/kotlin/com/kyant/backdrop/catalog/utils/Coroutines.kt         (actual)
 * 许可: Apache License 2.0
 *
 * 改动: 官方是 KMP 多平台工程, expect/actual 分开两个文件;
 *       本工程只要 Android 这一份, 于是把 actual 直接落成唯一实现。
 */

import kotlinx.coroutines.android.awaitFrame as androidAwaitFrame

suspend fun awaitFrame() = androidAwaitFrame()
