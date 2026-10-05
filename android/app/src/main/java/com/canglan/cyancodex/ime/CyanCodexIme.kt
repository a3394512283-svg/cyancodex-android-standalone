package com.canglan.cyancodex.ime

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.inputmethodservice.InputMethodService
import android.text.InputType
import android.view.Gravity
import android.view.KeyEvent
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.canglan.cyancodex.MainActivity
import com.canglan.cyancodex.R
import com.canglan.cyancodex.data.ApiClient
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
 * CyanCodex 输入法: 普通全键盘(可正常打字) + 「指令」面板(指令库/快捷/收藏/最近/常用工具)
 */
class CyanCodexIme : InputMethodService() {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var rootView: LinearLayout? = null
    private var modeBtn: TextView? = null
    private var keyboardBox: LinearLayout? = null
    private var commandBox: LinearLayout? = null
    private var letterBox: LinearLayout? = null
    private var symbolBox: LinearLayout? = null
    private var searchBox: EditText? = null
    private var listContainer: LinearLayout? = null
    private var tabViews = mutableListOf<TextView>()
    private var tab = 0
    private var libItems: List<QuickCommand> = emptyList()
    private var favItems: List<QuickCommand> = emptyList()

    private var shifted = false
    private var shiftLocked = false
    private var isSymbol = false

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    override fun onCreateInputView(): View {
        val density = resources.displayMetrics.density
        rootView = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 30))
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        }

        // ---------- 顶部工具条 ----------
        val toolbar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(Color.rgb(26, 31, 38))
            setPadding((6 * density).toInt(), (4 * density).toInt(), (6 * density).toInt(), (4 * density).toInt())
        }
        modeBtn = TextView(this).apply {
            text = " 指令"
            setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bolt, 0, 0, 0)
            compoundDrawablePadding = (3 * density).toInt()
            textSize = 13f
            gravity = Gravity.CENTER
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(Color.rgb(74, 222, 128))
            setBackgroundColor(Color.rgb(34, 44, 52))
            setPadding((10 * density).toInt(), (4 * density).toInt(), (10 * density).toInt(), (4 * density).toInt())
            setOnClickListener { setMode(1) }
        }
        toolbar.addView(modeBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        val title = TextView(this).apply {
            text = "⌘ CyanCodex 输入法"
            textSize = 12f
            setTextColor(Color.rgb(157, 180, 165))
            gravity = Gravity.CENTER
        }
        toolbar.addView(title, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        val hideBtn = Button(this).apply {
            text = "收起"
            textSize = 11f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(34, 44, 52))
            setOnClickListener { requestHideSelf(0) }
        }
        toolbar.addView(hideBtn, LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT))
        rootView!!.addView(toolbar)

        // ---------- 内容区 ----------
        val content = FrameLayout(this)
        keyboardBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        commandBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(keyboardBox)
        content.addView(commandBox)
        rootView!!.addView(content, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        buildKeyboard()
        buildCommandPanel()
        setMode(0)
        return rootView!!
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        searchBox?.setText("")
        setMode(0)
        setSymbol(false)
        setShift(false)
    }

    // ==================== 键盘 ====================
    private fun key(char: String, onClick: () -> Unit, weight: Float = 1f, bg: Int = 0xFF2A333D.toInt(), fg: Int = Color.WHITE): TextView {
        val density = resources.displayMetrics.density
        return TextView(this).apply {
            text = char
            textSize = 17f
            gravity = Gravity.CENTER
            setTextColor(fg)
            setBackgroundColor(bg)
            setPadding(0, (10 * density).toInt(), 0, (10 * density).toInt())
            setOnClickListener { onClick() }
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, weight).apply {
                marginStart = (2 * density).toInt(); marginEnd = (2 * density).toInt(); topMargin = (2 * density).toInt()
            }
        }
    }

    private fun buildKeyboard() {
        val density = resources.displayMetrics.density
        // 字母键盘
        letterBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val row1 = "qwertyuiop".map { it.toString() }.toMutableList()
        val row2 = "asdfghjkl".map { it.toString() }.toMutableList()
        val row3 = "zxcvbnm".map { it.toString() }.toMutableList()

        letterBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            row1.forEach { c -> r.addView(key(c, { typeChar(c) }, 1f)) }
        })
        letterBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            r.addView(key("⇧", { setShift(true) }, 1.2f, bg = if (shifted || shiftLocked) 0xFF4A7A3C.toInt() else 0xFF2A333D.toInt(), fg = Color.WHITE))
            row2.forEach { c -> r.addView(key(c, { typeChar(c) }, 1f)) }
            r.addView(key("⌫", { backspace() }, 1.2f, fg = Color.rgb(255, 150, 150)))
        })
        letterBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            r.addView(key("?123", { setSymbol(true) }, 1.3f, bg = 0xFF232B34.toInt()))
            row3.forEach { c -> r.addView(key(c, { typeChar(c) }, 1f)) }
            r.addView(key("⌫", { backspace() }, 1.3f, fg = Color.rgb(255, 150, 150)))
        })
        letterBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            r.addView(key(",", { typeChar(",") }, 1.2f, bg = 0xFF232B34.toInt()))
            r.addView(key("空格", { typeChar(" ") }, 4.2f, bg = 0xFF2A333D.toInt()))
            r.addView(key(".", { typeChar(".") }, 1.2f, bg = 0xFF232B34.toInt()))
            r.addView(key("⏎", { enter() }, 1.6f, bg = 0xFF2F5E3C.toInt()))
        })
        keyboardBox!!.addView(letterBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))

        // 符号键盘
        symbolBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; visibility = View.GONE }
        val symRow1 = "1234567890".map { it.toString() }.toMutableList()
        val symRow2 = listOf("-", "/", ":", ";", "(", ")", "@", "\"", "&", "*")
        val symRow3 = listOf("!", "?", "'", "#", "%", "^", "_", "+", "=", "~")
        symbolBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            symRow1.forEach { c -> r.addView(key(c, { typeChar(c) }, 1f)) }
        })
        symbolBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            symRow2.forEach { c -> r.addView(key(c, { typeChar(c) }, 1f)) }
        })
        symbolBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            symRow3.forEach { c -> r.addView(key(c, { typeChar(c) }, 1f)) }
        })
        symbolBox!!.addView(LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }.also { r ->
            r.addView(key("ABC", { setSymbol(false) }, 1.4f, bg = 0xFF232B34.toInt()))
            r.addView(key("⌫", { backspace() }, 1.4f, fg = Color.rgb(255, 150, 150)))
            r.addView(key("空格", { typeChar(" ") }, 3.4f, bg = 0xFF2A333D.toInt()))
            r.addView(key("⏎", { enter() }, 1.6f, bg = 0xFF2F5E3C.toInt()))
        })
        keyboardBox!!.addView(symbolBox, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT))
    }

    private fun setSymbol(s: Boolean) {
        isSymbol = s
        letterBox?.visibility = if (s) View.GONE else View.VISIBLE
        symbolBox?.visibility = if (s) View.VISIBLE else View.GONE
    }

    private fun setShift(on: Boolean) {
        shifted = on
    }

    private fun typeChar(c: String) {
        val out = if (shifted && c.length == 1 && c[0].isLetter()) c.uppercase() else c
        if (shifted && !shiftLocked) setShift(false)
        currentInputConnection?.commitText(out, 1)
    }

    private fun backspace() {
        currentInputConnection?.deleteSurroundingText(1, 0)
    }

    private fun enter() {
        val ic = currentInputConnection ?: return
        val action = currentInputEditorInfo?.imeOptions?.and(android.view.inputmethod.EditorInfo.IME_MASK_ACTION) ?: 0
        when (action) {
            android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH -> ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH)
            android.view.inputmethod.EditorInfo.IME_ACTION_SEND -> ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_SEND)
            android.view.inputmethod.EditorInfo.IME_ACTION_GO -> ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_GO)
            android.view.inputmethod.EditorInfo.IME_ACTION_DONE -> ic.performEditorAction(android.view.inputmethod.EditorInfo.IME_ACTION_DONE)
            else -> sendDownUpKeyEvents(KeyEvent.KEYCODE_ENTER)
        }
    }

    // ==================== 指令面板 ====================
    private fun buildCommandPanel() {
        val density = resources.displayMetrics.density
        // Tab 栏
        val tabBar = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("快捷", "指令库", "收藏", "最近", "工具").forEachIndexed { i, name ->
            val tv = TextView(this).apply {
                text = name
                textSize = 12f
                gravity = Gravity.CENTER
                typeface = Typeface.DEFAULT_BOLD
                setPadding((4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt(), (4 * density).toInt())
                setOnClickListener { setTab(i) }
            }
            tabViews.add(tv)
            tabBar.addView(tv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        commandBox!!.addView(tabBar)

        // 搜索
        searchBox = EditText(this).apply {
            hint = "搜索指令…"
            setHintTextColor(Color.rgb(157, 180, 165))
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(34, 44, 52))
            inputType = InputType.TYPE_CLASS_TEXT
            textSize = 13f
            setPadding((8 * density).toInt(), (4 * density).toInt(), (8 * density).toInt(), (4 * density).toInt())
            addTextChangedListener(object : android.text.TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) { rebuildList() }
                override fun afterTextChanged(s: android.text.Editable?) {}
            })
        }
        commandBox!!.addView(searchBox)

        // 列表
        listContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        val scroll = ScrollView(this).apply { addView(listContainer) }
        commandBox!!.addView(scroll, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))

        // 工具按钮行
        val toolsRow = HorizontalScrollView(this)
        val tools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun toolBtn(label: String, cmd: String?) = Button(this).apply {
            text = label
            textSize = 12f
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.rgb(34, 44, 52))
            setOnClickListener {
                if (cmd != null) insertCommand(QuickCommand(label, cmd)) else {
                    startActivity(android.content.Intent(this@CyanCodexIme, MainActivity::class.java)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
                }
            }
        }
        tools.addView(toolBtn("打开App", null))
        tools.addView(toolBtn("传送到家", "/tp @s 0 64 0"))
        tools.addView(toolBtn("切换创造", "/gamemode creative @s"))
        tools.addView(toolBtn("切换生存", "/gamemode survival @s"))
        tools.addView(toolBtn("无敌", "/effect @s resistance 999999 255 true"))
        tools.addView(toolBtn("清掉落物", "/kill @e[type=item]"))
        tools.addView(toolBtn("管理快捷", null).also {
            it.setOnClickListener {
                startActivity(android.content.Intent(this@CyanCodexIme, CyanCodexImeSettings::class.java)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
        })
        toolsRow.addView(tools)
        commandBox!!.addView(toolsRow)

        setTab(0)
    }

    private fun setMode(m: Int) {
        if (m == 1) {
            modeBtn?.text = " 键盘"
            modeBtn?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_keyboard, 0, 0, 0)
            modeBtn?.compoundDrawablePadding = (3 * resources.displayMetrics.density).toInt()
            modeBtn?.setOnClickListener { setMode(0) }
            keyboardBox?.visibility = View.GONE
            commandBox?.visibility = View.VISIBLE
            setTab(tab)
        } else {
            modeBtn?.text = " 指令"
            modeBtn?.setCompoundDrawablesWithIntrinsicBounds(R.drawable.ic_bolt, 0, 0, 0)
            modeBtn?.compoundDrawablePadding = (3 * resources.displayMetrics.density).toInt()
            modeBtn?.setOnClickListener { setMode(1) }
            keyboardBox?.visibility = View.VISIBLE
            commandBox?.visibility = View.GONE
        }
    }

    private fun setTab(i: Int) {
        tab = i
        tabViews.forEachIndexed { idx, tv ->
            tv.setTextColor(if (idx == i) Color.rgb(74, 222, 128) else Color.rgb(157, 180, 165))
            tv.setBackgroundColor(if (idx == i) Color.rgb(34, 44, 52) else Color.TRANSPARENT)
        }
        if (i == 4) { rebuildList(); return }
        if (i == 1) {
            scope.launch {
                runCatching {
                    val r = ApiClient.get().commands(null, null, null, null, null, 1, 50)
                    if (r.ok) r.data?.list?.map { QuickCommand(it.title, it.command) } ?: emptyList()
                    else emptyList()
                }.onSuccess { libItems = it }
                rebuildList()
            }
        } else if (i == 2) {
            scope.launch {
                runCatching {
                    val r = ApiClient.get().favorites()
                    if (r.ok) r.data?.map { QuickCommand(it.title, it.command) } ?: emptyList()
                    else emptyList()
                }.onSuccess { favItems = it }
                rebuildList()
            }
        } else {
            rebuildList()
        }
    }

    private fun currentItems(): List<QuickCommand> {
        if (tab == 4) return emptyList()
        val kw = searchBox?.text?.toString()?.trim().orEmpty()
        val items = when (tab) {
            1 -> libItems
            2 -> favItems
            3 -> Session.recentCommands().map { QuickCommand("最近", it) }
            else -> {
                val list = mutableListOf<QuickCommand>()
                Session.quickCommands().forEach { list.add(it) }
                try {
                    val cached = Session.cachedFavorites
                    val type = object : TypeToken<List<QuickCommand>>() {}.type
                    gson.fromJson<List<QuickCommand>>(cached, type)?.forEach { list.add(it) }
                } catch (e: Exception) {}
                list
            }
        }
        return items.distinctBy { it.command }.filter {
            kw.isEmpty() || it.title.contains(kw) || it.command.contains(kw)
        }.take(30)
    }

    private fun rebuildList() {
        val container = listContainer ?: return
        val density = resources.displayMetrics.density
        container.removeAllViews()
        if (tab == 4) {
            container.addView(TextView(this).apply {
                text = "工具区: 上方按钮一键插入常用指令或打开 App"
                setTextColor(Color.rgb(157, 180, 165)); textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, (16 * density).toInt(), 0, (16 * density).toInt())
            })
            return
        }
        val items = currentItems()
        if (items.isEmpty()) {
            container.addView(TextView(this).apply {
                text = "暂无指令\n去 App 指令库收藏/加入快捷后自动同步"
                setTextColor(Color.rgb(157, 180, 165)); textSize = 12f; gravity = Gravity.CENTER
                setPadding(0, (20 * density).toInt(), 0, (20 * density).toInt())
            })
            return
        }
        items.forEach { qc ->
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundColor(Color.rgb(28, 36, 44))
                setPadding((10 * density).toInt(), (7 * density).toInt(), (10 * density).toInt(), (7 * density).toInt())
                setOnClickListener { insertCommand(qc) }
            }
            card.addView(TextView(this).apply {
                text = qc.title.ifBlank { "指令" }
                setTextColor(Color.rgb(251, 191, 36)); textSize = 13f; typeface = Typeface.DEFAULT_BOLD; maxLines = 1
            })
            card.addView(TextView(this).apply {
                text = qc.command
                setTextColor(Color.rgb(231, 242, 234)); textSize = 12f; typeface = Typeface.MONOSPACE; maxLines = 2
            })
            container.addView(card, LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { bottomMargin = (5 * density).toInt() })
        }
    }

    private fun insertCommand(qc: QuickCommand) {
        currentInputConnection?.commitText(qc.command + " ", 1)
        Session.addRecent(qc.command)
    }
}

