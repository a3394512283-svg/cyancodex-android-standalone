package com.canglan.cyancodex.data

import com.google.gson.annotations.SerializedName

// ---- 通用响应包装 ----
data class ApiResp<T>(
    val ok: Boolean = false,
    val data: T? = null,
    val error: String? = null,
    @SerializedName("need_totp") val needTotp: Boolean = false,   // 登录需两步验证码
)

// ---- 用户/认证 ----
data class User(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val email: String = "",
    val gender: String = "secret",
    val points: Int = 0,
    @SerializedName("ai_unlocked") val aiUnlocked: Boolean = false,
    @SerializedName("ai_unlock_expire_at") val aiUnlockExpireAt: String? = null,
    @SerializedName("vip_level") val vipLevel: Int = 0,
    val role: String = "user",
    @SerializedName("created_at") val createdAt: String? = null,
)

data class LoginResp(val token: String? = null, val user: User? = null)

data class CaptchaResp(val id: String = "", val image: String = "")

// ---- 指令 ----
data class Command(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val command: String = "",
    val category: String = "other",
    val source: String = "user",
    val quality: String = "high",
    @SerializedName("mc_version") val mcVersion: String = "",
    val tags: String = "",
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("view_count") val viewCount: Int = 0,
    @SerializedName("like_count") val likeCount: Int = 0,
    @SerializedName("comment_count") val commentCount: Int = 0,
    val image: String? = null,       // 效果图 (/uploads/command_images/...)
    val status: String = "active",   // active=已通过 pending=待审核 rejected=已驳回
    @SerializedName("review_note") val reviewNote: String? = null, // 审核意见/驳回理由
    val liked: Boolean = false,      // 当前用户是否已点赞 (详情接口返回)
    @SerializedName("created_at") val createdAt: String? = null,
    val author: String? = null,
    @SerializedName("author_role") val authorRole: String? = null, // admin 显示管理员徽章 (意见 #43)
    @SerializedName("author_official") val authorOfficial: Int = 0, // 官方认证账号 1=是 (红色徽章)
    // 付费指令 (意见 #83)
    val price: Int = 0,              // 0=免费, >0 需积分购买
    val purchased: Boolean = false,  // 当前用户是否已购买
    val locked: Boolean = false,     // 详情接口: true=付费未解锁 (command 为空)
    @SerializedName("can_edit") val canEdit: Boolean = false, // 当前用户是否可编辑
    @SerializedName("file_count") val fileCount: Int = 0,      // 附件数量 (列表接口)
    val files: List<CommandFile> = emptyList(),                // 附件列表 (详情接口)
)

/** 指令附件 (txt/json/mcfunction/图片/zip 等) */
data class CommandFile(
    val id: Long = 0,
    val name: String = "",
    val size: Int = 0,
    @SerializedName("size_text") val sizeText: String = "",
    val ext: String = "",
    val url: String = "",
)

/** 上传/编辑指令的请求体 (支持附件) */
data class CmdFileIn(val name: String, val data: String)

data class CommandBody(
    val title: String,
    val description: String = "",
    val command: String = "",
    val category: String = "other",
    @SerializedName("mc_version") val mcVersion: String = "",
    val tags: String = "",
    val price: String = "0",
    val files: List<CmdFileIn> = emptyList(),
    @SerializedName("remove_files") val removeFiles: List<Long> = emptyList(),
)

// ---- 快捷查询 ID (基岩版参考表) ----
data class McRefItem(
    val kind: String = "",
    val name: String = "",
    val ids: List<String> = emptyList(),
    val tag: String = "",
)

data class McRefList(
    val list: List<McRefItem> = emptyList(),
    val q: String = "",
    val total: Int = 0,
)

data class McRefKind(val kind: String = "", val count: Int = 0)

data class McRefKinds(
    val list: List<McRefKind> = emptyList(),
    val total: Int = 0,
)

