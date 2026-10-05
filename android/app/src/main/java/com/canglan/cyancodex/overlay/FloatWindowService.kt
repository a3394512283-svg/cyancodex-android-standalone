package com.canglan.cyancodex.overlay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import android.text.InputType
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.canglan.cyancodex.MainActivity
import com.canglan.cyancodex.R
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.Command
import com.canglan.cyancodex.data.QuickCommand
import com.canglan.cyancodex.data.Session
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * 悬浮窗 v3 (对标 BNM): 悬浮球(可拖动/贴边) → 点击弹出主悬浮窗
 * 主悬浮窗: 指令库/快捷/收藏/App + 搜索 + 逐条复制 + ⚙ 大小/透明度调节 + 右下角缩放手柄
 * 指令库列表样式参考 App 内指令卡片; 点击卡片 → 详情视图(与 App 一致: 按命令块分段逐条复制)
 */
class FloatWindowService : Service() {

    companion object {
        const val CHANNEL_ID = "cyancodex_float"
        private const val TAG = "CyanCodexFloat"
        @Volatile var running = false
            private set

        fun start(context: Context) {
            if (running) return
            if (!Settings.canDrawOverlays(context)) {
                Toast.makeText(context, "请先在设置中授予悬浮窗权限", Toast.LENGTH_LONG).show()
                return
            }
            val i = Intent(context, FloatWindowService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, FloatWindowService::class.java))
        }
    }

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var wm: WindowManager? = null
    private var ballView: View? = null
    private var ballLp: WindowManager.LayoutParams? = null
    private var panelView: View? = null
    private var panelLp: WindowManager.LayoutParams? = null
    private var panelVisible = false
    private var listBox: LinearLayout? = null
    private var searchBox: EditText? = null
    private var detailRoot: LinearLayout? = null
    private var tabViews = mutableListOf<TextView>()
    private var tab = 0
    private var libItems: List<Command> = emptyList()
    private var favItems: List<QuickCommand> = emptyList()
    private val density get() = resources.displayMetrics.density
    private val screenW get() = resources.displayMetrics.widthPixels
    private val screenH get() = resources.displayMetrics.heightPixels

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "onCreate: start")
        createChannel()
        startForeground(1001, buildNotification())
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "未授予悬浮窗权限，无法显示", Toast.LENGTH_LONG).show()
            stopSelf()
            return
        }
        try {
            showBall()
            running = true
        } catch (e: Exception) {
            running = false
            Log.e(TAG, "onCreate: showBall failed", e)
            Toast.makeText(this, "悬浮窗启动失败: ${e.message}", Toast.LENGTH_LONG).show()
            stopSelf()
        }
    }

    override fun onDestroy() {
        running = false
        scope.cancel()
        panelView?.let { runCatching { wm?.removeView(it) } }
        ballView?.let { runCatching { wm?.removeView(it) } }
        panelView = null
        ballView = null
        panelVisible = false
        super.onDestroy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        refreshData()
        // 自愈: 系统可能回收了窗口(虚拟引擎/内存压力), 重启后自动重建悬浮球
        if (ballView == null && Settings.canDrawOverlays(this)) {
            try {
                showBall()
                Log.i(TAG, "onStartCommand: ball rebuilt (self-heal)")
            } catch (e: Exception) {
                Log.e(TAG, "onStartCommand: rebuild failed", e)
            }
        }
        return START_STICKY
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            val ch = NotificationChannel(CHANNEL_ID, "CyanCodex 悬浮窗", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("CyanCodex 悬浮窗运行中")
            .setContentText("悬浮球点击打开指令面板")
            .setContentIntent(pi)
            .setOngoing(true)
            .build()
    }

    // ==================== 悬浮球 (BNM 式) ====================
    /** 窗口类型候选: 卓易通/虚拟引擎等环境对 OVERLAY 支持差, 依次降级重试 */
    private val overlayTypes = intArrayOf(
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.TYPE_PHONE,
        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT,
    )

    /** 尝试用给定窗口类型添加视图; 成功返回使用的类型, 失败返回 -1 */
    private fun tryAddView(v: View, lp: WindowManager.LayoutParams): Int {
        for (t in overlayTypes) {
            try {
                lp.type = t
                wm?.addView(v, lp)
                return t
            } catch (e: Exception) {
                Log.w(TAG, "addView type=$t failed: ${e.message}")
            }
        }
        return -1
    }

    private fun showBall() {
        if (ballView != null) return
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val size = (Session.floatBallSizeDp.coerceIn(40, 80) * density).toInt()

        // 悬浮球: 圆形 App 图标 + 绿边
        val ball = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.ball_bg)
            setOnClickListener { togglePanel() }
        }
        val icon = ImageView(this).apply {
            setImageResource(R.mipmap.ic_launcher)
            scaleType = ImageView.ScaleType.CENTER_CROP
            // 圆形裁剪
            clipToOutline = true
            outlineProvider = object : android.view.ViewOutlineProvider() {
                override fun getOutline(view: View, outline: android.graphics.Outline) {
                    outline.setOval(0, 0, view.width, view.height)
                }
            }
        }
        val pad = (3 * density).toInt()   // 留出绿边
        ball.addView(icon, FrameLayout.LayoutParams(size - pad * 2, size - pad * 2, Gravity.CENTER))

        ballLp = WindowManager.LayoutParams(
            size, size,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            // 位置钳制到屏幕内 (游戏旋转/分辨率变化后旧坐标可能越界, 球会跑到屏外点不到 -> "打不开")
            val savedX = if (Session.floatBallX >= 0) Session.floatBallX else screenW - size - (6 * density).toInt()
            val savedY = if (Session.floatBallY >= 0) Session.floatBallY else (screenH / 2 - size / 2)
            x = savedX.coerceIn(0, screenW - size)
            y = savedY.coerceIn(0, screenH - size)
        }

        // 拖动 + 贴边 + 点击切换
        var downX = 0f
        var downY = 0f
        var moved = false
        ball.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = ev.rawX
                    downY = ev.rawY
                    moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val lp = ballLp ?: return@setOnTouchListener true
                    val dx = ev.rawX - downX
                    val dy = ev.rawY - downY
                    if (Math.abs(dx) > 6 || Math.abs(dy) > 6) moved = true
                    lp.x = (lp.x + dx).toInt().coerceIn(0, screenW - lp.width)
                    lp.y = (lp.y + dy).toInt().coerceIn(0, screenH - lp.height)
                    wm?.updateViewLayout(v, lp)
                    downX = ev.rawX
                    downY = ev.rawY
                    true
                }
                MotionEvent.ACTION_UP -> {
                    val lp = ballLp ?: return@setOnTouchListener true
                    if (!moved) {
                        // 点击 → 切换面板
                        v.performClick()
                    } else {
                        // 贴边 (BNM 式: 吸到最近的左/右边缘, 露出约 1/3)
                        val stickW = (lp.width * 2 / 3)
                        val left = lp.x < screenW / 2
                        lp.x = if (left) -stickW else screenW - lp.width + stickW
                        wm?.updateViewLayout(v, lp)
                        Session.floatBallX = lp.x
                        Session.floatBallY = lp.y
                    }
                    true
                }
                else -> false
            }
        }

        ballView = ball
        val usedType = tryAddView(ball, ballLp!!)
        if (usedType < 0) {
            ballView = null
            throw IllegalStateException("所有悬浮窗类型均被系统拒绝，请检查系统权限或虚拟环境设置")
        }
        refreshData()
    }

    private fun togglePanel() {
        try {
            if (panelVisible) hidePanel() else showPanel()
        } catch (e: Exception) {
            // 面板构建/添加异常时不崩溃进程: 记录 + 清理 + 提示 (反馈: 游戏内打开悬浮窗崩溃)
            Log.e(TAG, "togglePanel failed", e)
            panelView?.let { runCatching { wm?.removeView(it) } }
            panelView = null
            panelVisible = false
            Toast.makeText(this, "悬浮窗打开失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun hidePanel() {
        panelView?.let { runCatching { wm?.removeView(it) } }
        panelView = null
        panelVisible = false
    }

    // ==================== 主悬浮窗面板 ====================
    private fun showPanel() {
        if (panelVisible) return
        try {
            buildPanel()?.let { root ->
                val usedType = tryAddView(root, panelLp!!)
                if (usedType < 0) {
                    throw IllegalStateException("系统拒绝显示悬浮窗（虚拟环境可能需在系统设置开启悬浮窗）")
                }
                panelView = root
                panelVisible = true
                rebuildList()
            }
        } catch (e: Exception) {
            Log.e(TAG, "showPanel failed", e)
            panelView?.let { runCatching { wm?.removeView(it) } }
            panelView = null
            panelVisible = false
            Toast.makeText(this, "悬浮窗打开失败: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun buildPanel(): View? {
        val wm = wm ?: return null
        var panelW = (Session.floatWidthDp * density).toInt()
        var panelH = (Session.floatHeightDp * density).toInt()
        var opacity = Session.floatOpacity.coerceIn(0.2f, 1f)
        var settingsOpen = false
        var settingsBar: LinearLayout? = null
        var lp: WindowManager.LayoutParams? = null

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(14, 21, 18))
            setPadding((10 * density).toInt(), (8 * density).toInt(), (10 * density).toInt(), (8 * density).toInt())
            alpha = opacity
        }

        // 标题栏(可拖动)
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val titleTv = TextView(this).apply {
            text = "⌘ CyanCodex"
            setTextColor(Color.rgb(74, 222, 128))
            textSize = 14f
            typeface = Typeface.DEFAULT_BOLD
        }
        header.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val refreshBtn = Button(this).apply {
            text = "刷新"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 45, 36))
            setOnClickListener { refreshData() }
        }
        header.addView(refreshBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val setBtn = Button(this).apply {
            text = "⚙"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 45, 36))
            setOnClickListener {
                settingsOpen = !settingsOpen
                settingsBar?.visibility = if (settingsOpen) View.VISIBLE else View.GONE
            }
        }
        header.addView(setBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val closeBtn = Button(this).apply {
            text = "×"
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(120, 30, 30))
            setOnClickListener { hidePanel() }
        }
        header.addView(closeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        root.addView(header)

        // 调节面板 (⚙ 展开): 透明度 + 大小
        settingsBar = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
            setBackgroundColor(Color.rgb(22, 34, 28))
            setPadding((8 * density).toInt(), (6 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
        }
        val opacityRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val opacityLabel = TextView(this).apply {
            text = "透明度 ${(opacity * 100).toInt()}%"
            setTextColor(Color.rgb(157, 180, 165))
            textSize = 11f
        }
        opacityRow.addView(opacityLabel, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val opacityBar = android.widget.SeekBar(this).apply {
            max = 80
            progress = ((opacity - 0.2f) * 100).toInt()
            progressTintList = android.content.res.ColorStateList.valueOf(Color.rgb(74, 222, 128))
            setOnSeekBarChangeListener(object : android.widget.SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: android.widget.SeekBar?, p: Int, fromUser: Boolean) {
                    val a = 0.2f + p / 100f
                    root.alpha = a
                    opacityLabel.text = "透明度 ${(a * 100).toInt()}%"
                    Session.floatOpacity = a
                }
                override fun onStartTrackingTouch(sb: android.widget.SeekBar?) {}
                override fun onStopTrackingTouch(sb: android.widget.SeekBar?) {}
            })
        }
        opacityRow.addView(opacityBar, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * density).toInt() })
        settingsBar?.addView(opacityRow)
        val sizeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val sizeLabel = TextView(this).apply {
            text = "大小 ${Session.floatWidthDp}×${Session.floatHeightDp}"
            setTextColor(Color.rgb(157, 180, 165))
            textSize = 11f
        }
        sizeRow.addView(sizeLabel, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        listOf("小" to 220 to 380, "中" to 300 to 520, "大" to 400 to 680).forEach { (pair, h) ->
            val (label, w) = pair
            val btn = Button(this).apply {
                text = label
                textSize = 11f
                setTextColor(Color.WHITE)
                setBackgroundColor(Color.rgb(30, 45, 36))
                setOnClickListener {
                    panelW = (w * density).toInt()
                    panelH = (h * density).toInt()
                    lp?.width = panelW
                    lp?.height = panelH
                    lp?.let { this@FloatWindowService.wm?.updateViewLayout(root, it) }
                    sizeLabel.text = "大小 ${(panelW / density).toInt()}×${(panelH / density).toInt()}"
                    Session.floatWidthDp = (panelW / density).toInt()
                    Session.floatHeightDp = (panelH / density).toInt()
                }
            }
            sizeRow.addView(btn, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (4 * density).toInt() })
        }
        settingsBar?.addView(sizeRow)
        settingsBar?.let { root.addView(it, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (6 * density).toInt() }) }

        // Tab 栏
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val tabNames = listOf("指令库", "快捷", "收藏", "App")
        tabNames.forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
                setOnClickListener { setTab(i) }
            }
            tabViews.add(tv)
            tabBar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (2 * density).toInt() })
        }

        // 搜索框 (指令库 Tab)
        searchBox = EditText(this).apply {
            hint = "搜索指令…"
            setHintTextColor(Color.rgb(157, 180, 165))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 45, 36))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { rebuildList() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }

        // 列表层 + 详情层 (叠层: 点卡片 → 详情覆盖列表, 逐条复制)
        listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listBox) }
        val listRoot = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        listRoot.addView(tabBar, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        listRoot.addView(searchBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
        listRoot.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (4 * density).toInt() })

        detailRoot = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            visibility = View.GONE
        }
        val contentStack = FrameLayout(this).apply {
            addView(listRoot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
            addView(detailRoot, FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT))
        }
        root.addView(contentStack, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (4 * density).toInt() })

        // 右下角缩放手柄 (拖动调节大小)
        val resizeHandle = TextView(this).apply {
            text = "◢"
            textSize = 14f
            setTextColor(Color.rgb(74, 222, 128))
            gravity = Gravity.CENTER
            setBackgroundColor(Color.rgb(22, 34, 28))
        }
        root.addView(resizeHandle, LinearLayout.LayoutParams((30 * density).toInt(), (26 * density).toInt()).apply {
            gravity = Gravity.END
            topMargin = (2 * density).toInt()
        })

        // 面板位置: 默认在悬浮球附近, 贴边侧保持一致
        val ballX = Session.floatBallX
        val ballY = Session.floatBallY
        lp = WindowManager.LayoutParams(
            panelW,
            panelH,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            if (Session.floatX >= 0 && Session.floatY >= 0) {
                x = Session.floatX
                y = Session.floatY
            } else {
                val ballOnRight = ballX < 0 || ballX > screenW / 2
                x = if (ballOnRight) screenW - panelW - (8 * density).toInt() else (8 * density).toInt()
                y = if (ballY >= 0) (ballY - panelH / 2).coerceIn((30 * density).toInt(), screenH - panelH - (30 * density).toInt())
                else (90 * density).toInt()
            }
            // 位置钳制到屏幕内 (防止旧坐标越界导致面板打不开)
            x = x.coerceIn(0, screenW - panelW)
            y = y.coerceIn(0, screenH - panelH)
        }

        val params = lp!!

        // 标题拖动 → 移动面板 + 保存位置
        header.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    params.x = ev.rawX.toInt() - params.width
                    params.y = ev.rawY.toInt() - (v.height / 2)
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    params.x = ev.rawX.toInt() - params.width
                    params.y = ev.rawY.toInt() - (v.height / 2)
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    Session.floatX = params.x
                    Session.floatY = params.y
                    false
                }
                else -> false
            }
        }

        // 缩放手柄
        resizeHandle.setOnTouchListener { v, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    v.setBackgroundColor(Color.rgb(30, 45, 36))
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val newW = (ev.rawX - params.x).toInt()
                    val newH = (ev.rawY - params.y).toInt()
                    val minW = (160 * density).toInt()
                    val minH = (200 * density).toInt()
                    val maxW = (screenW * 0.9f).toInt()
                    val maxH = (screenH * 0.85f).toInt()
                    panelW = newW.coerceIn(minW, maxW)
                    panelH = newH.coerceIn(minH, maxH)
                    params.width = panelW
                    params.height = panelH
                    wm.updateViewLayout(root, params)
                    true
                }
                MotionEvent.ACTION_UP -> {
                    v.setBackgroundColor(Color.rgb(22, 34, 28))
                    Session.floatWidthDp = (panelW / density).toInt()
                    Session.floatHeightDp = (panelH / density).toInt()
                    false
                }
                else -> false
            }
        }

        panelLp = lp
        return root
    }

    private fun setTab(i: Int) {
        try {
            tab = i
            tabViews.forEachIndexed { idx, tv ->
                tv.setTextColor(if (idx == i) Color.rgb(74, 222, 128) else Color.rgb(157, 180, 165))
                tv.setBackgroundColor(if (idx == i) Color.rgb(30, 45, 36) else Color.TRANSPARENT)
            }
            searchBox?.visibility = if (i == 0) View.VISIBLE else View.GONE
            rebuildList()
        } catch (e: Exception) {
            Log.e(TAG, "setTab failed", e)
        }
    }

    /** 拉取指令库/收藏 */
    private fun refreshData() {
        if (tab == 0) {
            scope.launch {
                runCatching {
                    val r = ApiClient.get().commands(null, "all", null, null, null, 1, 50)
                    if (r.ok) r.data?.list ?: emptyList() else emptyList()
                }.onSuccess { libItems = it }.onFailure { libItems = emptyList() }
                rebuildList()
            }
        } else if (tab == 2) {
            scope.launch {
                runCatching {
                    val r = ApiClient.get().favorites()
                    if (r.ok) r.data?.map { QuickCommand(it.title, it.command) } ?: emptyList()
                    else emptyList()
                }.onSuccess { favItems = it }.onFailure { favItems = emptyList() }
                rebuildList()
            }
        } else {
            rebuildList()
        }
    }

    private fun allQuick(): List<QuickCommand> {
        val list = mutableListOf<QuickCommand>()
        Session.quickCommands().forEach { list.add(it) }
        try {
            val cached = Session.cachedFavorites
            val type = object : TypeToken<List<QuickCommand>>() {}.type
            gson.fromJson<List<QuickCommand>>(cached, type)?.forEach { list.add(it) }
        } catch (e: Exception) {}
        Session.recentCommands().forEach { c -> list.add(QuickCommand("最近", c)) }
        return list.distinctBy { it.command }
    }

    private fun catLabel(c: String) = when (c) {
        "survival" -> "生存"
        "adventure" -> "冒险"
        "redstone" -> "红石"
        "scoreboard" -> "计分板"
        "gamerule" -> "游戏规则"
        else -> "其他"
    }

    /** 指令库列表: 参考 App 内指令卡片样式 (标题 + 深色指令框 + 分类·版本 + 复制) */
    private fun rebuildList() {
        try {
            rebuildListImpl()
        } catch (e: Exception) {
            Log.e(TAG, "rebuildList failed", e)
        }
    }

    private fun rebuildListImpl() {
        val box = listBox ?: return
        box.removeAllViews()

        val items = when (tab) {
            0 -> {
                val kw = searchBox?.text?.toString()?.trim().orEmpty()
                libItems.filter { kw.isEmpty() || it.title.contains(kw) || it.command.contains(kw) }
            }
            2 -> favItems
            else -> allQuick()
        }.take(20)

        if (tab == 0 && items.isEmpty()) {
            box.addView(emptyHint("指令库加载中/为空\n点「刷新」重试；在 App 指令库上传后同步"))
        } else if (tab == 2 && items.isEmpty()) {
            box.addView(emptyHint("暂无收藏\n去 App 指令库点收藏"))
        } else if (tab != 0 && items.isEmpty()) {
            box.addView(emptyHint("暂无快捷指令\n去 App 指令详情点「加入快捷」"))
        }
        // 非空时提示: 点卡片进详情逐条复制 (仅指令库/收藏)
        if ((tab == 0 || tab == 2) && items.isNotEmpty() && detailRoot?.visibility != View.VISIBLE) {
            box.addView(hintLine("点卡片查看详情 · 逐条复制"))
        }

        items.forEachIndexed { idx, item ->
            val title: String
            val command: String
            val category: String
            val version: String
            if (tab == 0 && item is Command) {
                title = item.title
                command = item.command
                category = catLabel(item.category)
                version = item.mcVersion.ifBlank { "1.20+" }
            } else {
                val qc = item as QuickCommand
                title = qc.title.ifBlank { "指令" }
                command = qc.command
                category = ""
                version = ""
            }
            // 卡片 (深色圆角, 参考 App CommandCard; 点击 → 详情逐条复制)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(22, 34, 28))
                setPadding((8 * density).toInt(), (8 * density).toInt(), (8 * density).toInt(), (6 * density).toInt())
                setOnClickListener {
                    if (tab == 1 || tab == 3) copyCommand(title, command)
                    else showDetail(title, command, category, version)
                }
            }
            val titleTv = TextView(this).apply {
                text = title
                setTextColor(Color.rgb(251, 191, 36))
                textSize = 12f
                typeface = Typeface.DEFAULT_BOLD
                maxLines = 1
            }
            card.addView(titleTv)
            // 指令内容框 (深色, 等宽, 参考 App 卡片预览)
            val cmdBox = TextView(this).apply {
                text = command
                setTextColor(Color.rgb(231, 242, 234))
                textSize = 11f
                typeface = Typeface.MONOSPACE
                maxLines = 2
                setBackgroundColor(Color.rgb(10, 15, 12))
                setPadding((6 * density).toInt(), (5 * density).toInt(), (6 * density).toInt(), (5 * density).toInt())
            }
            card.addView(cmdBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (5 * density).toInt() })
            // 底行: 分类·版本 (左侧) + 复制 (右侧)
            val footRow = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
            }
            val metaTv = TextView(this).apply {
                text = if (category.isNotEmpty()) "$category · $version" else ""
                setTextColor(Color.rgb(157, 180, 165))
                textSize = 10f
            }
            footRow.addView(metaTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { topMargin = (3 * density).toInt() })
            val copyBtn = Button(this).apply {
                text = "复制"
                textSize = 11f
                setTextColor(Color.rgb(74, 222, 128))
                setBackgroundColor(Color.rgb(30, 45, 36))
                setOnClickListener { copyCommand(title, command) }
            }
            footRow.addView(copyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (3 * density).toInt() })
            card.addView(footRow)
            box.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (6 * density).toInt() })
        }

        if (tab == 3) {
            box.addView(appPanel(density))
        }
    }

    private fun emptyHint(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(157, 180, 165))
        textSize = 12f
        gravity = Gravity.CENTER
        setPadding(0, (14 * resources.displayMetrics.density).toInt(), 0, (14 * resources.displayMetrics.density).toInt())
    }

    private fun hintLine(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.rgb(74, 222, 128))
        textSize = 10f
        setPadding((2 * resources.displayMetrics.density).toInt(), 0, 0, (4 * resources.displayMetrics.density).toInt())
    }

    /** 指令详情 (对标 App CommandDetailScreen): 按命令块分段, 每段可单独复制 */
    private fun showDetail(title: String, command: String, category: String, version: String) {
        val dr = detailRoot ?: return
        dr.removeAllViews()
        val lines = command.split("\n").map { it.trim() }.filter { it.isNotBlank() }

        // 头部: 返回 / 标题 / 复制全部
        val head = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val backBtn = Button(this).apply {
            text = "‹ 返回"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(30, 45, 36))
            setOnClickListener { hideDetail() }
        }
        head.addView(backBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val titleTv = TextView(this).apply {
            text = title
            setTextColor(Color.rgb(251, 191, 36))
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
        }
        head.addView(titleTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * density).toInt() })
        val copyAllBtn = Button(this).apply {
            text = if (lines.size > 1) "复制全部(${lines.size}段)" else "复制指令"
            textSize = 11f
            setTextColor(Color.rgb(14, 21, 18))
            setBackgroundColor(Color.rgb(74, 222, 128))
            setOnClickListener { copyCommand(title, command, "已复制全部 ${lines.size} 段") }
        }
        head.addView(copyAllBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        dr.addView(head)

        // 元信息
        if (category.isNotEmpty()) {
            val metaTv = TextView(this).apply {
                text = "$category · ${version.ifBlank { "1.20+" }}"
                setTextColor(Color.rgb(157, 180, 165))
                textSize = 10f
            }
            dr.addView(metaTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { topMargin = (4 * density).toInt() })
        }

        // 分段列表: 每段 序号 + 指令框 + 复制
        val segBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        lines.forEachIndexed { idx, ln ->
            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setBackgroundColor(Color.rgb(10, 15, 12))
                setPadding((6 * density).toInt(), (5 * density).toInt(), (6 * density).toInt(), (5 * density).toInt())
            }
            val numTv = TextView(this).apply {
                text = "第${idx + 1}段"
                setTextColor(Color.rgb(74, 222, 128))
                textSize = 10f
                typeface = Typeface.DEFAULT_BOLD
            }
            row.addView(numTv, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
            val cmdTv = TextView(this).apply {
                text = ln
                setTextColor(Color.rgb(231, 242, 234))
                textSize = 11f
                typeface = Typeface.MONOSPACE
            }
            row.addView(cmdTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply { marginStart = (6 * density).toInt() })
            val copyBtn = Button(this).apply {
                text = "复制"
                textSize = 10f
                setTextColor(Color.rgb(74, 222, 128))
                setBackgroundColor(Color.rgb(30, 45, 36))
                setOnClickListener { copyCommand(title, ln, "已复制第 ${idx + 1} 段") }
            }
            row.addView(copyBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { marginStart = (6 * density).toInt() })
            segBox.addView(row, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT).apply { bottomMargin = (5 * density).toInt() })
        }
        val segScroll = ScrollView(this).apply { addView(segBox) }
        dr.addView(segScroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f).apply { topMargin = (5 * density).toInt() })

        dr.visibility = View.VISIBLE
    }

    private fun hideDetail() {
        detailRoot?.visibility = View.GONE
        rebuildList()
    }

    private fun appPanel(density: Float): LinearLayout {
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val openBtn = Button(this).apply {
            text = "打开 CyanCodex App"
            textSize = 13f
            setTextColor(Color.rgb(14, 21, 18))
            setBackgroundColor(Color.rgb(74, 222, 128))
            setOnClickListener {
                val i = Intent(this@FloatWindowService, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                runCatching { startActivity(i) }
            }
        }
        panel.addView(openBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val hint = TextView(this).apply {
            text = "编辑器/T显/穷举/字幕/音乐/AI 等请在 App 内使用\n悬浮窗专注快捷复制"
            setTextColor(Color.rgb(157, 180, 165))
            textSize = 11f
            gravity = Gravity.CENTER
            setPadding(0, (8 * density).toInt(), 0, 0)
        }
        panel.addView(hint)
        return panel
    }

    private fun copyCommand(title: String, command: String) = copyCommand(title, command, null)

    private fun copyCommand(title: String, command: String, toast: String?) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("command", command))
        Session.addRecent(command)
        Toast.makeText(this, toast ?: "已复制: $title", Toast.LENGTH_SHORT).show()
    }
}
