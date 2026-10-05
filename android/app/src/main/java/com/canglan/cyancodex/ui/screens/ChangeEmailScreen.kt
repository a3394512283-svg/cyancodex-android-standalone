package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AlternateEmail
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 修改邮箱 (需邮箱验证码) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangeEmailScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var email by remember { mutableStateOf("") }
    var code by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }
    var countdown by remember { mutableStateOf(0) }

    fun sendCode() {
        if (!android.util.Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()) {
            msg = "邮箱格式不正确"
            return
        }
        scope.launch {
            busy = true; msg = ""
            val r = safeApi { ApiClient.get().sendEmailCode(mapOf("email" to email.trim())) }
            busy = false
            if (r.ok) {
                msg = "验证码已发送到 $email"
                countdown = 60
                while (countdown > 0) {
                    delay(1000)
                    countdown--
                }
            } else msg = r.error ?: "发送失败"
        }
    }

    fun submit() {
        if (email.isBlank() || code.isBlank()) { msg = "请填写邮箱和验证码"; return }
        scope.launch {
            busy = true; msg = ""
            val r = safeApi { ApiClient.get().changeEmail(mapOf("email" to email.trim(), "email_code" to code.trim())) }
            busy = false
            if (r.ok) {
                android.widget.Toast.makeText(ctx, "邮箱修改成功", android.widget.Toast.LENGTH_SHORT).show()
                nav.popBackStack()
            } else msg = r.error ?: "修改失败"
        }
    }

    Scaffold(topBar = {
        AppTopBar(title = "修改邮箱", icon = Icons.Default.AlternateEmail, onBack = { nav.popBackStack() })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = email, onValueChange = { email = it },
                label = { Text("新邮箱") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
                OutlinedTextField(value = code, onValueChange = { code = it },
                    label = { Text("邮箱验证码") }, singleLine = true, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(8.dp))
                GlassActionButton(onClick = { sendCode() }, enabled = countdown == 0 && !busy) {
                    Text(if (countdown > 0) "${countdown}s" else "发送验证码")
                }
            }
            if (msg.isNotBlank()) {
                Text(msg, color = if (msg.startsWith("验证码已发送")) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
            GlassActionButton(filled = true, onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "提交中…" else "确认修改")
            }
        }
    }
}
