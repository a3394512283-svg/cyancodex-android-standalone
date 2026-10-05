package com.canglan.cyancodex.generators

/**
 * 五子棋引擎 (本地 AI + 胜负判断)
 * 难度: 1=简单(随机+基础) 2=普通(启发式评估) 3=困难(带搜索的评估)
 * 棋盘: 15x15, 0=空 1=黑 2=白
 */
object GomokuEngine {
    const val SIZE = 15
    const val EMPTY = 0
    const val BLACK = 1
    const val WHITE = 2

    data class Pos(val x: Int, val y: Int)

    /** 检查 (x,y) 是否在棋盘内 */
    fun inBoard(x: Int, y: Int) = x in 0 until SIZE && y in 0 until SIZE

    /** 判断指定玩家是否在 (x,y) 落子后获胜 */
    fun checkWin(board: Array<IntArray>, x: Int, y: Int, player: Int): Boolean {
        val dirs = arrayOf(
            intArrayOf(1, 0), intArrayOf(0, 1),
            intArrayOf(1, 1), intArrayOf(1, -1),
        )
        for (d in dirs) {
            var count = 1
            // 正方向
            var nx = x + d[0]; var ny = y + d[1]
            while (inBoard(nx, ny) && board[nx][ny] == player) { count++; nx += d[0]; ny += d[1] }
            // 反方向
            nx = x - d[0]; ny = y - d[1]
            while (inBoard(nx, ny) && board[nx][ny] == player) { count++; nx -= d[0]; ny -= d[1] }
            if (count >= 5) return true
        }
        return false
    }

    /** 所有空位 */
    private fun emptyCells(board: Array<IntArray>): List<Pos> {
        val list = mutableListOf<Pos>()
        for (i in 0 until SIZE) for (j in 0 until SIZE) if (board[i][j] == EMPTY) list.add(Pos(i, j))
        return list
    }

    /** 评估某个位置对某玩家的得分 (四方向连子/活三/冲四等) */
    private fun scoreAt(board: Array<IntArray>, x: Int, y: Int, player: Int): Int {
        var score = 0
        val dirs = arrayOf(
            intArrayOf(1, 0), intArrayOf(0, 1),
            intArrayOf(1, 1), intArrayOf(1, -1),
        )
        for (d in dirs) {
            // 统计该方向连子数
            var count = 1
            var openEnds = 0
            var nx = x + d[0]; var ny = y + d[1]
            while (inBoard(nx, ny) && board[nx][ny] == player) { count++; nx += d[0]; ny += d[1] }
            if (inBoard(nx, ny) && board[nx][ny] == EMPTY) openEnds++
            nx = x - d[0]; ny = y - d[1]
            while (inBoard(nx, ny) && board[nx][ny] == player) { count++; nx -= d[0]; ny -= d[1] }
            if (inBoard(nx, ny) && board[nx][ny] == EMPTY) openEnds++

            // 计分: 五连/活四/冲四/活三... (权重拉开, 让 AI 优先做活三/堵活三)
            score += when {
                count >= 5 -> 1000000
                count == 4 && openEnds == 2 -> 100000
                count == 4 && openEnds == 1 -> 10000
                count == 3 && openEnds == 2 -> 5000
                count == 3 && openEnds == 1 -> 800
                count == 2 && openEnds == 2 -> 200
                count == 2 && openEnds == 1 -> 50
                count == 1 && openEnds == 2 -> 10
                else -> count
            }
        }
        return score
    }

    const val WIN_SCORE = 500000    // 五连 (>= 即必胜)
    const val FOUR_SCORE = 10000    // 冲四/活四 (>= 即需堵)

