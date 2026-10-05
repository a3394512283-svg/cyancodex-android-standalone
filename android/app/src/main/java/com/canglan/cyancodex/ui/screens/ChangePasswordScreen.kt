package com.canglan.cyancodex.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.launch

/** 修改密码 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChangePasswordScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    var old by remember { mutableStateOf("") }
    var new by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun submit() {
        if (old.isBlank() || new.isBlank() || confirm.isBlank()) { msg = "请填写完整"; return }
        if (new.length < 6) { msg = "新密码至少 6 位"; return }
        if (new != confirm) { msg = "两次输入的新密码不一致"; return }
        scope.launch {
            busy = true; msg = ""
            val r = safeApi { ApiClient.get().changePassword(mapOf("old_password" to old, "new_password" to new)) }
            busy = false
            if (r.ok) {
                // 改密后服务端已作废所有旧令牌, 用返回的新令牌续上当前设备登录态
                r.data?.token?.let { com.canglan.cyancodex.ui.AppState.updateToken(it) }
                android.widget.Toast.makeText(ctx, "密码修改成功", android.widget.Toast.LENGTH_SHORT).show()
                nav.popBackStack()
            } else msg = r.error ?: "修改失败"
        }
    }

    Scaffold(topBar = {
        AppTopBar(title = "修改密码", icon = Icons.Default.Lock, onBack = { nav.popBackStack() })
    }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedTextField(value = old, onValueChange = { old = it },
                label = { Text("原密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = new, onValueChange = { new = it },
                label = { Text("新密码 (至少6位)") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            OutlinedTextField(value = confirm, onValueChange = { confirm = it },
                label = { Text("确认新密码") }, singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth())
            if (msg.isNotBlank()) {
                Text(msg, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            GlassActionButton(filled = true, onClick = { submit() }, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (busy) "提交中…" else "确认修改")
            }
        }
    }
}