/**
 * 输入法设置页: 管理快捷指令
 */
class CyanCodexImeSettings : Activity() {
    override fun onCreate(savedInstanceState: android.os.Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.rgb(20, 24, 30))
            setPadding((14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt(), (14 * density).toInt())
        }
        root.addView(TextView(this).apply {
            text = "CyanCodex 输入法设置"
            setTextColor(Color.rgb(74, 222, 128)); textSize = 18f; typeface = Typeface.DEFAULT_BOLD
        })
        root.addView(TextView(this).apply {
            text = "启用: 系统设置 → 语言与输入法 → 键盘列表 → 勾选 CyanCodex 指令输入法\n" +
                "使用: 顶部「指令」按钮切换指令面板, 一键插入指令; 再点「键盘」回到正常打字。"
            setTextColor(Color.rgb(157, 180, 165)); textSize = 13f
        })
        val listBox = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        root.addView(ScrollView(this).apply { addView(listBox) }, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f
        ).apply { topMargin = (10 * density).toInt() })

        fun refresh() {
            listBox.removeAllViews()
            val items = Session.quickCommands()
            if (items.isEmpty()) {
                listBox.addView(TextView(this).apply {
                    text = "暂无快捷指令"; setTextColor(Color.rgb(157, 180, 165))
                })
            }
            items.forEach { qc ->
                val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val cmdTv = TextView(this).apply {
                    text = qc.command; setTextColor(Color.WHITE); textSize = 13f
                    typeface = Typeface.MONOSPACE; maxLines = 2
                }
                row.addView(cmdTv, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                val delBtn = Button(this).apply {
                    text = "删除"; textSize = 12f; setBackgroundColor(Color.rgb(120, 30, 30)); setTextColor(Color.WHITE)
                    setOnClickListener { Session.removeQuickCommand(qc.command); refresh() }
                }
                row.addView(delBtn)
                listBox.addView(row, LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
                ).apply { bottomMargin = (8 * density).toInt() })
            }
        }
        refresh()

        setContentView(root)
    }
}
