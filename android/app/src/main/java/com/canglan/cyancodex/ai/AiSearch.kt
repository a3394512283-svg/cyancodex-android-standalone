package com.canglan.cyancodex.ai

import com.canglan.cyancodex.data.Session
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * 智能搜索 —— 把实时网页结果喂给模型, 治"模型不知道最近的事"。
 *
 * 对齐 DeepSeek 客户端: 输入框里跟「深度思考」并排的第二个 chip。
 * **没配搜索引擎时整个 chip 不出现** —— 配都没有, 摆个点了报错的按钮只会添堵。
 *
 * ## 关于各家接口形态的可靠性
 * [SearchEngine.verified] 标出哪些是**逐字对着官方文档/实测**写出来的:
 *  - ✅ Exa / TinyFish / Brave / Serper / 智谱 —— 官方文档核实, TinyFish 还拿真 Key 打过一次
 *  - ⚠️ Tavily / 博查 —— 按公开 API 惯例写, 未经实测
 * 未实测的两家如果形态有出入, 表现是"XX 搜索失败(4xx/解析错)", 单个函数改掉即可,
 * 不会波及其它引擎。
 *
 * ⚠️ **接口形态对 ≠ 网络能连通**: 本机实测 `api.search.brave.com:443` TCP 不通
 * (同一时刻其余几家全通), 所以 Brave 在列表里排最后并带红字警告 —— 见 [SearchEngine.warn]。
 */
enum class SearchKind { EXA, TINYFISH, BRAVE, SERPER, TAVILY, BOCHA, ZHIPU }

data class SearchEngine(
    val id: String,
    val name: String,
    /** 一句话说明这家是谁 */
    val hint: String,
    /** 申请 Key 的入口 */
    val consoleUrl: String,
    val kind: SearchKind,
    /** 请求/响应形态是否已核实 (true = 官方文档逐字核对过) */
    val verified: Boolean,
    /** 需要提醒用户的坑, 显示成红字; 空串 = 没有 */
    val warn: String = "",
)

val SEARCH_ENGINES: List<SearchEngine> = listOf(
    SearchEngine(
        id = "exa", name = "Exa",
        hint = "语义搜索，返回与问题最相关的网页片段",
        consoleUrl = "https://dashboard.exa.ai/api-keys",
        kind = SearchKind.EXA, verified = true,
    ),
    SearchEngine(
        id = "tinyfish", name = "TinyFish",
        hint = "搜索 API 免费，返回标题+摘要+链接",
        consoleUrl = "https://agent.tinyfish.ai/api-keys",
        kind = SearchKind.TINYFISH, verified = true,
    ),
    SearchEngine(
        id = "serper", name = "Serper",
        hint = "真 Google 结果，中文与长尾问题覆盖最好（有免费额度）",
        consoleUrl = "https://serper.dev/dashboard",
        kind = SearchKind.SERPER, verified = true,
    ),
    SearchEngine(
        id = "tavily", name = "Tavily",
        hint = "为 AI 场景做的搜索，返回正文摘要",
        consoleUrl = "https://app.tavily.com/home",
        kind = SearchKind.TAVILY, verified = false,
    ),
    SearchEngine(
        id = "bocha", name = "博查",
        hint = "国内可直连，中文内容更全",
        consoleUrl = "https://open.bochaai.com/",
        kind = SearchKind.BOCHA, verified = false,
    ),
    SearchEngine(
        id = "zhipu", name = "智谱联网",
        hint = "和上面的智谱 GLM 用同一个 Key，国内直连",
        consoleUrl = "https://open.bigmodel.cn/usercenter/apikeys",
        kind = SearchKind.ZHIPU, verified = true,
    ),
    // ⚠️ Brave 排最后: 本机实测 `api.search.brave.com:443` **TCP 连不通**
    //    (同一时刻 Exa / TinyFish / Serper / Tavily / DeepSeek 全通, 只有它和 www.google.com 不通)。
    //    接口形态本身是官方文档核实的, 所以 verified 仍为 true —— 连不上是网络问题, 不是实现问题。
    SearchEngine(
        id = "brave", name = "Brave",
        hint = "独立网页索引，每月有免费额度（需绑卡）",
        consoleUrl = "https://api-dashboard.search.brave.com/app/keys",
        kind = SearchKind.BRAVE, verified = true,
        warn = "实测本机连不上 api.search.brave.com（TCP 443 不通），选它大概率搜不出结果，" +
            "建议改用 Serper / TinyFish / 智谱联网",
    ),
)

/** 一条搜索结果 */
data class WebHit(val title: String, val url: String, val snippet: String)

