package com.canglan.cyancodex.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Female
import androidx.compose.material.icons.filled.Male
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Save
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.AppState
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.AvatarIcon
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.TopBarTextAction
import com.canglan.cyancodex.ui.components.avatarMap
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 个人中心: 修改头像 / 性别 / 昵称(必填) */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProfileEditScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val user = AppState.user
    var nickname by remember { mutableStateOf(user?.nickname ?: "") }
    var avatar by remember { mutableStateOf(user?.avatar?.ifBlank { "person" } ?: "person") }
    var gender by remember { mutableStateOf(user?.gender ?: "secret") }
    var saving by remember { mutableStateOf(false) }
    var uploading by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf("") }

    fun pickAvatar(uri: Uri) {
        scope.launch {
            uploading = true
            msg = ""
            try {
                val bmp = withContext(Dispatchers.IO) {
                    try {
                        android.util.Log.d("CyanCodex", "picked uri: $uri")
                        val bytes = ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                        if (bytes == null || bytes.isEmpty()) {
                            android.util.Log.e("CyanCodex", "read bytes empty for $uri")
                            return@withContext null
                        }
                        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o)
                        var sample = 1
                        while (o.outWidth / sample > 512 || o.outHeight / sample > 512) sample *= 2
                        val o2 = BitmapFactory.Options().apply { inSampleSize = sample }
                        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, o2)
                    } catch (e: Exception) {
                        android.util.Log.e("CyanCodex", "avatar decode failed: ${e.message}", e)
                        null
                    }
                }
                if (bmp == null) { msg = "无法读取图片"; return@launch }
                val baos = java.io.ByteArrayOutputStream()
                bmp.compress(Bitmap.CompressFormat.PNG, 100, baos)
                val b64 = android.util.Base64.encodeToString(baos.toByteArray(), android.util.Base64.NO_WRAP)
                val r = safeApi { ApiClient.get().uploadAvatar(mapOf("data" to b64)) }
                if (r.ok && r.data != null) {
                    avatar = r.data.avatar
                    AppState.patchUser(avatar = avatar)
                    msg = "头像已上传"
                } else {
                    msg = r.error ?: "上传失败"
                }
            } finally { uploading = false }
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) pickAvatar(uri)
    }

    fun save() {
        val n = nickname.trim()
        if (n.isBlank()) { msg = "昵称不能为空"; return }
        scope.launch {
            saving = true
            try {
                val r = safeApi {
                    ApiClient.get().updateProfile(mapOf("nickname" to n, "gender" to gender, "avatar" to avatar))
                }
                if (r.ok) {
                    AppState.patchUser(nickname = n, gender = gender, avatar = avatar)
                    msg = "保存成功"
                    nav.popBackStack()
                } else {
                    msg = r.error ?: "保存失败"
                }
            } finally { saving = false }
        }
    }

    Scaffold(
        topBar = {
            AppTopBar(title = "个人中心", icon = Icons.Default.Person, onBack = { nav.popBackStack() }, actions = {
                TopBarTextAction(onClick = { save() }, enabled = !saving) {
                    Icon(Icons.Default.Save, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)); Text("保存")
                }
            })
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
            // 当前头像
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                AvatarIcon(avatar, 76.dp)
            }
            Spacer(Modifier.height(10.dp))
            GlassActionButton(
                onClick = { launcher.launch("image/*") },
                enabled = !uploading,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (uploading) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(18.dp))
                }
                Spacer(Modifier.width(6.dp))
                Text(if (uploading) "上传中…" else "从相册选择图片作为头像")
            }
            Spacer(Modifier.height(8.dp))
            Text("选择头像", style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.primary)
            LazyVerticalGrid(columns = GridCells.Fixed(6), modifier = Modifier.fillMaxWidth().heightIn(max = 260.dp)) {
                items(avatarMap.keys.toList()) { code ->
                    Box(
                        Modifier.padding(4.dp)
                            .background(
                                if (avatar == code) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceVariant,
                                RoundedCornerShape(10.dp))
                            .clickable { avatar = code }
                            .padding(6.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        AvatarIcon(code, 40.dp)
                    }
                }
            }

            Spacer(Modifier.height(16.dp))
            OutlinedTextField(value = nickname, onValueChange = { nickname = it },
                label = { Text("昵称(必填)") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            Spacer(Modifier.height(12.dp))
            Text("性别", style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("male" to "男", "female" to "女", "secret" to "保密").forEach { (k, v) ->
                    GlassFilterChip(selected = gender == k, onClick = { gender = k }, label = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            if (k == "male") { Icon(Icons.Default.Male, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                            if (k == "female") { Icon(Icons.Default.Female, null, Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
                            Text(v)
                        }
                    })
                }
            }

            if (msg.isNotBlank()) {
                Text(msg, color = if (msg == "保存成功") MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
            }

            Spacer(Modifier.height(16.dp))
            GlassActionButton(filled = true, onClick = { save() }, enabled = !saving, modifier = Modifier.fillMaxWidth()) {
                Text(if (saving) "保存中…" else "保存修改", fontWeight = FontWeight.Bold)
            }
        }
    }
}
