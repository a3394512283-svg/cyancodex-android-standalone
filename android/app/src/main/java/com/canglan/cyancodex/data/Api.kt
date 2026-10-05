package com.canglan.cyancodex.data

import com.canglan.cyancodex.BuildConfig
import com.google.gson.annotations.SerializedName
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import okhttp3.ResponseBody.Companion.toResponseBody
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import retrofit2.http.*
import java.util.concurrent.TimeUnit

interface ApiService {

    // 认证
    @POST("api/auth/register")
    suspend fun register(@Body body: Map<String, String>): ApiResp<LoginResp>

    @POST("api/auth/login")
    suspend fun login(@Body body: Map<String, String>): ApiResp<LoginResp>

    @GET("api/auth/captcha")
    suspend fun captcha(): ApiResp<CaptchaResp>

    @POST("api/auth/send-email-code")
    suspend fun sendEmailCode(@Body body: Map<String, String>): ApiResp<Any?>

    @GET("api/auth/me")
    suspend fun me(): ApiResp<User>

    @PUT("api/auth/profile")
    suspend fun updateProfile(@Body body: Map<String, String>): ApiResp<Any?>

    @PUT("api/auth/password")
    suspend fun changePassword(@Body body: Map<String, String>): ApiResp<ChangePwdResp>

    @PUT("api/auth/email")
    suspend fun changeEmail(@Body body: Map<String, String>): ApiResp<Any?>