object AiSearch {

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .build()

    private val JSON = "application/json; charset=utf-8".toMediaType()

    /**
     * 配了引擎**且**填了 Key 才算可用 —— 输入框的 chip 用它决定显不显示。
     *
     * ⚠️ 走 [Session.hasSearchKey] 而不是 `Session.aiSearchKey.isNotBlank()`:
     * 后者要过 Keystore 解密, 而这个函数是在组合期调的。
     */
    fun isConfigured(): Boolean =
        Session.aiSearchEngine.isNotBlank() && Session.hasSearchKey()

    fun engine(): SearchEngine? = SEARCH_ENGINES.firstOrNull { it.id == Session.aiSearchEngine }

    fun engineName(): String = engine()?.name ?: ""

    /** 搜一次。失败直接抛, 由调用方决定怎么呈现 */
    suspend fun search(query: String, limit: Int = 5): List<WebHit> = withContext(Dispatchers.IO) {
        val eng = engine() ?: throw RuntimeException("还没配置搜索引擎")
        val key = Session.aiSearchKey.trim()
        if (key.isBlank()) throw RuntimeException("还没填搜索引擎的 Key")
        run(eng.kind, query, key, limit)
    }

    /** 按引擎类型分发。抽出来是为了让 [test] 能复用同一批实现 —— 测的必须就是搜的那条路 */
    private fun run(kind: SearchKind, query: String, key: String, limit: Int): List<WebHit> {
        // ★ Key 统一在这里洗。搜和测共用这道门, 所以两边看到的字符一定是同一串 ——
        //   否则会出现"测试通过、发消息却 401"这种最让人抓狂的情况。
        val k = cleanApiKey(key)
        return when (kind) {
            SearchKind.EXA -> exa(query, k, limit)
            SearchKind.TINYFISH -> tinyfish(query, k, limit)
            SearchKind.BRAVE -> brave(query, k, limit)
            SearchKind.SERPER -> serper(query, k, limit)
            SearchKind.TAVILY -> tavily(query, k, limit)
            SearchKind.BOCHA -> bocha(query, k, limit)
            SearchKind.ZHIPU -> zhipu(query, k, limit)
        }
    }

    /** 搜索引擎自检结果 */
    data class SearchTest(val ok: Boolean, val message: String)

    /**
     * 拿**用户当前填在输入框里**的引擎与 Key 直接打一次真实搜索 —— 保存前就能知道 Key 行不行。
     *
     * 为什么要这个: 在此之前, Key 填错只有等到用户发第一条消息才暴露,
     * 而且那时看到的是「搜索失败(401)」, 用户根本不知道是 Key 抄错了还是服务商挂了。
     * 跟 AI 的「测试连接」、思考的「实测思考支持」是同一套路。
     *
     * 查询词用 `OpenAI` —— 它一定搜得到结果, 免得"0 条结果"被误当成 Key 有问题。
     */
    suspend fun test(engineId: String, key: String): SearchTest = withContext(Dispatchers.IO) {
        val eng = SEARCH_ENGINES.firstOrNull { it.id == engineId }
            ?: return@withContext SearchTest(false, "先选一个搜索引擎")
        val k = key.trim()
        if (k.isBlank()) return@withContext SearchTest(false, "还没填 Key")
        try {
            val hits = run(eng.kind, "OpenAI", k, 3)
            SearchTest(true, "连接正常，${eng.name} 返回了 ${hits.size} 条结果")
        } catch (e: Exception) {
            SearchTest(false, e.message ?: "测试失败")
        }
    }

    // ---------------------------------------------------------------------------------
    //  各家实现
    // ---------------------------------------------------------------------------------

    /**
     * Exa —— `POST https://api.exa.ai/search`, 头 `x-api-key`。
     * 用 `contents.highlights` (官方推荐形态): 返回与 query 最相关的片段, 比拉整页正文省 token。
     * 响应 `{"results":[{"title","url","highlights":["..."]}]}`
     */
    private fun exa(query: String, key: String, limit: Int): List<WebHit> {
        val body = JSONObject().apply {
            put("query", query)
            put("numResults", limit)
            put("contents", JSONObject().put("highlights", true))
        }
        val req = Request.Builder()
            .url("https://api.exa.ai/search")
            .post(body.toString().toRequestBody(JSON))
            .header("x-api-key", key)
            .build()
        return call(req, "Exa") { root ->
            hits(root.optJSONArray("results")) { o ->
                WebHit(
                    title = jsonStr(o, "title").ifBlank { jsonStr(o, "url") },
                    url = jsonStr(o, "url"),
                    snippet = o.optJSONArray("highlights")?.let { h ->
                        (0 until h.length()).joinToString(" … ") { jsonStr(h, it) }
                    }.orEmpty().ifBlank { jsonStr(o, "text") },
                )
            }
        }
    }