data class CommandPage(
    val list: List<Command> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

data class Category(val key: String = "", val label: String = "")

data class FavResp(val favorited: Boolean = false)

// ---- 教程 ----
data class Tutorial(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val category: String = "basic",
    val source: String = "platform",
    val quality: String = "high",
    val type: String = "post",            // post=帖子 / video=视频
    @SerializedName("video_url") val videoUrl: String = "",
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("view_count") val viewCount: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    val author: String? = null,
)

data class TutorialPage(
    val list: List<Tutorial> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

// ---- 签到 (替代充值得积分) ----
data class CheckinStatus(
    @SerializedName("checked_today") val checkedToday: Boolean = false,
    @SerializedName("last_date") val lastDate: String? = null,
    val streak: Int = 0,
    val total: Int = 0,
    val points: Int = 0,
    @SerializedName("today_gain") val todayGain: Int = 0,
    val gained: Int = 0,     // 本次签到获得 (签到响应)
    val bonus: Int = 0,      // 本次连续奖励
)

data class AiStatus(
    val unlocked: Boolean = false,
    val expireAt: String? = null,
    val points: Int = 0,
    @SerializedName("pointsNeeded") val pointsNeeded: Int = 200,
)

data class SimpleOk(
    val unlocked: Boolean? = null,
    val points: Int? = null,
    val message: String? = null,
    val status: String? = null,
)

// ---- 历史/公告 ----
// 插件库 (意见 #37)
data class Plugin(
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val version: String = "1.0.0",
    // 适用版本 (意见 #86): java = Java 版, bedrock = 基岩版
    val platform: String = "java",
    val size: Int = 0,
    val status: String = "pending",
    val downloads: Int = 0,
    @SerializedName("review_note") val reviewNote: String? = null,
    @SerializedName("user_id") val userId: Long? = null,
    val author: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class PluginPage(
    val list: List<Plugin> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

// ---- 看公益广告得积分 ----
data class AdStatus(
    val enabled: Boolean = true,
    val reward: Int = 50,
    @SerializedName("daily_limit") val dailyLimit: Int = 20,
    @SerializedName("today_count") val todayCount: Int = 0,
    @SerializedName("today_points") val todayPoints: Int = 0,
    val remain: Int = 20,
    @SerializedName("total_times") val totalTimes: Int = 0,
    @SerializedName("total_points") val totalPoints: Int = 0,
)

data class AdItem(
    val id: Long = 0,
    val title: String = "",
    @SerializedName("page_url") val pageUrl: String = "",
    val cover: String? = null,
    val duration: Int = 30,
    @SerializedName("play_url") val playUrl: String = "",
    @SerializedName("play_type") val playType: String = "",   // hls / mp4 / page
)

data class AdStartResp(
    @SerializedName("view_id") val viewId: Long = 0,
    val ad: AdItem = AdItem(),
    val reward: Int = 50,
    val remain: Int = 20,
)

data class AdClaimResp(
    val gained: Int = 0,
    val points: Int = 0,
    val balance: Int = 0,
    @SerializedName("today_count") val todayCount: Int = 0,
    @SerializedName("daily_limit") val dailyLimit: Int = 20,
    val remain: Int = 20,
)

// ---- 宝贝回家 (公益寻亲信息) ----
data class BabyHomeContact(
    val tel: String = "",
    val mobile: String = "",
    val email: String = "",
    val address: String = "",
    @SerializedName("service_time") val serviceTime: String = "",
    @SerializedName("qq_group") val qqGroup: String = "",
)

data class BabyHomeItem(
    val id: String = "",
    val name: String = "",
    val gender: String = "",
    val photo: String = "",
    val feature: String = "",
    val birthday: String = "",
    @SerializedName("lost_time") val lostTime: String = "",
    @SerializedName("lost_place") val lostPlace: String = "",
    val height: String = "",
    val category: String = "",
    @SerializedName("detail_url") val detailUrl: String = "",
    val contact: BabyHomeContact = BabyHomeContact(),
)

data class BabyHomeResp(
    val ok: Boolean = false,
    @SerializedName("key_set") val keySet: Boolean = false,
    val error: String = "",
    @SerializedName("updated_at") val updatedAt: String = "",
    val source: String = "",
    val site: String = "",
    val items: List<BabyHomeItem> = emptyList(),
    val tips: List<String> = emptyList(),
)

// 好友 (意见 #38)
data class FriendItem(
    val id: Long = 0,
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("friend_id") val friendId: Long? = null,
    @SerializedName("peer_id") val peerId: Long? = null,
    val status: String = "pending",
    val nickname: String? = null,
    val avatar: String? = null,
    val username: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
)

// 私聊会话 (意见 #38)
data class Conversation(
    val id: Long = 0,
    @SerializedName("from_id") val fromId: Long? = null,
    @SerializedName("to_id") val toId: Long? = null,
    @SerializedName("peer_id") val peerId: Long? = null,
    val content: String = "",
    val nickname: String? = null,
    val avatar: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
    // 未读私信数（用户要求：私信改在好友/私聊卡片上用红点提示，不再进站内信）
    val unread: Int = 0,
)
data class DmMessage(
    val id: Long = 0,
    @SerializedName("from_id") val fromId: Long? = null,
    @SerializedName("to_id") val toId: Long? = null,
    val content: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
)

// 聊天室消息 (意见 #36)
data class ChatMessage(
    val id: Long = 0,
    val content: String = "",
    @SerializedName("user_id") val userId: Long? = null,
    val nickname: String? = null,
    val avatar: String? = null,
    val role: String? = null,
    @SerializedName("user_official") val userOfficial: Int = 0, // 官方认证账号 1=是
    @SerializedName("created_at") val createdAt: String? = null,
)

// AI 对话模型 (Gson 反射序列化: 必须放 data 包, R8 混淆需保留字段名)
data class ChatMsg(val role: String = "", val content: String = "")
data class UiMsg(
    val role: String = "",
    val content: String = "",
    val err: Boolean = false,
    /** 服务端对话轮次 id (AI 回复才有), 用于「有用/没用」反馈 */
    val turnId: Long = 0L,
    /** 本地已提交的评价: 0 未评价 / 1 有用 / -1 没用 */
    val vote: Int = 0,
    /**
     * 思考过程 (直连自备 API 时上游返回的 reasoning_content)。
     * 空串 = 该轮没有思考内容。UI 上折叠展示, 不占正文位置。
     */
    val think: String = "",
    /**
     * 这轮思考花了多少毫秒 (0 = 没测/没思考)。
     * 折叠条上显示「已思考（用时 N 秒）」—— 用户对"等了多久"比对"多少字"敏感得多。
     */
    val thinkMs: Long = 0L,
    /**
     * 这轮 AI **自己决定**发起的搜索关键词 (工具调用, 见 `AiClient.chat` 的 `searchTool`)。
     * 空 = 没搜。UI 上显示成「使用搜索能力」一行。
     *
     * ⚠️ 读取处一律用 `.orEmpty()` —— 老会话的 JSON 里没有这个键, Gson 反射出来是 **null**,
     * 直接 `.isEmpty()` 会 NPE。(`think` 字段同理。)
     */
    val searchQueries: List<String> = emptyList(),
    /**
     * 这轮 AI 查过的 MC 百科关键词 (「指令专家」模式的 `mc_wiki` 工具)。
     * 空 = 没查。UI 上显示成「查阅 MC 百科 · …」一行。
     *
     * ⚠️ 同 [searchQueries], 读取处一律 `.orEmpty()`。
     */
    val wikiQueries: List<String> = emptyList(),
    /**
     * 这轮把指令递给语法核对工具跑了几次 (`check_command`)。0 = 没核对。
     * UI 上显示成「已核对指令语法」一行 —— 让用户知道这份指令是自查过的。
     */
    val checkCount: Int = 0,
    /**
     * ★ 客户端**自己**在最终输出后复查过一遍 (2026-10-02)。
     *
     * 为什么要有这个字段: [checkCount] 只说明"模型**说**它查过了", 而模型可以压根不查、
     * 也可以查完再改。用户报「这个审核不对啊，石头 ID 都不对」就是踩在这里 ——
     * 界面上挂着"已核对指令语法", 指令却是错的。
     *
     * ⚠️ 老会话的 JSON 里没有这个键, Gson 反射出来是 **null**, 读取处用 `.orEmpty()`/默认值兜。
     */
    val rechecked: Boolean = false,
    /** 最后一次复查发现的问题数。0 = 通过（或这轮压根没有指令代码块） */
    val recheckIssueCount: Int = 0,
    /** 复查发现的问题清单（最多 6 条）。空 = 通过。⚠️ 读取处一律 `.orEmpty()` */
    val recheckIssues: List<String> = emptyList(),
    /**
     * ★★ **本轮用户消息附带的上传文件** (2026-10-05)。
     *
     * 用户原话:「对了，顺便给AI加一个上传文件功能」。
     *
     * ⛔ 只存**元信息 + 正文**, 不存 Uri / 不存原始字节:
     *   - Uri 是临时授权(SAF persistable 也要额外拿权限), 存进会话 JSON 后
     *     换个进程/隔天再打开就取不到了, 会变成一堆打不开的空壳;
     *   - 直接把正文存下来, 才能保证**重新打开这条会话时 AI 仍看得见文件内容**
     *     (历史里塞着"[附件] xxx.mcfunction（已上传）"却没有正文, 模型会当场瞎猜)。
     *
     * ⚠️ 老会话 JSON 里没这个键 → Gson 反射成 **null**, 读取处一律 `.orEmpty()`。
     */
    val attachments: List<Attachment> = emptyList(),
    /**
     * ★★ **本轮 AI 调用工具 / 复查的完整过程记录** (2026-10-05)。
     *
     * 用户原话:「我的过程指的是像 workbuddy 或者 ima 一样**展示中间思考的内容和调用的工具**」。
     * 上面那几个 `searchQueries` / `wikiQueries` / `checkCount` 只是**汇总**(一行关键词列表),
     * 而这里存的是**逐条**的 [AiToolEvent] —— 带状态(成功/失败)、耗时、并发分组。
     *
     * ## 为什么要落盘
     * 流式期间时间线是**实时**画出来的(见 `AiTaskRunner.toolEvents`), 但任务一收尾那份内存状态
     * 就被清了 —— 回答落地后, 用户回头看这条消息, "刚才查过什么、哪条超时了"全没了, 只剩
     * 一行干巴巴的关键词。把这份记录随消息一起存下来, 重新打开会话也能折叠展开查看。
     *
     * ⚠️ 老会话 JSON 里没这个键 → Gson 反射成 **null**, 读取处一律 `.orEmpty()`。
     */
    val toolTimeline: List<AiToolEvent> = emptyList(),
    /**
     * ★★ **同一条回答的多个版本** (2026-10-05)。
     *
     * 用户原话:「修改的那个功能，像 deepseek 一样有个 1/2，2/2 一样表示修改的次数
     * 还有每次的内容」。
     *
     * ## 什么时候会有多版
     *  - **重新生成**: 同一句提问让 AI 再答一遍 —— 旧答案不该被丢掉, 它是"第 1 版";
     *  - **修改后重发**: 用户改了提问再发 —— 上一轮的问答整组算"第 1 版"。
     *
     * ## 为什么 userText 要存在**版本**里而不是只存一次
     * 「修改」改的正是**用户那句提问**。第 1 版的提问和第 2 版可能完全不同, 所以每一版
     * 必须各自记住"当时问的是什么" —— 切回第 1 版时, 上面那条 user 气泡也要跟着换回去。
     *
     * ⚠️ 空列表 = 只有一版(就是本消息自己)。**永远不会有 size==1 的 variants** ——
     *   产生多版时一定是"旧 + 新"两条一起写进去。
     * ⚠️ 老会话 JSON 里没这个键 → Gson 反射成 **null**, 读取处一律 `.orEmpty()`。
     */
    val variants: List<MsgVariant> = emptyList(),
    /** 当前显示第几版 (0-based)。[variants] 为空时无意义 */
    val variantIndex: Int = 0,
) {
    /** 一共几版。没有 [variants] 时算 1 版(就是它自己) */
    val versionCount: Int get() = variants.orEmpty().size.coerceAtLeast(1)

    /** 把本消息自身抽成"一个版本" —— 用来生成 [variants] 里的第一项 */
    fun toVariant(userText: String = ""): MsgVariant = MsgVariant(
        userText = userText,
        content = content,
        think = think.orEmpty(),
        thinkMs = thinkMs,
        searchQueries = searchQueries.orEmpty(),
        wikiQueries = wikiQueries.orEmpty(),
        checkCount = checkCount,
        rechecked = rechecked,
        recheckIssueCount = recheckIssueCount,
        recheckIssues = recheckIssues.orEmpty(),
        toolTimeline = toolTimeline.orEmpty(),
        turnId = turnId,
    )

    /**
     * 切到第 [i] 版 —— **内容 / 思考 / 工具过程一起换**。
     *
     * ⚠️ 必须整组换, 不能只换正文: 不然会出现"第 1 版的正文 + 第 2 版的思考过程"这种
     *   自相矛盾的展示, 用户会以为 AI 前后不一致。
     */
    fun switchTo(i: Int): UiMsg {
        val vs = variants.orEmpty()
        if (vs.size <= 1) return this
        val k = i.coerceIn(0, vs.lastIndex)
        val v = vs[k]
        return copy(
            content = v.content,
            think = v.think,
            thinkMs = v.thinkMs,
            searchQueries = v.searchQueries,
            wikiQueries = v.wikiQueries,
            checkCount = v.checkCount,
            rechecked = v.rechecked,
            recheckIssueCount = v.recheckIssueCount,
            recheckIssues = v.recheckIssues,
            toolTimeline = v.toolTimeline,
            turnId = v.turnId,
            variantIndex = k,
        )
    }
}

/**
 * ★★ **一版回答** (2026-10-05) —— 见 [UiMsg.variants]。
 *
 * 字段是 [UiMsg] 里"随版本变化"的那部分。刻意**不复用 UiMsg 自身**做嵌套:
 * 那样会形成 UiMsg 套 UiMsg 的递归结构, Gson 落盘/读回的风险和心智负担都不值得。
 *
 * ⛔ 必须放在 `data` 包 —— Gson + R8 只保留 data 包的字段名(见本文件顶部注释)。
 */
data class MsgVariant(
    /** 这一版对应的**用户提问原文**(含附件包装; 展示时走 `AiAttach.displayText`) */
    val userText: String = "",
    /** 回答正文 */
    val content: String = "",
    /** 思考过程 */
    val think: String = "",
    /** 思考耗时(毫秒) */
    val thinkMs: Long = 0L,
    val searchQueries: List<String> = emptyList(),
    val wikiQueries: List<String> = emptyList(),
    val checkCount: Int = 0,
    val rechecked: Boolean = false,
    val recheckIssueCount: Int = 0,
    val recheckIssues: List<String> = emptyList(),
    val toolTimeline: List<AiToolEvent> = emptyList(),
    /** 服务端轮次 id (官方通道才有) —— 逐版不同, 否则点赞会点到别的版本上 */
    val turnId: Long = 0L,
)

/**
 * ★★ **AI 消息附件** (2026-10-05) —— 用户上传的文件, 内容随消息一起送模型。
 *
 * ⛔ 必须放在 `data` 包: Gson + R8 只保留 data 包的字段名(见本文件顶部注释),
 *   放到别的包里被混淆掉字段名, 序列化/反序列化直接对不上。
 *
 * ⛔ `content` 是**已解码的文本**(不是 base64): 只收文本类文件(mcfunction/json/txt/md/csv/日志等)。
 *   二进制文件(图片/压缩包)在本功能里**明确拒收** —— 模型这条通道是纯文本,
 *   硬塞 base64 既烧 token 又看不懂, 不如当场告诉用户"这个类型不支持"。
 */
data class Attachment(
    /** 文件名（含扩展名）, 用于在气泡上显示与在 prompt 里标注 */
    val name: String = "",
    /** 原始字节数（用于 UI 显示大小; 截断后仍显示真实大小, 让用户知道被截了） */
    val size: Long = 0L,
    /** 文本正文（可能已被截断, 见 [truncated]） */
    val content: String = "",
    /** 是否因超长被截断（UI 上要如实标出来, 不能装作完整） */
    val truncated: Boolean = false,
)
/** 反馈请求 (自我进化质量信号) */
data class FeedbackReq(val turn_id: Long, val vote: Int, val note: String = "")
data class FeedbackResp(val vote: Int = 0)

data class GenHistory(
    val id: Long = 0,
    @SerializedName("gen_type") val genType: String = "",
    @SerializedName("input_json") val inputJson: String = "",
    @SerializedName("output_command") val outputCommand: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
)

data class HistoryPage(
    val list: List<GenHistory> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

data class Announcement(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    val type: String = "normal",
    val version: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class IdResp(val id: Long = 0)

// 具体 body 类型 (Retrofit 不允许 Map<String, Any> 含通配符)
data class ImportCmdItem(val title: String, val command: String, val tags: String)
data class ImportBody(val commands: List<ImportCmdItem>)

data class ImportResp(val imported: Int = 0)

// ---- 社区: 评论/用户主页/动态广场 ----
data class Comment(
    val id: Long = 0,
    @SerializedName("command_id") val commandId: Long = 0,
    val content: String = "",
    @SerializedName("user_id") val userId: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    @SerializedName("user_role") val userRole: String? = null, // admin 管理员徽章 (意见 #43)
    @SerializedName("user_official") val userOfficial: Int = 0, // 官方认证账号 1=是
    @SerializedName("created_at") val createdAt: String? = null,
)

data class UserProfile(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val avatar: String = "",
    val gender: String = "secret",
    val points: Int = 0,
    @SerializedName("ai_unlocked") val aiUnlocked: Boolean = false,
    val role: String = "user",
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("follower_count") val followerCount: Int = 0,
    @SerializedName("following_count") val followingCount: Int = 0,
    @SerializedName("command_count") val commandCount: Int = 0,
    @SerializedName("is_following") val isFollowing: Boolean = false,
)

data class FeedItem(
    val type: String = "command",   // command | comment
    val id: Long = 0,
    val title: String = "",
    val description: String = "",
    val category: String = "other",
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("like_count") val likeCount: Int = 0,
    @SerializedName("view_count") val viewCount: Int = 0,
    @SerializedName("comment_count") val commentCount: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    val author: String? = null,
    @SerializedName("author_avatar") val authorAvatar: String? = null,
    val content: String = "",            // 评论内容
    @SerializedName("command_id") val commandId: Long? = null,
    @SerializedName("command_title") val commandTitle: String? = null,
)

data class FeedPage(
    val list: List<FeedItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

data class AvatarResp(val avatar: String = "")

// ---- 积分明细 / 站内信 ----
data class PointsLogItem(
    val id: Long = 0,
    val delta: Int = 0,
    val balance: Int = 0,
    val reason: String = "",
    @SerializedName("created_at") val createdAt: String? = null,
)

data class PointsLogPage(
    val list: List<PointsLogItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

// 积分转账结果 (意见 #42)
data class TransferResp(
    val sent: Int = 0,     // 转出积分
    val fee: Int = 0,      // 手续费
    val received: Int = 0, // 对方到账
    val balance: Int? = null, // 转出后余额
)

/* ============================================================================
 *  积分充值（爱发电）
 *
 *  钱在爱发电那边收，App 只做三件事：展示档位、给出"带账号线索的备注"、
 *  付完款把订单号换成积分。所以这里**没有任何支付相关字段** ——
 *  App 不碰钱，也就不会因为支付逻辑出问题把用户的账搞乱。
 * ========================================================================== */

/** 一个可购买的积分包（对应爱发电的一个商品/方案） */
data class RechargePlan(
    val id: Long = 0,
    @SerializedName("plan_id") val planId: String = "",
    val title: String = "",
    @SerializedName("price_cents") val priceCents: Int = 0,
    val points: Int = 0,
    @SerializedName("bonus_points") val bonusPoints: Int = 0,
    /** 积分 + 赠送，也就是实际到账数 */
    @SerializedName("total_points") val totalPoints: Int = 0,
    @SerializedName("item_url") val itemUrl: String = "",
    val note: String = "",
    val active: Boolean = true,
    val sort: Int = 0,
) {
    val priceYuan: String get() = String.format(java.util.Locale.CHINA, "%.2f", priceCents / 100.0)
}

data class RechargePlans(
    val plans: List<RechargePlan> = emptyList(),
    val configured: Boolean = false,
    /** 下单时要粘到爱发电「备注」栏的账号线索，例如 CYANUID-123 */
    val remark: String = "",
    val howto: List<String> = emptyList(),
)

/** 一笔充值订单在「我的充值」里的样子 */
data class RechargeOrder(
    @SerializedName("order_no") val orderNo: String = "",
    @SerializedName("plan_id") val planId: String = "",
    @SerializedName("plan_title") val planTitle: String = "",
    @SerializedName("amount_cents") val amountCents: Int = 0,
    val points: Int = 0,
    val status: String = "",
    @SerializedName("user_id") val userId: Long? = null,
    @SerializedName("credited_at") val creditedAt: String? = null,
    @SerializedName("created_at") val createdAt: String? = null,
) {
    val priceYuan: String get() = String.format(java.util.Locale.CHINA, "%.2f", amountCents / 100.0)
    val statusText: String
        get() = when (status) {
            "credited" -> "已到账"
            "paid" -> "已支付"
            "unmatched" -> "待领取"
            "refunded" -> "已退款"
            else -> status
        }
}

data class RechargeStatus(
    val points: Int = 0,
    /** 是否绑定过爱发电账号：绑定之后充值自动到账，不用再填订单号 */
    val bound: Boolean = false,
    @SerializedName("afdian_user_id") val afdianUserId: String = "",
    val remark: String = "",
    val orders: List<RechargeOrder> = emptyList(),
    val configured: Boolean = false,
)

/** 领取结果 */
data class RechargeClaim(
    val points: Int = 0,
    val balance: Int? = null,
    val bound: Boolean = false,
    @SerializedName("plan_title") val planTitle: String = "",
)

data class MessageItem(
    val id: Long = 0,
    val title: String = "",
    val content: String = "",
    @SerializedName("is_read") val isRead: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class MessagePage(
    val list: List<MessageItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

data class UnreadCount(val count: Int = 0)

// ---- AI 对话历史 ----
data class AiHistoryItem(
    val id: Long = 0,
    val prompt: String = "",
    val result: String = "",
    @SerializedName("cost_points") val costPoints: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
)

data class AiHistoryPage(
    val list: List<AiHistoryItem> = emptyList(),
    val total: Int = 0,
    val page: Int = 1,
    val size: Int = 20,
)

// ---- 更新/点赞 ----
data class UpdateInfo(
    val version: String = "",
    val url: String = "",
    val size: Long = 0,
    // 审计 F1：下载后必须核对哈希，防止镜像/中间人投毒
    val md5: String = "",
    @SerializedName("sha256") val sha256: String = "",
    @SerializedName("min_version") val minVersion: String = "",
)

data class LikeResp(
    val liked: Boolean = false,
    @SerializedName("like_count") val likeCount: Int = 0,
)

// ---- 本地快捷指令(输入法/悬浮窗用) ----
data class QuickCommand(val title: String = "", val command: String = "")

// ---- 娱乐: 五子棋匹配 ----
data class GameMatchResp(
    val matched: Boolean = false,
    val waiting: Boolean = false,
    @SerializedName("room_id") val roomId: String = "",
    val game: String = "gomoku",
    val player: GamePlayer? = null,
    val opponent: GamePlayer? = null,
    @SerializedName("timeout_ms") val timeoutMs: Long = 0,
)

data class GamePlayer(
    val id: Long = 0,
    val nickname: String = "",
    val turn: Int = 1,
    val piece: Int = 1,
)

data class GameStateResp(
    val board: List<List<Int>>? = null,
    val turn: Int = 1,
    val moves: Int = 0,
    val over: Boolean = false,
    val winner: String = "",
    @SerializedName("my_piece") val myPiece: Int = 0,
    val players: List<GamePlayer>? = null,
    val type: String = "pvp",
    @SerializedName("ai_level") val aiLevel: Int = 0,
    val spectator: Boolean = false,
    val code: String = "",
)

data class GameRoomInfo(
    @SerializedName("room_id") val roomId: String = "",
    val code: String = "",
    val game: String = "gomoku",
    val type: String = "pvp",
    @SerializedName("ai_level") val aiLevel: Int = 0,
    @SerializedName("public") val isPublic: Boolean = false,
    val players: List<GamePlayer>? = null,
    val owner: Long = 0,
    val moves: Int = 0,
    val over: Boolean = false,
    val winner: String = "",
    @SerializedName("spectator_count") val spectatorCount: Int = 0,
)

data class GameCreateReq(
    val type: String = "pvp",
    @SerializedName("ai_level") val aiLevel: Int = 0,
    @SerializedName("public") val isPublic: Boolean = true,
)

data class GameCreateResp(
    @SerializedName("room_id") val roomId: String = "",
    val code: String = "",
    val type: String = "pvp",
    @SerializedName("ai_level") val aiLevel: Int = 0,
    @SerializedName("public") val isPublic: Boolean = false,
)

data class GameJoinResp(
    @SerializedName("room_id") val roomId: String = "",
    val code: String = "",
    val type: String = "pvp",
    val player: GamePlayer? = null,
    val spectator: Boolean = false,
    @SerializedName("already_in") val alreadyIn: Boolean = false,
)

// ---- 开学季活动: 分享推广到 QQ 群得积分 ----
data class SchoolShareStatus(
    @SerializedName("act_key") val actKey: String = "",
    val target: Int = 5,
    val reward: Int = 200,
    val count: Int = 0,
    val claimed: Boolean = false,
    val done: Boolean = false,
    val points: Int? = null,
)

data class SchoolShareResp(
    val count: Int = 0,
    val claimed: Boolean = false,
    val gained: Int = 0,
    val points: Int? = null,
    val message: String? = null,
)

// 付费指令购买结果 (意见 #83)
data class PurchaseResp(
    val points: Int? = null,       // 购买后剩余积分
    val purchased: Boolean = false,
)

// ---- 账号中心: 我的 ID / 登录设备 / 绑定账号 ----

data class CurrentDevice(
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("app_version") val appVersion: String = "",
    @SerializedName("last_ip") val lastIp: String = "",
    @SerializedName("last_seen_at") val lastSeenAt: String? = null,
    @SerializedName("login_count") val loginCount: Int = 0,
)

data class AccountOverview(
    val id: Long = 0,
    val username: String = "",
    val nickname: String = "",
    val email: String = "",
    val role: String = "user",
    @SerializedName("is_reviewer") val isReviewer: Boolean = false,
    @SerializedName("is_official") val isOfficial: Boolean = false,
    val status: String = "active",
    val points: Int = 0,
    @SerializedName("created_at") val createdAt: String? = null,
    @SerializedName("last_login_at") val lastLoginAt: String? = null,
    @SerializedName("totp_enabled") val totpEnabled: Boolean = false,
    @SerializedName("device_count") val deviceCount: Int = 0,
    @SerializedName("binding_count") val bindingCount: Int = 0,
    @SerializedName("current_device") val currentDevice: CurrentDevice? = null,
    @SerializedName("current_ip") val currentIp: String = "",
)

data class LoginDevice(
    val id: Long = 0,
    @SerializedName("device_name") val deviceName: String = "",
    @SerializedName("app_version") val appVersion: String = "",
    @SerializedName("last_ip") val lastIp: String = "",
    @SerializedName("login_count") val loginCount: Int = 0,
    @SerializedName("first_seen_at") val firstSeenAt: String? = null,
    @SerializedName("last_seen_at") val lastSeenAt: String? = null,
    val current: Boolean = false,
)

data class DeviceList(
    val total: Int = 0,
    val list: List<LoginDevice> = emptyList(),
)

data class BindingItem(
    val id: Long = 0,
    val platform: String = "",
    @SerializedName("platform_label") val platformLabel: String = "",
    @SerializedName("platform_icon") val platformIcon: String = "",
    @SerializedName("openid_masked") val openidMasked: String = "",
    val nickname: String = "",
    @SerializedName("bound_at") val boundAt: String? = null,
)

data class SupportedBinding(
    val platform: String = "",
    val label: String = "",
    val icon: String = "",
    val available: Boolean = false,
    val howto: String = "",
)

data class BindingList(
    val total: Int = 0,
    val list: List<BindingItem> = emptyList(),
    val supported: List<SupportedBinding> = emptyList(),
)

data class RemovedResp(
    val removed: Int = 0,
)