    // 指令库
    @GET("api/commands")
    suspend fun commands(
        @Query("category") category: String?,
        @Query("source") source: String?,
        @Query("quality") quality: String?,
        @Query("q") q: String?,
        @Query("mine") mine: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResp<CommandPage>

    @GET("api/commands/{id}")
    suspend fun commandDetail(@Path("id") id: Long): ApiResp<Command>

    @POST("api/commands")
    suspend fun uploadCommand(@Body body: CommandBody): ApiResp<IdResp>

    @POST("api/commands/import")
    suspend fun importCommands(@Body body: ImportBody): ApiResp<ImportResp>

    @PUT("api/commands/{id}")
    suspend fun updateCommand(@Path("id") id: Long, @Body body: CommandBody): ApiResp<Any?>

    @DELETE("api/commands/{id}")
    suspend fun deleteCommand(@Path("id") id: Long): ApiResp<Any?>

    @POST("api/commands/{id}/like")
    suspend fun likeCommand(@Path("id") id: Long): ApiResp<LikeResp>

    @POST("api/commands/{id}/favorite")
    suspend fun toggleFavorite(@Path("id") id: Long): ApiResp<FavResp>

    @GET("api/commands/favorites/list")
    suspend fun favorites(): ApiResp<List<Command>>

    @POST("api/commands/{id}/purchase")
    suspend fun purchaseCommand(@Path("id") id: Long): ApiResp<PurchaseResp>

    @GET("api/commands/meta/categories")
    suspend fun categories(): ApiResp<List<Category>>

    // 教程
    @GET("api/tutorials")
    suspend fun tutorials(
        @Query("category") category: String?,
        @Query("source") source: String?,
        @Query("q") q: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResp<TutorialPage>

    @GET("api/tutorials/{id}")
    suspend fun tutorialDetail(@Path("id") id: Long): ApiResp<Tutorial>

    @POST("api/tutorials")
    suspend fun uploadTutorial(@Body body: Map<String, String>): ApiResp<IdResp>

    // 积分订单 (已废弃, 改为签到得积分)
    // 签到
    @GET("api/checkin")
    suspend fun checkinStatus(): ApiResp<CheckinStatus>

    @POST("api/checkin")
    suspend fun checkin(): ApiResp<CheckinStatus>

    // 积分明细
    @GET("api/points")
    suspend fun pointsLog(@Query("page") page: Int, @Query("size") size: Int): ApiResp<PointsLogPage>

    // 积分转账 (意见 #42, 手续费 20%)
    @POST("api/points/transfer")
    suspend fun pointsTransfer(@Body body: Map<String, String>): ApiResp<TransferResp>

    // 积分充值 (爱发电): 档位 / 我的充值状态 / 用订单号领取 / 刷新
    @GET("api/recharge/plans")
    suspend fun rechargePlans(): ApiResp<RechargePlans>

    @GET("api/recharge/status")
    suspend fun rechargeStatus(): ApiResp<RechargeStatus>

    @POST("api/recharge/claim")
    suspend fun rechargeClaim(@Body body: Map<String, String>): ApiResp<RechargeClaim>

    @POST("api/recharge/refresh")
    suspend fun rechargeRefresh(): ApiResp<RechargeStatus>

    // 站内信
    @GET("api/messages")
    suspend fun messages(@Query("page") page: Int, @Query("size") size: Int): ApiResp<MessagePage>

    @GET("api/messages/unread-count")
    suspend fun unreadMessages(): ApiResp<UnreadCount>

    @POST("api/messages/{id}/read")
    suspend fun markMessageRead(@Path("id") id: Long): ApiResp<Any?>

    // 账号中心: 我的 ID / 最近登录设备 / 绑定账号
    @GET("api/account/overview")
    suspend fun accountOverview(): ApiResp<AccountOverview>

    @GET("api/account/devices")
    suspend fun accountDevices(@Query("size") size: Int = 20): ApiResp<DeviceList>

    @GET("api/account/bindings")
    suspend fun accountBindings(): ApiResp<BindingList>

    @POST("api/account/bindings/{id}/unbind")
    suspend fun unbindAccount(@Path("id") id: Long): ApiResp<RemovedResp>

    /** 给当前设备命名 (只更新自己那台设备的显示名) */
    @POST("api/account/device/rename")
    suspend fun renameDevice(@Body body: Map<String, String>): ApiResp<SimpleOk>

    // AI
    @GET("api/ai/status")
    suspend fun aiStatus(): ApiResp<AiStatus>

    @POST("api/ai/unlock")
    suspend fun aiUnlock(): ApiResp<SimpleOk>

    @POST("api/ai/usage")
    suspend fun aiUsage(@Body body: AiUsageReq): ApiResp<IdResp>

    // AI 对话历史
    @GET("api/ai/history")
    suspend fun aiHistory(@Query("page") page: Int, @Query("size") size: Int): ApiResp<AiHistoryPage>

    // AI 对话代理: 所有对话走服务器(自定义 Key 或平台 AI), 服务器记录全部历史
    @POST("api/ai/proxy")
    suspend fun aiProxy(@Body body: Map<String, String>): ApiResp<AiProxyResp>

    // AI 回答反馈 (有用/没用): 服务器据此做自我进化的质量筛选
    @POST("api/ai/feedback")
    suspend fun aiFeedback(@Body body: FeedbackReq): ApiResp<FeedbackResp>

    // 生成历史
    @GET("api/history")
    suspend fun history(@Query("type") type: String?, @Query("page") page: Int, @Query("size") size: Int): ApiResp<HistoryPage>

    @POST("api/history")
    suspend fun saveHistory(@Body body: Map<String, String>): ApiResp<IdResp>

    @DELETE("api/history/{id}")
    suspend fun deleteHistory(@Path("id") id: Long): ApiResp<Any?>

    // 公告
    @GET("api/announcements")
    suspend fun announcements(@Query("type") type: String?): ApiResp<List<Announcement>>

    // 社区: 头像上传
    @POST("api/auth/avatar")
    suspend fun uploadAvatar(@Body body: Map<String, String>): ApiResp<AvatarResp>

    // 社区: 评论
    @GET("api/comments/command/{id}")
    suspend fun comments(@Path("id") id: Long): ApiResp<List<Comment>>

    @POST("api/comments/command/{id}")
    suspend fun addComment(@Path("id") id: Long, @Body body: Map<String, String>): ApiResp<IdResp>

    @DELETE("api/comments/{id}")
    suspend fun deleteComment(@Path("id") id: Long): ApiResp<Any?>

    // 社区: 用户主页 / 关注
    @GET("api/users/{id}")
    suspend fun userProfile(@Path("id") id: Long): ApiResp<UserProfile>

    @GET("api/users/{id}/commands")
    suspend fun userCommands(@Path("id") id: Long, @Query("page") page: Int, @Query("size") size: Int): ApiResp<List<Command>>

    @POST("api/users/{id}/follow")
    suspend fun follow(@Path("id") id: Long): ApiResp<FavResp>

    @DELETE("api/users/{id}/follow")
    suspend fun unfollow(@Path("id") id: Long): ApiResp<FavResp>

    // 社区: 动态广场
    @GET("api/feed")
    suspend fun feed(@Query("page") page: Int, @Query("size") size: Int): ApiResp<FeedPage>

    // 更新: 服务器权威的最新版本/下载地址
    @GET("api/update/latest")
    suspend fun latestUpdate(): ApiResp<UpdateInfo>

    // 意见/申诉: 提交 (category: feedback=意见 appeal=申诉)
    @POST("api/feedback")
    suspend fun submitFeedback(@Body body: Map<String, String>): ApiResp<IdResp>

    // 两步验证 (TOTP)
    @POST("api/auth/logout")
    suspend fun logout(@Header("Authorization") auth: String, @Body body: LogoutReq): ApiResp<SimpleOk>

    @GET("api/auth/2fa/status")
    suspend fun totpStatus(): ApiResp<TotpStatus>

    @POST("api/auth/2fa/setup")
    suspend fun totpSetup(@Body body: Map<String, String>): ApiResp<TotpSetup>

    @POST("api/auth/2fa/verify")
    suspend fun totpVerify(@Body body: Map<String, String>): ApiResp<SimpleOk>

    @POST("api/auth/2fa/disable")
    suspend fun totpDisable(@Body body: Map<String, String>): ApiResp<SimpleOk>

    // 指令效果图: 上传 base64 图片
    @POST("api/commands/{id}/image")
    suspend fun uploadCommandImage(@Path("id") id: Long, @Body body: Map<String, String>): ApiResp<ImageResp>

    @DELETE("api/commands/{id}/image")
    suspend fun deleteCommandImage(@Path("id") id: Long): ApiResp<Any?>

    // 客户端检测: 自检发现第三方客户端/篡改后主动上报, 服务器封禁当前账号
    @POST("api/auth/report-tamper")
    suspend fun reportTamper(@Body body: Map<String, String>): ApiResp<Any?>

    // 聊天室 (意见 #36)
    @GET("api/chat")
    suspend fun chatMessages(@Query("limit") limit: Int): ApiResp<List<ChatMessage>>

    @GET("api/chat/after")
    suspend fun chatAfter(@Query("after_id") afterId: Long): ApiResp<List<ChatMessage>>

    @POST("api/chat")
    suspend fun sendChat(@Body body: Map<String, String>): ApiResp<ChatMessage?>

    @POST("api/chat/report")
    suspend fun reportChat(@Body body: ReportChatReq): ApiResp<Any?>

    @DELETE("api/chat/{id}")
    suspend fun deleteChat(@Path("id") id: Long): ApiResp<Any?>

    // 开学季活动 (分享推广到 QQ 群得积分)
    @GET("api/activity/school-share")
    suspend fun schoolShareStatus(): ApiResp<SchoolShareStatus>

    @POST("api/activity/school-share")
    suspend fun schoolShareConfirm(): ApiResp<SchoolShareResp>

    // 娱乐: 五子棋匹配
    @POST("api/game/match")
    suspend fun gameMatch(@Body body: Map<String, String>): ApiResp<com.canglan.cyancodex.data.GameMatchResp>

    @GET("api/game/match/status")
    suspend fun gameMatchStatus(): ApiResp<com.canglan.cyancodex.data.GameMatchResp>

    @GET("api/game/rooms")
    suspend fun gameRooms(): ApiResp<List<com.canglan.cyancodex.data.GameRoomInfo>>

    @POST("api/game/rooms")
    suspend fun gameCreateRoom(@Body body: GameCreateReq): ApiResp<com.canglan.cyancodex.data.GameCreateResp>

    @POST("api/game/rooms/join")
    suspend fun gameJoinRoom(@Body body: Map<String, String>): ApiResp<com.canglan.cyancodex.data.GameJoinResp>

    @POST("api/game/{roomId}/kick")
    suspend fun gameKick(@Path("roomId") roomId: String, @Body body: Map<String, Long>): ApiResp<Any?>

    @POST("api/game/{roomId}/ai-move")
    suspend fun gameAiMove(@Path("roomId") roomId: String): ApiResp<com.canglan.cyancodex.data.GameStateResp>

    @GET("api/game/{roomId}/state")
    suspend fun gameState(@Path("roomId") roomId: String): ApiResp<com.canglan.cyancodex.data.GameStateResp>

    @POST("api/game/{roomId}/move")
    suspend fun gameMove(@Path("roomId") roomId: String, @Body body: Map<String, Int>): ApiResp<com.canglan.cyancodex.data.GameStateResp>

    @POST("api/game/{roomId}/leave")
    suspend fun gameLeave(@Path("roomId") roomId: String): ApiResp<Any?>

    // 好友 (意见 #38)
    @GET("api/friends")
    suspend fun friends(@Query("status") status: String?): ApiResp<List<FriendItem>>

    @POST("api/friends/request")
    suspend fun friendRequest(@Body body: Map<String, String>): ApiResp<Any?>

    @POST("api/friends/handle")
    suspend fun friendHandle(@Body body: Map<String, String>): ApiResp<Any?>

    @POST("api/friends/remove")
    suspend fun friendRemove(@Body body: Map<String, String>): ApiResp<Any?>

    // 私聊 (意见 #38)
    @GET("api/dm/conversations")
    suspend fun dmConversations(): ApiResp<List<Conversation>>

    @GET("api/dm/messages")
    suspend fun dmMessages(@Query("peer_id") peerId: Long, @Query("before_id") beforeId: Long): ApiResp<List<DmMessage>>

    @GET("api/dm/messages/after")
    suspend fun dmAfter(@Query("peer_id") peerId: Long, @Query("after_id") afterId: Long): ApiResp<List<DmMessage>>

    @POST("api/dm/messages")
    suspend fun sendDm(@Body body: Map<String, String>): ApiResp<IdResp?>

    // 插件库 (意见 #37; 意见 #86 增加适用版本 platform: java/bedrock)
    @GET("api/plugins")
    suspend fun plugins(
        @Query("status") status: String?,
        @Query("platform") platform: String?,
        @Query("page") page: Int,
        @Query("size") size: Int,
    ): ApiResp<PluginPage>

    @POST("api/plugins")
    suspend fun uploadPlugin(@Body body: Map<String, String>): ApiResp<IdResp>

    @GET("api/plugins/{id}")
    suspend fun pluginDetail(@Path("id") id: Long): ApiResp<Plugin>

    // 看公益广告得积分
    @GET("api/ad/status")
    suspend fun adStatus(): ApiResp<AdStatus>

    @POST("api/ad/start")
    suspend fun adStart(): ApiResp<AdStartResp>

    @POST("api/ad/claim")
    suspend fun adClaim(@Body body: Map<String, String>): ApiResp<AdClaimResp>

    // 宝贝回家 (公益寻亲信息, 免登录)
    @GET("api/bh")
    suspend fun babyHome(): ApiResp<BabyHomeResp>

    // 快捷查询 ID: 中文名 -> 基岩版 ID (内置参考表)
    @GET("api/mcref/search")
    suspend fun mcRefSearch(
        @Query("q") q: String,
        @Query("kind") kind: String?,
        @Query("limit") limit: Int,
    ): ApiResp<McRefList>

    @GET("api/mcref/kinds")
    suspend fun mcRefKinds(): ApiResp<McRefKinds>
}

data class ImageResp(val image: String? = null)

data class TotpStatus(val enabled: Boolean = false)
data class TotpSetup(val secret: String = "", @SerializedName("otpauth_uri") val otpauthUri: String = "")
/** 修改密码返回: 改密后服务端会作废旧令牌, 这里带回当前设备的新令牌 */
data class ChangePwdResp(val message: String? = null, val token: String? = null)
// 具体 body 类型: Retrofit 不接受 Map<String, Any?> 作为 @Body (Kotlin 协变会变成通配符)
data class LogoutReq(val all: Boolean = false)
data class ReportChatReq(@SerializedName("message_id") val messageId: Long, val reason: String = "")
data class AiUsageReq(val prompt: String = "", val result: String = "", val costPoints: Int = 0)
data class AiProxyResp(
    val content: String? = null,
    val cost: Int = 0,        // 本次消耗积分 (平台 AI)
    val tokens: Int = 0,      // 本次消耗 tokens
    val balance: Int? = null, // 剩余积分 (平台 AI)
    @SerializedName("turn_id") val turnId: Long = 0L,   // 服务端对话轮次 id (用于反馈)
    val cached: Boolean = false,                        // 命中经验库直接回答 (不扣积分)
)

object ApiClient {
    @Volatile private var service: ApiService? = null

    fun get(): ApiService {
        val cached = service
        if (cached != null) return cached
        synchronized(this) {
            return service ?: build().also { service = it }
        }
    }

    /** 当用户修改了 API 地址后调用, 重建客户端 */
    fun reset() {
        DebugStore.log("Api", "重建客户端, baseUrl=${Session.baseUrl}")
        synchronized(this) { service = null }
    }

    /**
     * 这个 host 是不是**我们自己的服务器** —— 决定要不要带用户注入的 cookie。
     *
     * ★★ 这是安全闸门, 判据必须**从严**:
     *   - 只认 `Session.baseUrl` 的 host (即当前生效的服务器域名);
     *   - 空 host 一律不放行。
     *
     * 为什么不做成"任意 host 都带": 用户还配了**自备 AI 域名 / 搜索引擎域名**,
     * 那些是第三方。cookie 是账号级凭证, 发到第三方 = 主动泄露。
     * 本函数是最后一道防线 —— 改它之前先想清楚这句话。
     */
    private fun isOwnHost(host: String): Boolean {
        if (host.isBlank()) return false
        val own = runCatching {
            java.net.URI(Session.baseUrl).host?.lowercase()
        }.getOrNull() ?: return false
        return own.isNotBlank() && host == own
    }

    private fun build(): ApiService {
        val base = Session.baseUrl
        DebugStore.log("Api", "初始化 Retrofit, baseUrl=$base" + if (Session.usingCustomBaseUrl) " (调试自定义)" else " (内置默认)")
        val authInterceptor = Interceptor { chain ->
            val req = chain.request()
            val b = req.newBuilder()
            Session.token?.let { b.header("Authorization", "Bearer $it") }
            b.header("Accept", "application/json")
            // ★★ 手动注入的 Cookie (隐藏入口, 2026-10-05) —— 与 Authorization 并行, 两者都带。
            //   ⛔⛔ **只给自己域名**。这是硬约束: Retrofit 的 baseUrl 是自家服务器, 但
            //      Api 客户端**只发往自家服务器**(AI 直连走的是 AiSearch/AiClient 各自的 OkHttp,
            //      不经过这里), 所以这里天然安全。但为了防将来有人把这个 client 复用出去,
            //      仍然显式判一次 host —— 防御性写法, 别删。
            //   ★★ 2026-10-05 再加一道: 必须 `DebugStore.cookieEnabled` —— 调试面板总开关(仿开发者选项)
            //      关掉后 cookie **不再注入**。用户要的"关闭开关"要能真的把这条能力关死,
            //      而不是只藏起 UI。判 cookieEnabled(= active = 解锁 且 总开关开着):
            //      "开调试模式就开" —— 与用户在登录页看到入口的条件**完全一致**, 不会出现
            //      "入口看得见却注不进去"这种自相矛盾。
            if (DebugStore.cookieEnabled) {
                Session.cookie?.let { ck ->
                    val host = req.url.host.lowercase()
                    if (isOwnHost(host)) b.header("Cookie", ck)
                }
            }
            b.header("X-Device-Id", Session.deviceId)
            // 设备名可能是中文 (用户自己起的名字/国产机型), HTTP 头只能放 ASCII, 先做百分号编码,
            // 服务器侧 decodeURIComponent 还原 (否则 OkHttp 会直接抛异常导致所有请求失败)
            b.header("X-Device-Name", android.net.Uri.encode(Session.deviceName))
            b.header("X-App-Version", BuildConfig.VERSION_NAME)
            // 客户端检测: 每次请求携带当前 APK 签名 SHA-256, 服务器与白名单比对,
            // 不符 -> 账号封禁; 本地拿不到签名(异常环境)时也上报该标识
            b.header("X-App-Signature", TamperDetector.ownSignature() ?: "unknown")
            chain.proceed(b.build())
        }
        // token 失效(401)时自动清理会话: 仅"已登录的请求"失效才登出;
        // 登录接口的 401 是密码错误, 不触发登出
        val authFailInterceptor = Interceptor { chain ->
            val req = chain.request()
            val resp = chain.proceed(req)
            if (resp.code == 401 && req.header("Authorization") != null) {
                Session.logout()
            }
            resp
        }
        // 服务器业务错误(非2xx + {ok:false} JSON)改写为 200, 让友好错误文案透传到 UI
        // 401: 已登录请求 -> 提示登录过期(登出由 authFailInterceptor 处理);
        //      登录请求(无 Authorization) -> 透传服务器文案(如"用户名或密码错误")
        val errorBodyInterceptor = Interceptor { chain ->
            val req = chain.request()
            val resp = chain.proceed(req)
            if (!resp.isSuccessful) {
                val ct = resp.headers["Content-Type"] ?: ""
                if (ct.contains("json", ignoreCase = true)) {
                    val raw = resp.body?.string()
                    if (!raw.isNullOrBlank()) {
                        try {
                            val obj = org.json.JSONObject(raw)
                            if (!obj.optBoolean("ok", true)) {
                                val body = if (resp.code == 401 && req.header("Authorization") != null) {
                                    org.json.JSONObject().put("ok", false)
                                        .put("error", "登录已过期，请重新登录").toString()
                                } else raw
                                return@Interceptor resp.newBuilder()
                                    .code(200).message("OK")
                                    .body(body.toResponseBody(resp.body?.contentType()))
                                    .build()
                            }
                        } catch (_: Exception) {}
                    }
                }
            }
            resp
        }
        // 调试面板: ① 按规则改写接口路径 ② 记录 method / URL / 状态码 / 耗时 / 错误
        // (未解锁时零开销 —— 第一行就 return)
        // ★ 2026-10-05: 判据从 unlocked 改成 active（解锁 且 总开关开着）——
        //   用户要的"仿开发者选项的关闭开关"必须能**真正停掉**路径改写与请求记录,
        //   而不是只把面板 UI 藏起来。详见 DebugStore.active。
        val debugInterceptor = Interceptor { chain ->
            if (!DebugStore.active) return@Interceptor chain.proceed(chain.request())

            // ① 路径改写: /api/commands -> /api/abc (前缀匹配, query 原样保留)
            var req = chain.request()
            val newPath = DebugStore.rewritePath(req.url.encodedPath)
            if (newPath != null) {
                // ★ 必须用 encodedPath 重建, 且 query 单独保留 ——
                // 直接改 URL 的 path 会把 ?a=b 一起丢掉
                val u = req.url.newBuilder().encodedPath(newPath).build()
                // ★ 动的是"最终 URL", 所以 authInterceptor 之后加的 header 不受影响;
                //   但 X-App-Signature 之类是按 baseUrl 算的, 改路径不影响签名
                DebugStore.log("Debug", "路径改写 ${req.url.encodedPath} -> $newPath")
                req = req.newBuilder().url(u).build()
            }

            val t0 = System.currentTimeMillis()
            try {
                val resp = chain.proceed(req)
                DebugStore.record(
                    DebugStore.ReqRecord(
                        time = t0,
                        method = req.method,
                        url = req.url.toString(),
                        code = resp.code,
                        costMs = System.currentTimeMillis() - t0,
                    )
                )
                resp
            } catch (e: Exception) {
                DebugStore.record(
                    DebugStore.ReqRecord(
                        time = t0,
                        method = req.method,
                        url = req.url.toString(),
                        code = 0,
                        costMs = System.currentTimeMillis() - t0,
                        error = e.javaClass.simpleName + ": " + (e.message ?: ""),
                    )
                )
                throw e
            }
        }
        val client = OkHttpClient.Builder()
            .dns(ServerDns)                       // 直连高防主服务器 IP (备用线路自动回退)
            .connectTimeout(25, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .writeTimeout(45, TimeUnit.SECONDS)
            .addInterceptor(debugInterceptor)
            .addInterceptor(errorBodyInterceptor)
            .addInterceptor(authInterceptor)
            .addInterceptor(authFailInterceptor)
            .build()
        return Retrofit.Builder()
            .baseUrl(base)
            .client(client)
            .addConverterFactory(GsonConverterFactory.create())
            .build()
            .create(ApiService::class.java)
    }
}