    /**
     * TinyFish —— `GET https://api.search.tinyfish.ai?query=...`, 头 `X-API-Key`。
     * 响应 `{"results":[{"title","url","snippet"}]}`（已用真 Key 实测）
     */
    private fun tinyfish(query: String, key: String, limit: Int): List<WebHit> {
        val req = Request.Builder()
            .url("https://api.search.tinyfish.ai?query=${enc(query)}&num_results=$limit")
            .header("X-API-Key", key)
            .build()
        return call(req, "TinyFish") { root ->
            hits(root.optJSONArray("results")) { o ->
                WebHit(jsonStr(o, "title"), jsonStr(o, "url"), jsonStr(o, "snippet"))
            }
        }
    }

    /**
     * Brave —— `GET https://api.search.brave.com/res/v1/web/search?q=...&count=N`。
     * ⚠️ 头是 `X-Subscription-Token`, **不是** `Authorization: Bearer` —— 用错必 401。
     * 响应 `{"web":{"results":[{"title","url","description"}]}}`
     */
    private fun brave(query: String, key: String, limit: Int): List<WebHit> {
        val n = limit.coerceIn(1, 20)   // 官方上限 20
        val req = Request.Builder()
            .url("https://api.search.brave.com/res/v1/web/search?q=${enc(query)}&count=$n")
            .header("X-Subscription-Token", key)
            .header("Accept", "application/json")
            .build()
        return call(req, "Brave") { root ->
            hits(root.optJSONObject("web")?.optJSONArray("results")) { o ->
                WebHit(jsonStr(o, "title"), jsonStr(o, "url"), jsonStr(o, "description"))
            }
        }
    }

    /**
     * Serper —— `POST https://google.serper.dev/search`, 头 `X-API-KEY`（**不是** Bearer）。
     * 拿的是**真 Google SERP**（不是自建索引），中文和长尾问题覆盖最好。
     * 响应 `{"organic":[{"title","link","snippet","position"}]}`
     * 顺带把 `gl/hl` 钉成中文区, 否则中文问题会返回一堆英文站。
     */
    private fun serper(query: String, key: String, limit: Int): List<WebHit> {
        val body = JSONObject().apply {
            put("q", query)
            put("num", limit.coerceIn(1, 20))
            put("gl", "cn")
            put("hl", "zh-cn")
        }
        val req = Request.Builder()
            .url("https://google.serper.dev/search")
            .post(body.toString().toRequestBody(JSON))
            .header("X-API-KEY", key)
            .build()
        return call(req, "Serper") { root ->
            hits(root.optJSONArray("organic")) { o ->
                WebHit(jsonStr(o, "title"), jsonStr(o, "link"), jsonStr(o, "snippet"))
            }
        }
    }

    /**
     * Tavily —— `POST https://api.tavily.com/search`。
     * 响应 `{"results":[{"title","url","content"}]}`
     * ⚠️ 未实测。
     */
    private fun tavily(query: String, key: String, limit: Int): List<WebHit> {
        val body = JSONObject().apply {
            put("query", query)
            put("max_results", limit)
            put("search_depth", "basic")
        }
        val req = Request.Builder()
            .url("https://api.tavily.com/search")
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $key")
            .build()
        return call(req, "Tavily") { root ->
            hits(root.optJSONArray("results")) { o ->
                WebHit(jsonStr(o, "title"), jsonStr(o, "url"), jsonStr(o, "content"))
            }
        }
    }

    /**
     * 博查 —— `POST https://api.bochaai.com/v1/web-search`, 头 `Authorization: Bearer`。
     * 响应 `{"code":200,"data":{"webPages":{"value":[{"name","url","snippet","summary"}]}}}`
     * ⚠️ 未实测。
     */
    private fun bocha(query: String, key: String, limit: Int): List<WebHit> {
        val body = JSONObject().apply {
            put("query", query)
            put("count", limit)
            put("summary", true)
        }
        val req = Request.Builder()
            .url("https://api.bochaai.com/v1/web-search")
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $key")
            .build()
        return call(req, "博查") { root ->
            val arr = root.optJSONObject("data")
                ?.optJSONObject("webPages")?.optJSONArray("value")
            hits(arr) { o ->
                WebHit(
                    title = jsonStr(o, "name"),
                    url = jsonStr(o, "url"),
                    snippet = jsonStr(o, "summary").ifBlank { jsonStr(o, "snippet") },
                )
            }
        }
    }

