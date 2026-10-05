package com.canglan.cyancodex.ui.screens

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddPhotoAlternate
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Feedback
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.Routes
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.GlassFilterChip
import com.canglan.cyancodex.ui.components.safeApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 意见申诉: 向管理员反馈问题 / 申诉
 * 分类: 意见(feedback) / 申诉(appeal); 提交后进入管理端待处理列表
 * 支持附图 (意见 #41)
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeedbackScreen(nav: NavHostController) {
    val scope = rememberCoroutineScope()
    val ctx = LocalContext.current
    var category by remember { mutableStateOf("feedback") }
    var content by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf("") }
    var done by remember { mutableStateOf(false) }
    // 附图 (意见 #41)
    var imageBmp by remember { mutableStateOf<Bitmap?>(null) }
    var imageB64 by remember { mutableStateOf("") }

    val imgPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri: Uri? ->
        if (uri != null) {
            scope.launch {
                val bmp = withContext(Dispatchers.IO) {
                    runCatching {
                        val src = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) } ?: return@withContext null
                        // 压缩到 900px 内 + JPEG 80
                        val max = 900f
                        val scale = minOf(1f, max / maxOf(src.width.toFloat(), src.height.toFloat()))
                        val out = Bitmap.createScaledBitmap(src,
                            (src.width * scale).toInt().coerceAtLeast(1), (src.height * scale).toInt().coerceAtLeast(1), true)
                        if (out != src) src.recycle()
                        out
                    }.getOrNull()
                }
                if (bmp != null) {
                    imageBmp = bmp
                    val bos = java.io.ByteArrayOutputStream()
                    bmp.compress(Bitmap.CompressFormat.JPEG, 80, bos)
                    imageB64 = android.util.Base64.encodeToString(bos.toByteArray(), android.util.Base64.DEFAULT)
                } else {
                    error = "图片读取失败"
                }
            }
        }
    }

    Scaffold(
        topBar = { AppTopBar(title = "意见申诉", icon = Icons.Default.Feedback, onBack = { nav.popBackStack() }) },
    ) { pad ->
        Column(
            Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("遇到问题或有建议？告诉管理员，我们会尽快处理。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)

            // 分类选择
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GlassFilterChip(selected = category == "feedback", onClick = { category = "feedback"; error = "" },
                    label = { Text("意见") })
                GlassFilterChip(selected = category == "appeal", onClick = { category = "appeal"; error = "" },
                    label = { Text("申诉") })
            }

            // 内容输入
            OutlinedTextField(
                value = content,
                onValueChange = { content = it.take(1000); error = ""; done = false },
                placeholder = { Text("请描述你的意见或申诉内容（5-1000 字）…") },
                minLines = 6,
                maxLines = 12,
                modifier = Modifier.fillMaxWidth(),
            )
            Text("${content.length}/1000", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.align(Alignment.End))

            // 附图选择 (意见 #41)
            val bmp = imageBmp
            if (bmp != null) {
                Box {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "附件",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxWidth().height(160.dp).clip(RoundedCornerShape(10.dp)),
                    )
                    IconButton(
                        onClick = { imageBmp = null; imageB64 = "" },
                        modifier = Modifier.align(Alignment.TopEnd),
                    ) {
                        Icon(Icons.Default.Close, "移除图片", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            } else {
                GlassActionButton(
                    onClick = { imgPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(Icons.Default.AddPhotoAlternate, null, Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("添加图片（可选，截图/报错界面）")
                }
            }

            if (error.isNotBlank()) {
                Text(error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }

            GlassActionButton(filled = true,
                onClick = {
                    val c = content.trim()
                    if (c.length < 5) { error = "内容至少 5 个字"; return@GlassActionButton }
                    scope.launch {
                        busy = true
                        error = ""
                        val body = mutableMapOf<String, String>("content" to c, "category" to category)
                        if (imageB64.isNotBlank()) body["image"] = imageB64
                        val r = safeApi { ApiClient.get().submitFeedback(body) }
                        busy = false
                        if (r.ok) {
                            content = ""
                            imageBmp = null
                            imageB64 = ""
                            done = true
                            android.widget.Toast.makeText(ctx, "已提交，感谢反馈！", android.widget.Toast.LENGTH_SHORT).show()
                        } else {
                            error = r.error ?: "提交失败，请稍后再试"
                        }
                    }
                },
                enabled = !busy,
                modifier = Modifier.fillMaxWidth().height(46.dp),
            ) { Text(if (busy) "提交中…" else "提交") }

            if (done) {
                Text("✓ 已提交，管理员会尽快处理（可在 QQ 群或站内信留意回复）",
                    color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }

            Text("提示：请勿提交与游戏指令无关的内容；同一问题请勿重复提交（60 秒内限 1 条）。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
