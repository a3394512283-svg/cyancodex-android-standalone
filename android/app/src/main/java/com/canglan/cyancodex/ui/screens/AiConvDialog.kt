package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DeleteOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Forum
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Terminal
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.canglan.cyancodex.data.AiConv
import com.canglan.cyancodex.data.AiMode
import com.canglan.cyancodex.ui.LocalGlassBottomInset
import com.canglan.cyancodex.ui.components.OutlinedTextField2
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * 对话记录**侧边栏** (从左侧滑出的抽屉)。
 *
 * 2026-10-01 用户要求: "要改的是侧边栏, 就是历史聊天记录, 参考图2"。
 * 之前是居中的 AlertDialog —— 弹窗会把整页盖住, 看历史时看不到自己在哪条会话里,
 * 而且弹窗天生带"这是一次性操作"的语义, 对话列表其实是**常驻导航**。
 *
 * 布局参考 DeepSeek 客户端: 顶部搜索框 + 按「今天 / 昨天 / 7 天内 / 30 天内 / 更早」分组。
 * ⛔ 参考图底部那块**用户头像 / 昵称 / 更多**是 DeepSeek 的账号入口, 我们**不做** ——
 *    用户明确要求删掉 ("底下有用户头像啥的删掉就行了")。
 *
 * 组件本身无状态, 列表与当前 id 由 [AiScreen] 持有并落盘 ([com.canglan.cyancodex.ai.AiConvStore])。
 */