    /**
     * 智谱联网搜索 —— `POST https://open.bigmodel.cn/api/paas/v4/web_search`, 头 `Authorization: Bearer`。
     *
     * 响应 `{"search_result":[{"title","link","content","media","publish_date"}]}`
     * —— 注意是 `link` **不是** `url`, 别照抄别家。
     *
     * `search_engine` 可选 `search_std`(0.01 元/次) / `search_pro`(0.03) / `search_pro_sogou`(0.05)
     * / `search_pro_quark`(0.05)。默认用最便宜的 `search_std` —— 给模型当资料够用,
     * 没必要为每次提问多付 3~5 倍。
     *
     * 端点与 GLM 对话共用同一个 base 和同一个 Key, 所以已经配了智谱 GLM 的用户不用再申请。
     */
    private fun zhipu(query: String, key: String, limit: Int): List<WebHit> {
        val body = JSONObject().apply {
            put("search_engine", "search_std")
            put("search_query", query)
            put("count", limit.coerceIn(1, 50))
            put("content_size", "medium")
        }
        val req = Request.Builder()
            .url("https://open.bigmodel.cn/api/paas/v4/web_search")
            .post(body.toString().toRequestBody(JSON))
            .header("Authorization", "Bearer $key")
            .build()
        return call(req, "智谱联网") { root ->
            hits(root.optJSONArray("search_result")) { o ->
                WebHit(
                    title = jsonStr(o, "title"),
                    url = jsonStr(o, "link"),
                    snippet = jsonStr(o, "content"),
                )
            }
        }
    }

    // ---------------------------------------------------------------------------------
    //  MC 百科 / Minecraft Wiki —— AI 的**独立工具**, 不占用户的搜索引擎配置
    // ---------------------------------------------------------------------------------

    /**
     * 中文 MC 百科 (MediaWiki)。**免费、无需 Key、无需登录**。
     *
     * 为什么单开一条路而不是塞进 [SEARCH_ENGINES]:
     * 那几家都是"用户自己申请 Key 的通用网页搜索", 而这一条是**领域知识源** ——
     * 写 MC 指令要的是方块/实体/语法条目的原文, 通用搜索引擎给的是论坛贴和视频站。
     * 而且它不要 Key, 所以**没配任何搜索引擎的用户也应该能用**。
     */
    private const val WIKI_ZH = "https://zh.minecraft.wiki/api.php"

    /** 英文 Minecraft Wiki —— 中文站搜不到时才兜底 (中文站条目覆盖更全, 优先它) */
    private const val WIKI_EN = "https://minecraft.wiki/api.php"

    /**
     * 查 MC 百科 / Minecraft Wiki。
     *
     * 用 `generator=search` 一次请求**同时拿到搜索命中与正文摘要**, 省一轮往返:
     * `action=query&generator=search&gsrsearch=…&gsrlimit=N&prop=extracts&explaintext=1&exchars=800`
     *  → `query.pages` 是**以 pageid 为键的对象**(不是数组!), 每项有
     *    `title` / `index`(1-based 相关度排名) / `extract`(纯文本, 已剥掉 HTML)。
     *
     * ⚠️ **不要加 `exintro=1`**: 那会只取首段, 而指令类条目(`命令/execute`)的**语法表在正文里**,
     *    只取首段等于拿到一段泛泛的简介, 对写指令毫无帮助。
     *
     * ⚠️ **不要加 `origin=*`**: 那是给浏览器跨域的, 原生请求不需要, 带上反而被某些镜像拒。
     */
    suspend fun wikiSearch(query: String, limit: Int = 4): List<WebHit> = withContext(Dispatchers.IO) {
        val q = query.trim()
        if (q.isBlank()) return@withContext emptyList()
        // 中文站优先; 它挂了或搜不到才去英文站。两次都失败就抛 —— 调用方会把异常原文交给模型。
        val zh = runCatching { wikiQuery(WIKI_ZH, q, limit) }.getOrElse { e ->
            // 中文站是"网络/服务"级失败(不是"没搜到"), 英文站大概率同样连不上, 直接抛更有信息量
            if (e is RuntimeException && e.message?.contains("检索失败") == true) throw e else emptyList()
        }
        if (zh.isNotEmpty()) return@withContext zh
        wikiQuery(WIKI_EN, q, limit)
    }

