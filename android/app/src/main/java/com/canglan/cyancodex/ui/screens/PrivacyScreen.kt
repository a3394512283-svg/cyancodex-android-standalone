package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PrivacyTip
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.ui.components.AppTopBar

/** 隐私协议页面 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PrivacyScreen(nav: NavHostController, onBack: (() -> Unit)? = null) {
    Scaffold(
        topBar = {
            AppTopBar(title = "隐私协议", icon = Icons.Default.PrivacyTip, onBack = { onBack?.invoke() ?: nav.popBackStack() })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("CyanCodex 隐私协议", style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary)
            Text("更新日期：2026-10-17 · 生效日期：2026-10-17", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("欢迎使用沧蓝工坊（CyanCodex，下称“本应用”）。我们非常重视您的个人信息与隐私安全，本协议向您说明本应用收集、使用、存储和保护信息的方式。", style = MaterialTheme.typography.bodyMedium)

            PrivacySection("一、我们收集的信息") {
                PrivacyLine("1. 账号信息：注册/登录时您提供的用户名、邮箱（可选）及加密存储的登录凭据，用于账号体系与登录态。")
                PrivacyLine("2. 设备信息：设备唯一标识（Android ID）、App 包名与安装包签名摘要，用于账号绑定、邀请码绑定与第三方客户端/篡改检测（封禁违规客户端以保护账号安全）。")
                PrivacyLine("3. 使用数据：您上传/发布的指令、评论、聊天内容、积分变动记录、AI 对话记录等，用于提供社区功能与按量计费服务。")
                PrivacyLine("4. 意见申诉：您主动提交的意见内容及附带的图片（截图/报错界面），仅用于问题处理。")
                PrivacyLine("5. 设置备份：您主动执行「备份到云端」时，本机的设置项与偏好（不含对话记录、不含图片）会用您的口令加密后上传，用于换机恢复。")
                PrivacyLine("6. AI 对话云同步：**默认开启**，可在「设置 → 云端同步」关闭。开启后，您的 AI 对话记录（含您输入的提问与 AI 的回复）会上传至我们部署的服务器，用于在多台设备与网页版之间同步。该数据仅凭您的账号令牌读取，不对外公开。")
                PrivacyLine("7. 分享链接：您主动点击「分享链接」时，被分享的那条内容会保存到服务器并生成一个**公开网页**，任何知道该链接的人都能查看。请勿分享含个人信息的内容。")
            }

            PrivacySection("二、信息的使用") {
                PrivacyLine("· 提供指令库、聊天、好友私聊、插件库、生成器、AI 助手等核心功能；")
                PrivacyLine("· 维护账号安全：登录校验、设备绑定、篡改检测与违规封禁；")
                PrivacyLine("· 积分结算：签到、观看公益广告、转账（收取手续费）、AI 按量扣费并记录流水；")
                PrivacyLine("· 公益信息展示：底部的「宝贝回家」寻亲信息来自公开公益数据接口，图片经我们的服务器中转，不含你的个人信息；")
                PrivacyLine("· 内容审核：上传内容经安全扫描与人工审核，违规内容将被下架或处理。")
            }

            PrivacySection("三、信息存储与安全") {
                PrivacyLine("1. 数据存储于我们部署的服务器（位于境内），采用密码加密存储、HTTPS 加密传输；")
                PrivacyLine("2. 登录凭据等敏感信息在本地以系统级加密（Android Keystore）保存；")
                PrivacyLine("3. 设置备份在上传前会先用您的口令做 AES-GCM 加密并压缩，服务器只保存密文，口令不离开您的设备——忘记口令则无法恢复，这是设计如此；")
                PrivacyLine("4. AI 对话云同步的内容以明文存储于服务器数据库中（传输过程加密），仅凭您的账号令牌可读取；")
                PrivacyLine("5. 我们采取合理的技术与管理措施防止信息泄露、丢失或被篡改。")
            }

            PrivacySection("四、第三方与外部链接") {
                PrivacyLine("· AI 功能默认使用平台内置 AI 服务；若您选择“自备 API”，对话将直接发送至您填写的第三方服务地址，由其处理；")
                PrivacyLine("· 本应用内 QQ 群、赞助（爱发电）等为外部平台链接，跳转后由对应平台按其隐私政策处理您的信息；")
                PrivacyLine("· 我们不会向任何第三方出售您的个人信息。")
            }

            PrivacySection("五、云服务与数据留存") {
                PrivacyLine("· 设置备份：保存 180 天，超期自动删除；您可随时在「设置 → 云端备份」覆盖它或停止使用该功能。")
                PrivacyLine("· AI 对话（云同步）：未删除的长期保留；您在 App 内删除会话后，该删除会同步到服务器，服务器另保留一条删除标记约 1 年后清除——它的唯一用途是防止其他设备把已删会话重新上传。")
                PrivacyLine("· 分享链接：默认 90 天后自动失效；您可通过「设置 → 意见申诉」联系我们提前下架，下架后链接立即无法访问。")
                PrivacyLine("· 调用记录与审计日志：用于额度统计与安全排查，保留 30 天。")
                PrivacyLine("· 关闭「设置 → 云端同步」后，App 不再上传新的对话记录；如需删除已经同步的内容，请通过「设置 → 意见申诉」联系我们。")
            }

            PrivacySection("六、您的权利") {
                PrivacyLine("· 您可随时在「我的」中查看与修改个人资料、修改密码/邮箱；")
                PrivacyLine("· 您可通过「设置 → 意见申诉」联系我们删除账号及相关数据；")
                PrivacyLine("· 您可以自主选择是否开启消息通知、悬浮窗等权限，关闭不影响核心功能使用；")
                PrivacyLine("· 您可以随时关闭「会话云同步」与「设置备份」，关闭后相关数据不再上传。")
            }

            PrivacySection("七、未成年人保护") {
                PrivacyLine("本应用面向 Minecraft（我的世界）玩家，若您为未成年人，请在监护人指导下使用，并在监护人同意下提供必要信息。")
            }

            PrivacySection("八、协议更新") {
                PrivacyLine("我们可能适时更新本协议，更新后将在本页面展示最新版本。重大变更会通过公告等方式通知您。继续使用本应用即视为同意更新后的协议。")
            }

            PrivacySection("九、联系我们") {
                PrivacyLine("如对本协议有任何疑问，可通过「设置 → 意见申诉」或加入官方 QQ 群与我们联系。")
            }

            Spacer(Modifier.height(8.dp))
            Text("© 2026 世纪集团 · 沧蓝工坊工作室", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun PrivacySection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun PrivacyLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