@Composable
fun AiConvDrawer(
    convs: List<AiConv>,
    currentId: String,
    onPick: (String) -> Unit,
    onNew: () -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onClose: () -> Unit,
) {
    var renaming by remember { mutableStateOf<AiConv?>(null) }
    var renameText by remember { mutableStateOf("") }
    var query by remember { mutableStateOf("") }
    val fmt = remember { SimpleDateFormat("MM-dd HH:mm", Locale.getDefault()) }

    // 搜索: 标题匹配 + 消息正文匹配 (只搜标题的话, 用户记得住内容却记不住标题时就没辙了)
    val shown = remember(convs, query) {
        val q = query.trim()
        if (q.isEmpty()) convs
        else convs.filter { c ->
            c.title.contains(q, ignoreCase = true) ||
                c.messages.any { it.content.contains(q, ignoreCase = true) }
        }
    }
    // 分组 (保持 list() 的 updatedAt 倒序, groupBy 保序)
    val groups = remember(shown) { shown.groupBy { groupLabel(it.updatedAt) } }

    // 300dp: 比 DrawerDefaults 的 360dp 窄一点, 右侧能露出一条主界面, 一眼看出"这是个侧栏"。
    ModalDrawerSheet(
        modifier = Modifier.width(300.dp),
        drawerContainerColor = MaterialTheme.colorScheme.surface,
    ) {
        // ★★ 抽屉内容整体上移「全局底栏高度」—— 2026-10-01 修掉的一个真 bug。
        //
        // 现象: 抽屉最底部的内容**点不到**。实测点它的坐标 (400,2285) 命中的是
        //       底栏的「广场」页签, 直接跳走了 —— 内容被底栏完整吞掉。
        //
        // 根因: 玻璃开启时 AppNav 会**去掉 NavHost 的底部内缩**(`navModifier = padding(top=…)`),
        //       让内容一路铺到屏幕底, 这样底栏玻璃下面才是真实内容而不是纯色(见 AppNav 的注释)。
        //       副作用: 各 Tab 页的根布局现在**伸到底栏下面**, 而 Scaffold 的 bottomBar 是画在
        //       内容**之上**的 —— 于是 AiScreen 里这个全高的 ModalNavigationDrawer 最后 ~287px
        //       被底栏盖住。列表项还能靠滚动看全, 但固定在最底部的那行就彻底没救了。
        //
        // 修法: 直接复用 AppNav 已经算好的 [LocalGlassBottomInset]。
        //       · 玻璃开启 + 当前是 Tab 页 → 它 = 底栏总高(含"宝贝回家"页脚) → 正好补回来
        //       · 玻璃关闭 → 它是 0.dp, 而那时 NavHost 走 `Modifier.padding(padding)` 本来就
        //         已经被内缩过, 抽屉压根没被盖 → 0 也是对的
        //       所以一个值同时覆盖两种情形, 不需要额外判断。
        // ⚠️ 只缩**内容**, 不缩 ModalDrawerSheet 本身 —— 白底仍要铺满整高, 否则底栏玻璃
        //    左边一半会透出壁纸, 跟右边一半对不上。
        Column(Modifier.fillMaxSize().padding(bottom = LocalGlassBottomInset.current)) {
        // ---- 标题行 ----
        Row(
            Modifier.fillMaxWidth().padding(start = 20.dp, end = 6.dp, top = 14.dp, bottom = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("对话记录", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onNew(); onClose() }, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Add, "新建对话",
                    tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onClose, modifier = Modifier.size(38.dp)) {
                Icon(Icons.Default.Close, "关闭",
                    tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
        }

        // ---- 搜索框 ----
        // ⚠️ OutlinedTextField2 没有 leadingIcon 参数 (见 Common.kt), 别加。
        OutlinedTextField2(
            value = query,
            onValueChange = { query = it },
            label = "搜索",
            placeholder = "搜标题或对话内容…",
            singleLine = true,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

        Spacer(Modifier.height(6.dp))

        // ---- 列表 ----
        if (convs.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("还没有对话", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else if (shown.isEmpty()) {
            Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                Text("没有匹配「$query」的对话", style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                groups.forEach { (label, list) ->
                    item(key = "h_$label") {
                        Text(
                            label,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 6.dp, top = 8.dp, bottom = 2.dp),
                        )
                    }
                    items(list, key = { it.id }) { c ->
                        ConvRow(
                            conv = c,
                            current = c.id == currentId,
                            subtitle = "${c.messages.size} 条 · ${fmt.format(Date(c.updatedAt))}",
                            onPick = { onPick(c.id) },
                            onRename = { renaming = c; renameText = c.title },
                            onDelete = { onDelete(c.id) },
                        )
                    }
                }
            }
        }
        }   // ← 闭合"给底栏让位"的 Column
    }

    // 重命名 (嵌套一个对话框 —— 比在列表里内联编辑干净得多)
    renaming?.let { c ->
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("重命名对话") },
            text = {
                OutlinedTextField2(renameText, { renameText = it }, "标题",
                    placeholder = "给这段对话起个名字")
            },
            confirmButton = {
                TextButton(onClick = {
                    onRename(c.id, renameText)
                    renaming = null
                }) { Text("保存") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("取消") } },
        )
    }
}

/**
 * 侧边栏里的一行会话。
 *
 * 长按/常显两个小图标 (重命名 / 删除) 直接摆在行尾 —— 抽屉里没有"更多"菜单的位置,
 * 而且这两个操作都不危险到需要二次确认。
 */
@Composable
private fun ConvRow(
    conv: AiConv,
    current: Boolean,
    subtitle: String,
    onPick: () -> Unit,
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = if (current) MaterialTheme.colorScheme.primaryContainer
        else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth().clickable { onPick() },
    ) {
        Row(
            Modifier.padding(start = 12.dp, end = 2.dp, top = 8.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    conv.title.ifBlank { "新对话" },
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            IconButton(onClick = onRename, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.Edit, "重命名",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onDelete, modifier = Modifier.size(32.dp)) {
                Icon(Icons.Default.DeleteOutline, "删除",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.error)
            }
        }
    }
}

/**
 * 时间分组标题 —— 与 DeepSeek 客户端的「昨天 / 7 天内 / 30 天内」一致, 另加「今天」「更早」。
 *
 * ⚠️ 必须按**自然日**算, 不能按 (now - ts) / 86400000 整除:
 * 今天 23:59 和昨天 00:01 只差 1 分钟, 整除会算成 0 天 → 昨天那条被归进「今天」。
 */
private fun groupLabel(ts: Long): String {
    val dayMs = 24 * 3600 * 1000L
    fun startOfDay(t: Long): Long = Calendar.getInstance().apply {
        timeInMillis = t
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis
    val days = ((startOfDay(System.currentTimeMillis()) - startOfDay(ts)) / dayMs).toInt()
    return when {
        days <= 0 -> "今天"
        days == 1 -> "昨天"
        days <= 7 -> "7 天内"
        days <= 30 -> "30 天内"
        else -> "更早"
    }
}

/**
 * **新建对话的模式选择** (2026-10-05)。
 *
 * 用户原话:「类似 WorkBuddy 一样有个**编写代码和检查语法**的选项在**新建对话的时候选择**」。
 *
 * ## 为什么是"建会话时选"而不是"会话中切"
 *
 * 模式决定这条会话里 AI **能做什么**(挂不挂 MC 百科、核不核语法), 属于"这条会话是什么"。
 * 这类**能力边界**和「深度思考 / 智能搜索」那种"随手可切的小开关"不是一回事 ——
 * 后者随会话进行切来切去无所谓, 前者是会话的**身份**。WorkBuddy 新建会话也是这个交互。
 *
 * ## 交互
 *
 * 点一行 → 立刻建会话并进入 (不要求"选中再确认" —— 就三个选项, 多一步是浪费)。
 * 取消 = 什么都不做 (不建空会话)。
 *
 * @param onPick    选好模式后回调, 参数是 [AiMode] 的取值
 * @param onDismiss 关掉面板 (不建会话)
 * @param title     面板标题。默认「新建对话」; **改当前会话模式**时传「切换会话模式」。
 * @param hint      标题下那行说明。默认是新建议语, 改模式时给另一种措辞。
 * @param current   当前已选中的模式 (2026-10-05)。非 null 时该行高亮打勾 ——
 *                  「改模式」场景必须让用户看得出**现在是什么**, 否则等于盲选。
 */
@Composable
fun AiModePicker(
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    title: String = "新建对话",
    hint: String = "先选这条对话要做什么，之后 AI 会按这个方式来帮你。",
    current: String? = null,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Column(Modifier.padding(18.dp)) {
                Text(title, style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(2.dp))
                Text(hint,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.height(14.dp))

                AiMode.ALL.forEach { m ->
                    ModeRow(
                        mode = m,
                        onClick = { onPick(m) },
                        selected = current != null && AiMode.of(current) == m,
                    )
                    Spacer(Modifier.height(8.dp))
                }

                Spacer(Modifier.height(4.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                }
            }
        }
    }
}

/** 模式选择面板里的一行: 图标 + 名称 + 说明，右侧箭头（选中时换成勾） */
@Composable
private fun ModeRow(mode: String, onClick: () -> Unit, selected: Boolean = false) {
    val (icon, tint) = when (mode) {
        AiMode.CHAT -> Icons.Default.Forum to MaterialTheme.colorScheme.secondary
        AiMode.LINT -> Icons.Default.FactCheck to MaterialTheme.colorScheme.tertiary
        else -> Icons.Default.Terminal to MaterialTheme.colorScheme.primary
    }
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.14f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(AiMode.label(mode), style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface)
                Text(AiMode.desc(mode), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (selected) {
                Icon(Icons.Default.Check, "当前模式",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(18.dp))
            } else {
                Icon(Icons.Default.ChevronRight, null,
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(18.dp))
            }
        }
    }
}