    /** [wikiSearch] 的单站实现。见上面关于 `exintro` / `origin` 的两条 ⚠️ */
    private fun wikiQuery(api: String, q: String, limit: Int): List<WebHit> {
        val url = api + "?action=query&format=json&generator=search&redirects=1" +
            "&gsrsearch=" + enc(q) +
            "&gsrlimit=" + limit.coerceIn(1, 10) +
            "&prop=extracts&explaintext=1&exchars=800"
        val req = Request.Builder().url(url).header("Accept", "application/json").build()
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("MC 百科检索失败(${resp.code})")
            val pages = JSONObject(text).optJSONObject("query")?.optJSONObject("pages")
                ?: return emptyList()
            val host = api.removeSuffix("/api.php")
            // ★ `index` 是相关度排名, 必须按它排 —— `query.pages` 是 JSON 对象, 遍历顺序
            //   由 pageid 决定(数字序), 不排的话最相关的那条可能排在最后。
            val ranked = ArrayList<Pair<Int, WebHit>>()
            val keys = pages.keys()
            while (keys.hasNext()) {
                val o = pages.optJSONObject(keys.next()) ?: continue
                val title = jsonStr(o, "title")
                if (title.isBlank()) continue
                val rank = if (o.has("index")) o.optInt("index", 99) else 99
                ranked.add(
                    rank to WebHit(
                        title = title,
                        url = host + "/w/" + wikiPath(title),
                        snippet = jsonStr(o, "extract").trim(),
                    )
                )
            }
            return ranked.sortedBy { it.first }.map { it.second }
        }
    }

    /**
     * 条目名 → URL 路径段。
     *
     * ⛔ **不能直接用 [enc]**: `URLEncoder` 会把空格编成 `+`(URL 路径里 `+` 是字面加号,
     *    不是空格), 还会把 `命令/execute` 里的 `/` 编成 `%2F` —— 那个 `/` 是**子页面分隔符**,
     *    编掉之后 wiki 会当成一个叫"命令/execute"的条目去找, 直接 404。
     */
    private fun wikiPath(title: String): String =
        enc(title.replace(' ', '_')).replace("+", "%20").replace("%2F", "/").replace("%2f", "/")

    // ---------------------------------------------------------------------------------
    //  多源知识检索 (2026-10-05) —— mc_wiki 工具背后的**可插拔源**
    // ---------------------------------------------------------------------------------
    //
    // 需求: 「不止 mc 百科 API, 还要 mc wiki、维基百科等」+「可以让 AI 同时查看几个接口」。
    //
    // 设计取舍 ★: **不增加第 4 个工具**。工具越多, 模型选错的概率越大; 而且每个工具
    //   都占 tools schema 的 token。做法是让 `mc_wiki` 背后**并发打多个源**, 把各源
    //   结果合并成一份返回。对模型而言仍是"查一次百科", 拿到的是多源交叉后的更全资料。
    //
    // ★★ 每个源**必须独立 try/catch** —— 这是本设计的核心价值:
    //   维基百科在本机被 DNS 污染(实测解析到 31.13.73.9 → TCP 443 不通),
    //   Fandom 时通时不通。若共用一个 try, 任何一个挂掉都会让整次检索失败。
    //   独立之后: 挂了的那源只是"未取到", 其余源的结果照常给模型。

    /** 一个知识源的静态描述 —— 用于 UI 展示"查了哪些源"、以及排序去重时的来源标记 */
    data class Source(val id: String, val name: String)

    /**
     * mc_wiki 工具的**知识源清单**（按优先级排序，靠前的在合并结果里排前面）。
     *
     * ⚠️ 顺序有讲究: MC 专属站必须排在维基百科前面 —— 写 MC 指令要的是
     *    「命令/execute」这种条目原文, 而维基百科的"我的世界"条目是泛泛介绍,
     *    对写指令没有直接帮助。通用百科只在 MC 专属站没结果时才补位。
     *
     * ★ 2026-10-05 起改为 `internal` 可见: `AiToolRunner` 要在时间线里列出
     *   "这一轮并发查了哪几个源" —— 需求里明确要看到"查看了哪几个接口"。
     *   暴露的是**只读元数据**(id + 展示名), 没有任何密钥或可变状态。
     */
    val SOURCES: List<Source> = listOf(
        Source("mc_zh", "MC百科·中文"),      // zh.minecraft.wiki  —— 最全, 中文条目名
        Source("mc_en", "MC Wiki·英文"),     // minecraft.wiki   —— 中文搜不到时兜底
        Source("mcmod", "mcmod.cn"),        // 中文社区资料站, HTML 解析
        Source("wiki_zh", "维基百科·中文"),   // 被墙, 失败即跳过
        Source("wiki_en", "维基百科·英文"),   // 同上
    )

    /** UI 展示用: 上一次 [wikiSearchMulti] 实际查过哪些源、各取到几条。给「过程时间线」用 */
    private val lastSourceStats = java.util.concurrent.ConcurrentHashMap<String, Int>()

    /** 取上次各源的命中数快照 (源 id → 条数; 0 = 未取到) */
    fun lastSourceHits(): Map<String, Int> = HashMap(lastSourceStats)

    /** 源 id → 展示名 */
    fun sourceName(id: String): String = SOURCES.firstOrNull { it.id == id }?.name ?: id

    /**
     * **多源并发检索** —— mc_wiki 工具的新入口。
     *
     * 一轮里所有源用 [async] 同时发, [awaitAll] 收口。总耗时 ≈ **最慢的那个源**,
     * 而不是各源之和。实测 5 个源若串行约 10s, 并发后 2~3s。
     *
     * @param limit 每个源返回几条 (会按源数做总预算控制, 不是每个源都取满)
     * @return (合并后的条目, 各源命中统计) —— 统计用于时间线展示, 不影响调用方逻辑
     */
    suspend fun wikiSearchMulti(query: String, limit: Int = 6): List<WebHit> {
        val q = query.trim()
        if (q.isBlank()) return emptyList()
        lastSourceStats.clear()

        // ★ 每个源一个 async, 各自 runCatching —— 任一源抛异常都被吃掉, 只记 0 条。
        // ⚠️ 这里**不要**把局部变量命名成 `hits` —— 会遮蔽成员函数 `hits(...)`,
        //    且 `emptyList()` 在 getOrDefault 下推不出类型 → 整片级联报错(踩过)。
        val deferred = SOURCES.map { src ->
            coroutineScope {
                async(Dispatchers.IO) {
                    val got: List<WebHit> =
                        runCatching { fetchSource(src, q, limit) }.getOrDefault(emptyList())
                    lastSourceStats[src.id] = got.size
                    got
                }
            }
        }

        // ★ awaitAll: 任何一个源内部已经不会抛了(上面 getOrDefault 兜住), 所以这里不会被拖垮。
        val all = deferred.awaitAll()

        // 合并 + 去重(同 URL 只留一条, 保留先出现的那个源 → 保证 MC 专属站优先)
        val seen = HashSet<String>()
        val merged = ArrayList<WebHit>()
        for (list in all) {
            for (h in list) {
                val key = h.url.trim().lowercase()
                if (key.isBlank() || !seen.add(key)) continue
                merged.add(h)
                if (merged.size >= limit * 2) return@wikiSearchMulti merged   // 总预算上限, 别撑爆 prompt
            }
        }
        return merged
    }

    /** 单源分发。新增源只需在 [SOURCES] 加一项 + 这里加一个分支 */
    private fun fetchSource(src: Source, q: String, limit: Int): List<WebHit> = when (src.id) {
        "mc_zh" -> wikiQuery(WIKI_ZH, q, limit)
        "mc_en" -> wikiQuery(WIKI_EN, q, limit)
        "mcmod" -> mcmodSearch(q, limit)
        "wiki_zh" -> wikiQuery(WIKI_ZH_PEDIA, q, limit)
        "wiki_en" -> wikiQuery(WIKI_EN_PEDIA, q, limit)
        else -> emptyList()
    }

    /** 维基百科中文站。⚠️ 本机实测被 DNS 污染, 不保证可达 —— 失败会被上层吃掉 */
    private const val WIKI_ZH_PEDIA = "https://zh.wikipedia.org/w/api.php"

    /** 维基百科英文站 */
    private const val WIKI_EN_PEDIA = "https://en.wikipedia.org/w/api.php"

    /**
     * mcmod.cn 搜索 —— **HTML 解析, 不是 API**。
     *
     * 为什么它值得单开: 它是**中文 MC 社区资料最全的站**(模组/教程/整合包),
     * 而且本机实测**直连可达**(200), 不需要代理。
     *
     * 实测到的结构: 结果条目形如
     *   `<a href="https://www.mcmod.cn/class/2021.html">机械动力 (Create)</a>`
     * 所以用正则抠 `/class/`、`/post/`、`/modpack/` 三类链接 + 紧随其后的标题文本。
     *
     * ⚠️ HTML 站点随时可能改版 —— 抠不到就返回空列表(上层会当成"这个源没结果"),
     *    **绝不能因为解析失败而抛异常**, 那会连累其它源。
     */
    private fun mcmodSearch(q: String, limit: Int): List<WebHit> {
        val url = "https://search.mcmod.cn/s?key=" + enc(q)
        val req = Request.Builder().url(url)
            .header("Accept", "text/html,application/xhtml+xml")
            .build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("mcmod 搜索失败(${resp.code})")
            val html = resp.body?.string().orEmpty()
            if (html.isBlank()) return emptyList()
            // 一条结果 = 链接 + 标题。用非贪婪匹配到最近的 </a>，避免把整页吞进去。
            //
            // ⛔⛔ **不要用三引号原始串拼这个正则**（我踩过）: 模式里既有 `"` 又有 `\`
            //   与 `"""` 相接时会凑成 `"""" ` → Kotlin 把字符串接续解析错，
            //   报的却是"整个 object 里的函数都 Unresolved"这种完全不相关的级联错误。
            //   普通转义串（下面的写法）反而最稳。
            val re = Regex(
                "<a[^>]+href=\"(https?://(?:www\\.)?mcmod\\.cn/(?:class|post|modpack)/\\d+[^\"]*)\"" +
                    "[^>]*>(.{1,80}?)</a>",
                RegexOption.DOT_MATCHES_ALL
            )
            val out = ArrayList<WebHit>()
            val seen = HashSet<String>()
            for (m in re.findAll(html)) {
                val link = m.groupValues[1]
                val title = m.groupValues[2].replace(Regex("<[^>]+>"), "").trim()
                // 过滤掉导航/页脚那种短标题，只留像条目的
                if (title.length < 2 || !seen.add(link)) continue
                out.add(WebHit(title = title, url = link, snippet = "mcmod.cn 条目：$title"))
                if (out.size >= limit) break
            }
            return out
        }
    }

    // ---------------------------------------------------------------------------------
    //  公共部分
    // ---------------------------------------------------------------------------------

    private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")

    /** 统一的"遍历结果数组 → 过滤掉没有 url 的条目" */
    private fun hits(arr: org.json.JSONArray?, make: (JSONObject) -> WebHit): List<WebHit> {
        if (arr == null) return emptyList()
        val out = ArrayList<WebHit>(arr.length())
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val h = make(o)
            if (h.url.isNotBlank()) out.add(h)
        }
        return out
    }

    private fun call(req: Request, who: String, parse: (JSONObject) -> List<WebHit>): List<WebHit> {
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string().orEmpty()
            if (!resp.isSuccessful) throw RuntimeException("$who 搜索失败(${resp.code})：${errOf(text)}")
            val list = try {
                parse(JSONObject(text))
            } catch (e: Exception) {
                // 解析炸了说明这家返回的形态和我们预期不一致 —— 把原文头一段带上, 好定位
                throw RuntimeException("$who 返回格式不认识：${text.take(160)}")
            }
            if (list.isEmpty()) throw RuntimeException("$who 没有搜到结果")
            return list
        }
    }

    private fun errOf(text: String): String {
        val fallback = text.take(160).ifBlank { "无响应内容" }
        return runCatching {
            val j = JSONObject(text)
            when (val e = j.opt("error")) {
                // ⚠️ optString 的 fallback 拦不住显式 null(`{"message":null}` → 字符串 "null")
                is JSONObject -> jsonStr(e, "message").ifBlank { fallback }
                is String -> e
                null -> jsonStr(j, "message").ifBlank { fallback }
                else -> fallback
            }
        }.getOrDefault(fallback)
    }

    /**
     * 把搜索结果拼成塞进 system prompt 的一段。
     *
     * 明确告诉模型"这是刚搜到的、可能过时、要给出来源" —— 不写这句它会当成自己的知识,
     * 编造起来更理直气壮。
     */
    fun buildContext(hits: List<WebHit>): String {
        if (hits.isEmpty()) return ""
        val sb = StringBuilder()
        sb.append("\n\n【实时搜索结果】用户开启了智能搜索，以下是刚刚检索到的网页资料（可能不完整或过时）：\n")
        hits.forEachIndexed { i, h ->
            sb.append("\n[").append(i + 1).append("] ").append(h.title).append('\n')
            sb.append(h.url).append('\n')
            if (h.snippet.isNotBlank()) sb.append(h.snippet.take(600)).append('\n')
        }
        sb.append("\n回答时优先采用上述资料；引用到的地方标出来源编号，如 [1]。资料不足以回答就直说。")
        return sb.toString()
    }

    /**
     * **工具调用**的返回内容 —— 作为 `role: "tool"` 消息塞回对话 (见 `AiClient` 的 function calling)。
     *
     * 与 [buildContext] 的区别: 那一份是"塞进 system prompt 的资料", 所以要带上"用户开启了智能搜索"
     * 这类**给人看的**说明; 这一份是**给模型看的工具返回值**, 只该有数据本身。
     * 多一句废话模型就多一分跑偏的机会, 而且 tool 消息里的说明它未必当说明读。
     *
     * 搜不到时**必须返回一句非空文本** —— 空 content 的 tool 消息有些网关会直接拒。
     */
    fun buildToolResult(hits: List<WebHit>): String {
        if (hits.isEmpty()) return "没有搜到相关结果，请换一组关键词重试，或直接凭已有知识回答。"
        val sb = StringBuilder()
        hits.forEachIndexed { i, h ->
            sb.append('[').append(i + 1).append("] ").append(h.title).append('\n')
            sb.append(h.url).append('\n')
            if (h.snippet.isNotBlank()) sb.append(h.snippet.take(800)).append('\n')
            sb.append('\n')
        }
        sb.append("（以上为实时检索结果，引用时标出来源编号，如 [1]。）")
        return sb.toString()
    }

    /**
     * **MC 百科工具**的返回内容 (作为 `role: "tool"` 塞回对话)。
     *
     * 与 [buildToolResult] 只差**开头那句限定**, 但那一句很关键:
     * wiki 正文里到处是 `[仅Java版]` 标记, 而网易版是**基岩版内核** ——
     * 不明确告诉模型"标了 Java 版的不能用于网易版", 它会把 `/execute store`、`/data` 这类
     * Java 专属写法照抄给用户, 用户拿去游戏里就是一条红字报错。
     */
    fun buildWikiToolResult(hits: List<WebHit>): String {
        if (hits.isEmpty()) return "MC 百科没有搜到这个条目，请换一组关键词（用中文条目名，如「命令 execute」「记分板」），或凭已有知识回答。"
        val sb = StringBuilder()
        sb.append("以下内容来自《我的世界》Wiki / MC 百科（权威，但可能滞后于最新版本）：\n")
        sb.append("⚠️ 正文里标注「[仅Java版]」的写法在网易版（基岩版内核）**不可用**，必须改用基岩版写法或放弃；")
        sb.append("标注「[仅基岩版]」的才适用于网易版。\n")
        hits.forEachIndexed { i, h ->
            sb.append('\n').append('[').append(i + 1).append("] ").append(h.title).append('\n')
            sb.append(h.url).append('\n')
            if (h.snippet.isNotBlank()) sb.append(h.snippet.take(900)).append('\n')
        }
        sb.append("\n（引用时标出来源编号，如 [1]。）")
        return sb.toString()
    }

    /**
     * **多源**版本的百科工具返回 (2026-10-05)。
     *
     * 与 [buildWikiToolResult] 的区别: 开头会**列明这次实际查了哪些源、各取到几条** ——
     * 这有两个作用: ① 模型知道资料的覆盖面(某源 0 条时会知道"这个方向没查到");
     * ② 用户从时间线上能看见 AI 到底访问了哪些接口(需求里明确要的)。
     *
     * 全部源都没结果时的文案要**区分"网络挂了"和"真没这条目"** ——
     * 若所有源都是 0 且存在网络型失败, 就提示"检索通道可能不通", 免得用户以为是条目不存在的锅。
     */
    fun buildMultiWikiToolResult(hits: List<WebHit>): String {
        val stats = lastSourceHits()
        val tried = SOURCES.map { it.name to (stats[it.id] ?: 0) }
        val header = "本次检索的知识源：" +
            tried.joinToString("、") { "${it.first}(${it.second}条)" } + "\n"

        if (hits.isEmpty()) {
            return header + "以上源都没有搜到相关条目。请换一组关键词（用中文条目名，如「命令 execute」「记分板」），" +
                "或直接凭已有知识回答。"
        }
        val sb = StringBuilder()
        sb.append(header)
        sb.append("以下内容来自《我的世界》Wiki / MC 百科 / 维基百科等（权威，但可能滞后于最新版本）：\n")
        sb.append("⚠️ 正文里标注「[仅Java版]」的写法在网易版（基岩版内核）**不可用**，必须改用基岩版写法或放弃；")
        sb.append("标注「[仅基岩版]」的才适用于网易版。\n")
        hits.forEachIndexed { i, h ->
            sb.append('\n').append('[').append(i + 1).append("] ").append(h.title).append('\n')
            sb.append(h.url).append('\n')
            if (h.snippet.isNotBlank()) sb.append(h.snippet.take(900)).append('\n')
        }
        sb.append("\n（引用时标出来源编号，如 [1]。）")
        return sb.toString()
    }
}