    /**
     * AI 落子
     * @param level 1=简单 2=普通 3=困难
     * @param me AI 方棋子
     * @param opponent 对手棋子
     */
    fun aiMove(board: Array<IntArray>, level: Int, me: Int, opponent: Int): Pos {
        val empties = emptyCells(board)
        if (empties.isEmpty()) return Pos(SIZE / 2, SIZE / 2)
        // 第一步下中心
        if (empties.size == SIZE * SIZE) return Pos(SIZE / 2, SIZE / 2)

        if (level == 1) {
            // 简单: 25% 随机, 75% 启发式
            if (Math.random() < 0.25) return empties[(Math.random() * empties.size).toInt().coerceIn(0, empties.size - 1)]
        }

        val candidates = empties.filter { hasNeighbor(board, it) }.ifEmpty { empties }

        // ===== 1. 必胜检测: 我方下一步能成五 -> 直接走 =====
        if (level >= 2) {
            for (p in candidates) {
                if (scoreAt(board, p.x, p.y, me) >= WIN_SCORE) return p
            }
            // 2. 必防检测: 对方下一步能成五 -> 必须堵 (活四/冲四)
            if (level >= 2) {
                var blockPos: Pos? = null
                var blockScore = 0
                for (p in candidates) {
                    val s = scoreAt(board, p.x, p.y, opponent)
                    if (s >= WIN_SCORE) return p          // 对手能成五, 立刻堵
                    if (s >= FOUR_SCORE && s > blockScore) { blockScore = s; blockPos = p }
                }
                // 对手有活四/冲四, 优先堵 (除非我方有必胜已处理)
                if (blockPos != null) return blockPos!!
            }
        }

        var bestPos = candidates[0]
        var bestScore = Double.MIN_VALUE
        for (p in candidates) {
            val attack = scoreAt(board, p.x, p.y, me).toDouble()
            val defend = scoreAt(board, p.x, p.y, opponent).toDouble()
            // 防守权重随难度提升: 困难更重视堵
            val defW = if (level == 3) 1.2 else 0.9
            val total = attack + defend * defW
            if (total > bestScore) {
                bestScore = total
                bestPos = p
            }
        }

        // ===== 难度4 (顽童宗师): 前瞻一层搜索, 想一步对手的最佳应对 =====
        if (level >= 4) {
            var lookPos = bestPos
            var lookScore = Double.MIN_VALUE
            val lookCands = candidates.take(48)
            for (p in lookCands) {
                val b2 = copyBoard(board)
                b2[p.x][p.y] = me
                val attack = scoreAt(b2, p.x, p.y, me).toDouble()
                // 对手在我落子后的最佳应对 (取前 24 个候选)
                var worst = 0.0
                var count = 0
                for (q in emptyCells(b2)) {
                    if (!hasNeighbor(b2, q)) continue
                    val s = scoreAt(b2, q.x, q.y, opponent).toDouble()
                    if (s > worst) worst = s
                    if (++count >= 24) break
                }
                val v = attack + worst * 0.85
                if (v > lookScore) { lookScore = v; lookPos = p }
            }
            return lookPos
        }
        return bestPos
    }

    private fun hasNeighbor(board: Array<IntArray>, p: Pos): Boolean {
        for (dx in -1..1) for (dy in -1..1) {
            if (dx == 0 && dy == 0) continue
            val nx = p.x + dx; val ny = p.y + dy
            if (inBoard(nx, ny) && board[nx][ny] != EMPTY) return true
        }
        return false
    }

    /** 棋盘是否已满 (平局) */
    fun isFull(board: Array<IntArray>): Boolean = emptyCells(board).isEmpty()

    /** 深拷贝棋盘 */
    fun copyBoard(b: Array<IntArray>): Array<IntArray> = Array(SIZE) { b[it].copyOf() }

    /** 评估某位置对某玩家的得分 (供外部校验, 如 Agnes 建议质量) */
    fun aiScoreOf(board: Array<IntArray>, x: Int, y: Int, player: Int): Int {
        if (!inBoard(x, y) || board[x][y] != EMPTY) return -1
        return scoreAt(board, x, y, player)
    }

    /** 构造空棋盘 */
    fun emptyBoard(): Array<IntArray> = Array(SIZE) { IntArray(SIZE) }
}
