package com.canglan.cyancodex

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import com.canglan.cyancodex.data.AnnouncementNotifier
import com.canglan.cyancodex.data.DebugStore
import com.canglan.cyancodex.data.Session
import com.canglan.cyancodex.ui.AppNav
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.GlobalBackgroundImage
import com.canglan.cyancodex.ui.theme.CyanCodexTheme

class MainActivity : ComponentActivity() {

    private val notifPermLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        DebugStore.logD("Life", "MainActivity.onCreate")
        // ★ 必须早于 setContent: 把"已保存但待生效"的液态玻璃设置刷成当前生效值。
        // 设置页拨开关只改 Session, 不动冻结值; 只有重建 Activity / 重开 App 才走到这里,
        // 这正是"外观重启后生效"的落点。放在 setContent 之前是为了避免先画一帧旧外观。
        AppState.refreshGlassAtLaunch()
        DebugStore.logD("Life", "液态玻璃设置已刷新 (生效值=${Session.liquidGlass})")
        maybeRequestNotifPermission()
        // 后台常驻收消息: App 在前台时启动前台服务, 关掉 App 后消息也能实时到 (静默通知)
        com.canglan.cyancodex.data.MessageWatchService.start(this)
        DebugStore.logD("Life", "消息前台服务已启动")
        // 每次回到前台立即检查一次新公告/站内信, 及时推送通知
        lifecycle.addObserver(object : LifecycleEventObserver {
            override fun onStateChanged(source: LifecycleOwner, event: Lifecycle.Event) {
                if (event == Lifecycle.Event.ON_RESUME) {
                    DebugStore.logD("Life", "回到前台 (ON_RESUME) → 重查公告")
                    com.canglan.cyancodex.data.MessageWatchService.start(this@MainActivity)
                    AnnouncementNotifier.checkNow(this@MainActivity)
                }
            }
        })
        setContent {
            val mode = AppState.themeMode
            val dark = when (mode) {
                "light" -> false
                "dark" -> true
                else -> isSystemInDarkTheme()
            }
            // 主题来源: brand(默认) / dynamic(莫奈取色) / custom(自定义主色)
            val source = AppState.themeSource
            val dynamic = source == "dynamic"
            val customSeed = if (source == "custom")
                runCatching { androidx.compose.ui.graphics.Color(android.graphics.Color.parseColor(AppState.themeCustomColor)) }.getOrNull()
            else null
            CyanCodexTheme(darkTheme = dark, dynamicColor = dynamic, customSeed = customSeed) {
                // 全局背景图铺在整个 App 的最底层(2026-09-26 由"仅主页"改为全局)。
                // 配套: CyanCodexTheme 在有背景图时把 colorScheme.background 置为全透明,
                // 否则各页 Scaffold 的默认容器色会把它盖住。
                Box(Modifier.fillMaxSize()) {
                    GlobalBackgroundImage()
                    AppNav()
                }
            }
        }
        DebugStore.logD("Life", "界面已挂载 (setContent)")
    }

    private fun maybeRequestNotifPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            DebugStore.logD("Life", "申请通知权限 (POST_NOTIFICATIONS)")
            notifPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
