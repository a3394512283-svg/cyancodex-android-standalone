package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Casino
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.data.GameCreateReq
import com.canglan.cyancodex.data.GameStateResp
import com.canglan.cyancodex.generators.GomokuEngine
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 五子棋: 人机对战(4难度) + 在线匹配(房间实时对战) + AI观战
 * 难度4 老叟戏顽童: 落子即时(本地困难引擎), 嘲讽由 Agnes 异步生成
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun GomokuScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()

    var difficulty by remember { mutableStateOf(2) }
    var mode by remember { mutableStateOf("ai") }          // ai / match / watch
    var board by remember { mutableStateOf(GomokuEngine.emptyBoard()) }
    var myTurn by remember { mutableStateOf(true) }
    var myPiece by remember { mutableStateOf(GomokuEngine.BLACK) }
    var gameOver by remember { mutableStateOf(false) }
    var winner by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var matched by remember { mutableStateOf(false) }
    var opponentName by remember { mutableStateOf("") }
    var moves by remember { mutableStateOf(0) }
    var taunt by remember { mutableStateOf("") }
    var godMove by remember { mutableStateOf("") }   // 神之一手特效文字 (意见#73)
    var watchSpeed by remember { mutableStateOf(400L) }
    var watching by remember { mutableStateOf(false) }
    // AI 观战双方强度 (意见#63): 1低 2中 3高
    var watchBlackLevel by remember { mutableStateOf(3) }
    var watchWhiteLevel by remember { mutableStateOf(2) }
    // 房间系统 (意见#64)
    var roomMode by remember { mutableStateOf(false) }
    var rooms by remember { mutableStateOf<List<com.canglan.cyancodex.data.GameRoomInfo>>(emptyList()) }
    var createType by remember { mutableStateOf("pvp") }
    var createAiLevel by remember { mutableStateOf(2) }
    var roomCodeInput by remember { mutableStateOf("") }
    var isOwner by remember { mutableStateOf(false) }
    var isSpectator by remember { mutableStateOf(false) }
    var roomType by remember { mutableStateOf("pvp") }
    var roomId by remember { mutableStateOf("") }
    var polling by remember { mutableStateOf(false) }
    // 房间列表筛选 (意见#76): all 全部 / pvp 真人 / ai AI房间
    var roomFilter by remember { mutableStateOf("all") }

    fun reset() {
        board = GomokuEngine.emptyBoard()
        myTurn = true
        myPiece = GomokuEngine.BLACK
        gameOver = false
        winner = ""
        busy = false
        msg = ""
        moves = 0
        taunt = ""
        godMove = ""
        watching = false
        polling = false
        roomId = ""
        isOwner = false
        isSpectator = false
        if (mode == "match") { matched = false; opponentName = "" }
        if (mode == "rooms") { rooms = emptyList(); roomMode = false }
    }

    fun endGame(w: String) {
        gameOver = true
        winner = w
        polling = false
        watching = false
    }

    /** 本地 AI 落子 (即时) */
    fun localAiMove(bd: Array<IntArray>, aiPiece: Int) {
        val p = GomokuEngine.aiMove(bd, difficulty, aiPiece, if (aiPiece == 1) 2 else 1)
        bd[p.x][p.y] = aiPiece
        board = bd
        moves++
        if (GomokuEngine.checkWin(bd, p.x, p.y, aiPiece)) endGame("AI 胜")
        else if (GomokuEngine.isFull(bd)) endGame("平局")
        else myTurn = true
    }

    /** 难度4/5: 落子即时(本地困难/宗师引擎), 嘲讽由本地语句池生成 (免费即时) */
    fun tauntMove(bd: Array<IntArray>, aiPiece: Int) {
        // 立即用本地引擎落子 (难度4用3级引擎, 难度5用4级宗师引擎)
        val lv = if (difficulty >= 5) 4 else 3
        val p = GomokuEngine.aiMove(bd, lv, aiPiece, if (aiPiece == 1) 2 else 1)
        bd[p.x][p.y] = aiPiece
        board = bd
        moves++
        taunt = "老头子我落子如飞，看招!"
        if (GomokuEngine.checkWin(bd, p.x, p.y, aiPiece)) endGame("老叟胜")
        else if (GomokuEngine.isFull(bd)) endGame("平局")
        else myTurn = true

        // #87/#88 修复: 嘲讽改为本地语句池随机生成 (不调用计费 AI, 不再每步扣积分)
        val pool = arrayOf(
            "老头子这步棋，够你琢磨半天了！",
            "年轻人，棋艺还要多练练啊！",
            "落子无悔，可别哭鼻子哦～",
            "此局老夫志在必得！",
            "嘿嘿，这招你没想到吧？",
            "老夫观棋数十载，你这套路我见多了！",
            "别急，慢慢想，老夫喝口茶等你。",
            "这一子，可是给你上了宝贵一课！",
            "棋盘如人生，输赢皆浮云，但你赢不了我！",
            "仔细看好了，这步才是精髓！",
            "小伙子，棋路还嫩着呢！",
            "老夫手抖了一下，你就当没看见这步好棋！",
            "山外有山，人外有人，棋外有老夫！",
            "你尽管落子，能赢算我输！",
            "此子一落，胜负已分七分！",
        )
        // 根据局面选择更贴合的语气 (简单判断己方棋子数)
        val ownCount = bd.sumOf { row -> row.count { it == aiPiece } }
        val rivalCount = bd.sumOf { row -> row.count { it != 0 && it != aiPiece } }
        val idx = if (ownCount < rivalCount) (Math.random() * 5).toInt() + 10  // 落后时更"嘴硬"
                  else (Math.random() * 10).toInt()                            // 领先/均势正常嘲讽
        taunt = pool[idx.coerceIn(0, pool.size - 1)]
    }

    /** 应用服务器状态到本地 */
    fun applyState(s: GameStateResp) {        val b = s.board
        if (b != null && b.size == 15 && b[0].size == 15) {
            board = Array(15) { i -> IntArray(15) { j -> b[i][j] } }
        }
        moves = s.moves
        if (s.over) {
            endGame(if (s.winner == "平局") "平局" else "胜负已分: ${s.winner}")
        } else {
            myTurn = s.turn == myPiece
            if (s.turn == myPiece) msg = "轮到你落子" else msg = "等待对手落子…"
        }
    }

    /** 玩家落子 (人机/匹配) */
    fun playerMove(x: Int, y: Int) {
        if (gameOver || !myTurn || busy) return
        if (mode == "watch") { msg = "观战模式不能落子"; return }
        val bd = GomokuEngine.copyBoard(board)
        if (bd[x][y] != 0) return

        // 神之一手 (意见#73): 老叟/顽童难度下, 玩家即将连成五子时 AI 有概率发动技能
        // 把玩家必赢的那一步变成 AI 的子, 屏幕蹦出金色"神之一手"
        if (mode == "ai" && difficulty >= 4) {
            val probe = GomokuEngine.copyBoard(bd)
            probe[x][y] = myPiece
            if (GomokuEngine.checkWin(probe, x, y, myPiece)) {
                val aiPiece = if (myPiece == GomokuEngine.BLACK) GomokuEngine.WHITE else GomokuEngine.BLACK
                val chance = if (difficulty >= 5) 0.6 else 0.4
                if (Math.random() < chance) {
                    bd[x][y] = aiPiece
                    board = bd
                    moves++
                    myTurn = false
                    val lines = arrayOf(
                        "神之一手！此子归老夫了！",
                        "神之一手！老夫早已看穿！",
                        "神之一手！年轻人，还差得远！",
                        "神之一手！这步棋，老夫收了！",
                    )
                    godMove = lines[(Math.random() * lines.size).toInt().coerceIn(0, lines.size - 1)]
                    scope.launch { delay(1800); godMove = "" }
                    if (GomokuEngine.checkWin(bd, x, y, aiPiece)) { endGame("老叟胜"); return }
                    if (GomokuEngine.isFull(bd)) { endGame("平局"); return }
                    return
                }
            }
        }

        bd[x][y] = myPiece
        board = bd
        moves++
        if (GomokuEngine.checkWin(bd, x, y, myPiece)) { endGame("你赢了!"); return }
        if (GomokuEngine.isFull(bd)) { endGame("平局"); return }
        myTurn = false

        if (mode == "match" && matched && roomId.isNotBlank()) {
            // 匹配模式: 落子同步到服务器
            scope.launch {
                busy = true
                val r = safeApi { ApiClient.get().gameMove(roomId, mapOf("x" to x, "y" to y)) }
                busy = false
                if (r.ok && r.data != null) {
                    applyState(r.data)
                } else {
                    msg = r.error ?: "落子同步失败"
                }
            }
        } else {
            // 人机: AI 回合
            val aiPiece = if (myPiece == GomokuEngine.BLACK) GomokuEngine.WHITE else GomokuEngine.BLACK
            if (difficulty >= 4) tauntMove(GomokuEngine.copyBoard(bd), aiPiece)
            else localAiMove(GomokuEngine.copyBoard(bd), aiPiece)
        }
    }

    /** 匹配模式: 轮询对手落子 */
    fun startPolling() {
        if (polling || roomId.isBlank()) return
        polling = true
        scope.launch {
            while (polling && scope.coroutineContext.isActive) {
                delay(1500)
                if (!polling) break
                val r = safeApi { ApiClient.get().gameState(roomId) }
                if (r.ok && r.data != null) {
                    applyState(r.data)
                    if (r.data.over) break
                } else if (r.error?.contains("不存在") == true || r.error?.contains("已结束") == true) {
                    endGame("对手已离开")
                    break
                }
            }
            polling = false
        }
    }

    // ===== 房间系统 (意见#64) =====
    /** AI 房间: 轮询 + 轮到 AI 时请求 ai-move */
    fun startAiRoom() {
        if (polling) return
        polling = true
        scope.launch {
            while (polling && scope.coroutineContext.isActive) {
                delay(1500)
                if (!polling) break
                val r = safeApi { ApiClient.get().gameState(roomId) }
                if (r.ok && r.data != null) {
                    val s = r.data
                    val b = s.board
                    if (b != null && b.size == 15 && b[0].size == 15) {
                        board = Array(15) { i -> IntArray(15) { j -> b[i][j] } }
                    }
                    moves = s.moves
                    if (s.over) { endGame(if (s.winner == "平局") "平局" else "胜负已分: ${s.winner}"); break }
                    val my = s.myPiece
                    if (s.turn != my) {
                        val am = safeApi { ApiClient.get().gameAiMove(roomId) }
                        if (am.ok && am.data != null) {
                            val b2 = am.data.board
                            if (b2 != null && b2.size == 15 && b2[0].size == 15) {
                                board = Array(15) { i -> IntArray(15) { j -> b2[i][j] } }
                            }
                            moves = am.data.moves
                            if (am.data.over) {
                                endGame(if (am.data.winner == "平局") "平局" else "胜负已分: ${am.data.winner}")
                                break
                            }
                        }
                    } else {
                        myTurn = true
                    }
                } else if (r.error?.contains("不存在") == true || r.error?.contains("已结束") == true) {
                    endGame("房间已关闭")
                    break
                }
            }
            polling = false
        }
    }

    fun refreshRooms() {
        scope.launch {
            val r = safeApi { ApiClient.get().gameRooms() }
            if (r.ok) {
                // 真人(pvp)房间排前面, 避免被 AI 房间刷屏 (意见#76)
                rooms = (r.data ?: emptyList()).sortedByDescending { if (it.type == "pvp") 1 else 0 }
            }
        }
    }

    fun createRoom() {
        scope.launch {
            busy = true
            val r = safeApi {
                ApiClient.get().gameCreateRoom(GameCreateReq(
                    type = createType, aiLevel = createAiLevel, isPublic = true))
            }
            busy = false
            if (r.ok && r.data != null) {
                val d = r.data
                roomId = d.roomId
                roomMode = true
                isOwner = true
                isSpectator = false
                roomType = d.type
                myPiece = 1
                myTurn = true
                matched = true
                board = GomokuEngine.emptyBoard()
                gameOver = false
                moves = 0
                msg = "房间 ${d.code} 已创建（${if (d.type == "ai") "AI 房间·难度" + d.aiLevel else "对战房间"}）"
                if (d.type == "ai") startAiRoom()
                else startPolling()
            } else msg = r.error ?: "创建失败"
        }
    }

    fun joinRoomByCode() {
        val code = roomCodeInput.trim()
        if (code.isEmpty()) { msg = "请输入房间号"; return }
        scope.launch {
            busy = true
            val r = safeApi { ApiClient.get().gameJoinRoom(mapOf("code" to code)) }
            busy = false
            if (r.ok && r.data != null) {
                val d = r.data
                roomId = d.roomId
                roomMode = true
                isOwner = false
                isSpectator = d.spectator
                roomType = d.type
                matched = true
                board = GomokuEngine.emptyBoard()
                gameOver = false
                moves = 0
                if (d.spectator) {
                    msg = "已加入房间 ${d.code} 观战"
                    startPolling()
                } else {
                    myPiece = d.player?.piece ?: 2
                    myTurn = myPiece == 1
                    msg = "已加入房间 ${d.code}，你执${if (myPiece == 1) "黑" else "白"}"
                    if (d.type == "ai") startAiRoom()
                    else startPolling()
                }
            } else msg = r.error ?: "加入失败"
        }
    }

    fun joinRoom(room: com.canglan.cyancodex.data.GameRoomInfo, asSpectator: Boolean = false) {
        scope.launch {
            busy = true
            val r = safeApi {
                ApiClient.get().gameJoinRoom(
                    mapOf("room_id" to room.roomId, "spectator" to if (asSpectator) "true" else "false"))
            }
            busy = false
            if (r.ok && r.data != null) {
                val d = r.data
                roomId = d.roomId
                roomMode = true
                isOwner = false
                isSpectator = d.spectator
                roomType = d.type
                matched = true
                board = GomokuEngine.emptyBoard()
                gameOver = false
                moves = 0
                if (d.spectator) {
                    msg = "已加入房间 ${d.code} 观战"
                    startPolling()
                } else {
                    myPiece = d.player?.piece ?: 2
                    myTurn = myPiece == 1
                    msg = "已加入房间 ${d.code}，你执${if (myPiece == 1) "黑" else "白"}"
                    if (d.type == "ai") startAiRoom()
                    else startPolling()
                }
            } else msg = r.error ?: "加入失败"
        }
    }

    fun kickPlayer(room: com.canglan.cyancodex.data.GameRoomInfo, targetId: Long) {
        scope.launch {
            val r = safeApi { ApiClient.get().gameKick(room.roomId, mapOf("target_id" to targetId)) }
            msg = if (r.ok) "已踢出该玩家" else (r.error ?: "踢人失败")
            refreshRooms()
        }
    }

    /** 开始匹配 (60秒无对手切普通 AI) */
    fun startMatch() {
        scope.launch {
            busy = true
            msg = "正在匹配对手…(60秒无对手将切换普通AI)"
            val r = safeApi { ApiClient.get().gameMatch(mapOf("game" to "gomoku")) }
            busy = false
            if (r.ok && r.data != null) {
                val d = r.data
                if (d.matched) {
                    matched = true
                    roomId = d.roomId
                    opponentName = d.opponent?.nickname ?: "对手"
                    myPiece = d.player?.piece ?: GomokuEngine.BLACK
                    myTurn = myPiece == GomokuEngine.BLACK
                    msg = "已匹配到 $opponentName，你执${if (myPiece == 1) "黑" else "白"}${if (myTurn) "，请落子" else "，等对方先手"}"
                    startPolling()
                } else {
                    // 等待撮合: 轮询 status (先匹配者被撮合后, 通过 status 拿到房间)
                    msg = "匹配中…(60秒无对手将切换普通AI)"
                    var gotMatch = false
                    for (i in 0 until 30) {
                        delay(2000)
                        val st = safeApi { ApiClient.get().gameMatchStatus() }
                        if (st.ok && st.data != null && st.data.matched) {
                            val d2 = st.data
                            matched = true
                            roomId = d2.roomId
                            opponentName = d2.opponent?.nickname ?: "对手"
                            myPiece = d2.player?.piece ?: GomokuEngine.BLACK
                            myTurn = myPiece == GomokuEngine.BLACK
                            msg = "已匹配到 $opponentName，你执${if (myPiece == 1) "黑" else "白"}${if (myTurn) "，请落子" else "，等对方先手"}"
                            startPolling()
                            gotMatch = true
                            break
                        }
                    }
                    if (!gotMatch) {
                        matched = false
                        mode = "ai"
                        difficulty = 2
                        msg = "未匹配到对手，已切换普通模式 AI（难度：普通）"
                    }
                }
            } else {
                msg = r.error ?: "匹配失败"
            }
        }
    }

    /** AI 观战: 两个 AI 自动对弈, 落子间隔 1-3s (取决于棋盘胜负率: 越胶着越久) */
    fun watchStep(bd: Array<IntArray>, current: Int, other: Int, step: Int) {
        val level = if (current == 1) watchBlackLevel else watchWhiteLevel
        val p = GomokuEngine.aiMove(bd, level, current, other)
        bd[p.x][p.y] = current
        board = bd
        moves = step
        if (GomokuEngine.checkWin(bd, p.x, p.y, current)) {
            endGame(if (current == 1) "黑方 AI 胜" else "白方 AI 胜")
        } else if (GomokuEngine.isFull(bd)) {
            endGame("平局")
        } else if (watching) {
            // 根据盘面紧张度计算间隔: 棋子越接近五连局势越胶着 -> 间隔越长 (1s~3s)
            val stones = bd.sumOf { row -> row.count { it != 0 } }
            val tension = stones.coerceIn(0, 200) / 200f   // 0~1
            val baseDelay = 1000L + (tension * 2000L).toLong()  // 1000~3000ms
            val delayMs = (baseDelay * 0.5f + watchSpeed * 0.5f).toLong()
            scope.launch {
                delay(delayMs)
                if (watching && !gameOver) watchStep(GomokuEngine.copyBoard(bd), other, current, step + 1)
            }
        }
    }

    fun startWatch() {
        if (watching) return
        board = GomokuEngine.emptyBoard()
        gameOver = false
        winner = ""
        moves = 0
        watching = true
        scope.launch {
            delay(300)
            if (watching) watchStep(GomokuEngine.copyBoard(board), 1, 2, 1)
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "五子棋", icon = Icons.Default.Casino, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                GlassFilterChip(selected = mode == "ai", onClick = { mode = "ai"; reset() }, label = { Text("人机对战") })
                GlassFilterChip(selected = mode == "match", onClick = { mode = "match"; reset() }, label = { Text("匹配对手") })
                GlassFilterChip(selected = mode == "rooms", onClick = { mode = "rooms"; reset(); refreshRooms() }, label = { Text("房间大厅") })
                GlassFilterChip(selected = mode == "watch", onClick = { mode = "watch"; reset() }, label = { Text("AI观战") })
            }
            if (mode == "ai") {
                Text("难度", style = MaterialTheme.typography.labelMedium)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    listOf(1 to "简单", 2 to "普通", 3 to "困难", 4 to "老叟戏顽童", 5 to "顽童宗师").forEach { (lv, name) ->
                        GlassFilterChip(
                            selected = difficulty == lv,
                            onClick = { difficulty = lv; reset() },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
                if (difficulty >= 4) {
                    Text("老叟戏顽童：落子如飞，边下边嘲讽你，还会发动【神之一手】抢走你的必赢棋",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error)
                }
            }

            // ===== 房间大厅 (意见#64) =====
            if (mode == "rooms" && !roomMode) {
                Text("公开房间", style = MaterialTheme.typography.titleSmall)
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(value = roomCodeInput, onValueChange = { roomCodeInput = it },
                        label = { Text("房间号") }, singleLine = true, modifier = Modifier.weight(1f))
                    GlassActionButton(filled = true, onClick = { joinRoomByCode() }) { Text("按号加入") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassFilterChip(selected = createType == "pvp", onClick = { createType = "pvp" }, label = { Text("对战房间") })
                    GlassFilterChip(selected = createType == "ai", onClick = { createType = "ai" }, label = { Text("AI房间") })
                    if (createType == "ai") {
                        listOf(1 to "易", 2 to "中", 3 to "难").forEach { (lv, name) ->
                            GlassFilterChip(selected = createAiLevel == lv, onClick = { createAiLevel = lv },
                                label = { Text(name, style = MaterialTheme.typography.labelSmall) })
                        }
                    }
                    GlassActionButton(filled = true, onClick = { createRoom() }) { Text("创建房间") }
                }
                GlassActionButton(onClick = { refreshRooms() }, modifier = Modifier.fillMaxWidth()) { Text("刷新房间列表") }
                // 房间筛选 (意见#76): 真人/AI/全部
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("筛选", style = MaterialTheme.typography.labelMedium)
                    listOf("all" to "全部", "pvp" to "真人", "ai" to "AI").forEach { (k, name) ->
                        GlassFilterChip(selected = roomFilter == k, onClick = { roomFilter = k },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                val filtered = rooms.filter { roomFilter == "all" || it.type == roomFilter }
                if (filtered.isEmpty()) {
                    Text("暂无公开房间，创建或刷新试试", style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                filtered.forEach { r ->
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                        modifier = Modifier.fillMaxWidth().clickable { joinRoom(r) }) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Column(Modifier.weight(1f)) {
                                Text("房间 ${r.code} · ${if (r.type == "ai") "AI(难度${r.aiLevel})" else "真人对战"}",
                                    style = MaterialTheme.typography.titleSmall)
                                Text("玩家 ${r.players?.size ?: 0}/2 · 观战 ${r.spectatorCount} · 手数 ${r.moves}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            // 观战按钮 (意见#77): 想观战的可直接以观战者身份进入, 不占玩家位
                            GlassActionButton(onClick = { joinRoom(r, asSpectator = true) },
                                modifier = Modifier.padding(start = 6.dp)) {
                                Text("观战", style = MaterialTheme.typography.labelSmall)
                            }
                            val myUid = AppState.user?.id
                            if (myUid != null && r.owner == myUid) {
                                val target = r.players?.firstOrNull { it.id != myUid }
                                if (target != null) {
                                    TextButton(onClick = { kickPlayer(r, target.id) }) {
                                        Text("踢人", color = MaterialTheme.colorScheme.error)
                                    }
                                }
                            }
                        }
                    }
                }
            } else if (mode == "rooms" && roomMode) {
                // 房间内: 显示房间号和房主操作
                Text("房间 ${roomCodeInput.ifBlank { "进行中" }}", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }

            Text(
                when {
                    gameOver -> "对局结束: $winner"
                    mode == "watch" -> "AI 观战：黑方(强度$watchBlackLevel) vs 白方(强度$watchWhiteLevel) · 第 ${moves + 1} 手"
                    mode == "match" && matched -> "${if (myTurn) "轮到你" else "等待 $opponentName…"} · 第 ${moves + 1} 手"
                    else -> "轮到: ${if (myTurn) "你" else "AI/对手"} · 第 ${moves + 1} 手"
                },
                style = MaterialTheme.typography.titleMedium,
                color = if (gameOver) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (msg.isNotBlank()) {
                Text(msg, style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (godMove.isNotBlank()) {
                Text(godMove,
                    style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                    color = Color(0xFFFFC107),
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center)
            }
            if (taunt.isNotBlank() && (difficulty == 4 || difficulty == 5 || mode == "watch")) {
                Text(taunt, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.fillMaxWidth())
            }

            // 棋盘: 自适应宽度正方形, 铺满屏幕宽度, 无底部多余
            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .background(Color(0xFFD9B87A), RoundedCornerShape(8.dp))
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            if (mode == "match" && !matched) { msg = "请先匹配对手"; return@detectTapGestures }
                            val stepPx = size.width / 15f
                            val x = (offset.x / stepPx).toInt().coerceIn(0, 14)
                            val y = (offset.y / stepPx).toInt().coerceIn(0, 14)
                            playerMove(x, y)
                        }
                    },
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val step = size.width / 15f
                    val half = step / 2f
                    for (i in 0 until 15) {
                        drawLine(Color(0xFF8B6B3E), Offset(half, half + i * step), Offset(half + 14 * step, half + i * step), 1.5f)
                        drawLine(Color(0xFF8B6B3E), Offset(half + i * step, half), Offset(half + i * step, half + 14 * step), 1.5f)
                    }
                    listOf(3 to 3, 11 to 3, 3 to 11, 11 to 11, 7 to 7).forEach { (sx, sy) ->
                        drawCircle(Color(0xFF8B6B3E), step * 0.08f, Offset(half + sx * step, half + sy * step))
                    }
                    for (i in 0 until 15) for (j in 0 until 15) {
                        if (board[i][j] != 0) {
                            val c = if (board[i][j] == 1) Color(0xFF1A1A1A) else Color(0xFFF5F5F5)
                            drawCircle(c, step * 0.42f, Offset(half + i * step, half + j * step))
                        }
                    }
                }
            }

            if (mode == "watch") {
                // 意见 #92: 观战控制按钮原来全挤在一行, 屏幕窄时后面的按钮被挤到屏幕外点不到;
                // 这里拆成多行 + 每行可横向滑动, 保证任何机型都能点到
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    GlassActionButton(filled = true, onClick = { if (watching) { watching = false } else startWatch() }) {
                        Text(if (watching) "暂停" else "开始观战")
                    }
                    GlassActionButton(onClick = { watching = false; reset() }) { Text("重新开始") }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("速度", style = MaterialTheme.typography.labelMedium)
                    listOf(150L to "快", 400L to "中", 800L to "慢").forEach { (ms, name) ->
                        GlassFilterChip(selected = watchSpeed == ms, onClick = { watchSpeed = ms },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                // 双方 AI 强度 (意见#63)
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("黑方", style = MaterialTheme.typography.labelMedium)
                    listOf(1 to "低", 2 to "中", 3 to "高").forEach { (lv, name) ->
                        GlassFilterChip(selected = watchBlackLevel == lv, onClick = { watchBlackLevel = lv },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) })
                    }
                }
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("白方", style = MaterialTheme.typography.labelMedium)
                    listOf(1 to "低", 2 to "中", 3 to "高").forEach { (lv, name) ->
                        GlassFilterChip(selected = watchWhiteLevel == lv, onClick = { watchWhiteLevel = lv },
                            label = { Text(name, style = MaterialTheme.typography.labelSmall) })
                    }
                }
            } else {
                Row(
                    Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    GlassActionButton(filled = true, onClick = {
                        if (mode == "match" && !matched) startMatch() else reset()
                    }) {
                        Text(if (mode == "match" && !matched) "开始匹配" else if (gameOver) "再来一局" else "重新开始")
                    }
                    if (mode == "match" && !matched) {
                        GlassActionButton(onClick = { reset() }) { Text("放弃匹配") }
                    }
                    if (mode == "match" && matched) {
                        GlassActionButton(onClick = {
                            scope.launch { runCatching { ApiClient.get().gameLeave(roomId) } }
                            reset()
                        }) { Text("退出对局") }
                    }
                }
            }
        }
    }
}
