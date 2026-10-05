package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Gavel
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.ui.components.AppTopBar

/**
 * 用户协议 (用户政策) 页面
 * 注册/登录即表示已阅读并同意本协议与《隐私政策》
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TermsScreen(nav: NavHostController, onBack: (() -> Unit)? = null) {
    Scaffold(
        topBar = {
            AppTopBar(title = "用户协议", icon = Icons.Default.Gavel, onBack = { onBack?.invoke() ?: nav.popBackStack() })
        },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Text("CyanCodex 用户协议与用户政策", style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.primary)
            Text("更新日期：2026-09-12 · 生效日期：2026-09-12", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("欢迎使用沧蓝工坊开发的 CyanCodex（我的世界指令助手，下称“本应用”）。本协议是你与沧蓝工坊工作室之间就使用本应用达成的约定；同时请阅读《隐私协议》以了解我们如何处理你的信息。注册、登录或继续使用本应用，即表示你已阅读并同意本协议全部内容。",
                style = MaterialTheme.typography.bodyMedium)

            TermsSection("一、账号注册与安全") {
                TermsLine("1. 你需使用真实、可用的信息注册账号，并妥善保管账号与密码；账号下的一切操作视为你本人行为。")
                TermsLine("2. 禁止将账号出售、出租、出借或与他人共享；由此造成的损失由你自行承担。")
                TermsLine("3. 为保障账号与社区安全，本应用会校验客户端签名与设备标识；使用第三方客户端、篡改版或注入工具将被拒绝服务，相关账号可能被限制或封禁。")
                TermsLine("4. 如发现账号被盗用，请立即修改密码并通过意见反馈联系我们。")
            }

            TermsSection("二、用户行为规范") {
                TermsLine("你在本应用内发布、上传、发送的一切内容与行为，不得包含或涉及：")
                TermsLine("· 外挂、作弊、破解、逆向工程、篡改数据、绕过审核或刷取积分等破坏公平与安全的行为；")
                TermsLine("· 冒用他人身份、盗用他人账号、批量注册或恶意占用资源；")
                TermsLine("· 辱骂、人身攻击、色情低俗、暴力威胁、赌博诈骗、政治敏感等违法违规内容；")
                TermsLine("· 广告刷屏、恶意引流、垃圾信息，以及对接口的自动化滥用（超频请求、爬取数据等）；")
                TermsLine("· 侵犯他人知识产权、隐私或其他合法权益的内容。")
                TermsLine("聊天室消息会经过本地敏感词与 AI 检测，违规内容可能被自动或人工撤回，并视情节处理账号。")
            }

            TermsSection("三、内容与知识产权") {
                TermsLine("1. 你上传的指令、教程、插件、评论等内容，你保留著作权，同时授权本应用在平台内展示、检索与分享。")
                TermsLine("2. 本应用源代码以 GNU GPLv3.0 协议开源；应用名称、图标、界面设计等标识归沧蓝工坊工作室所有。")
                TermsLine("3. 《我的世界》(Minecraft) 为 Mojang Studios / Microsoft 的商标与作品，本应用是非官方第三方工具，与之无隶属或授权关系。")
            }

            TermsSection("四、积分与付费服务") {
                TermsLine("1. 积分可通过注册赠送、每日签到、观看公益广告、活动与购买获得，用于 AI 助手按量计费等场景。")
                TermsLine("2. AI 对话按“基础消耗 + Tokens 用量”计费，实际扣费以服务端记录为准；虚拟积分一经消耗不支持退款。")
                TermsLine("3. 通过违规手段获取的积分将被扣除，情节严重者封禁账号。")
            }

            TermsSection("五、服务变更与中断") {
                TermsLine("我们可能因版本更新、服务器维护、迁移或不可抗力等原因调整、暂停或终止部分服务；重大变更会通过公告或站内信通知你。")
            }

            TermsSection("六、免责声明") {
                TermsLine("1. 本应用提供的指令、教程与 AI 生成内容仅供参考，请自行在游戏中测试后使用。")
                TermsLine("2. 因你自身操作不当、第三方平台问题或不可抗力造成的损失，本应用不承担赔偿责任。")
                TermsLine("3. 请遵守你所在服务器与游戏平台的规则，因违规使用指令导致的后果由你自行承担。")
            }

            TermsSection("七、违规处理") {
                TermsLine("视违规情节，我们可采取：提醒警告、删除内容、限制功能、扣除积分、短期封禁或永久封禁账号等措施；涉嫌违法的将依法处理。")
            }

            TermsSection("八、协议更新与联系") {
                TermsLine("1. 本协议可能适时更新，更新后在本页面展示；继续使用即视为接受更新后的协议。")
                TermsLine("2. 如有疑问或申诉，可通过「设置 → 意见申诉」、官方 QQ 群或邮箱 cyancodex@blueworkshop.top 联系我们。")
            }

            Spacer(Modifier.height(8.dp))
            Text("© 2026 世纪集团 · 沧蓝工坊工作室", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun TermsSection(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            content()
        }
    }
}

@Composable
private fun TermsLine(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
