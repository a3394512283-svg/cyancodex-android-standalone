package com.canglan.cyancodex.ui.screens

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.OndemandVideo
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.School
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.canglan.cyancodex.data.ApiClient
import com.canglan.cyancodex.ui.components.AppTopBar
import com.canglan.cyancodex.ui.components.GlassActionButton
import com.canglan.cyancodex.ui.components.LoadingBox
import com.canglan.cyancodex.ui.components.safeApi

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TutorialScreen(nav: NavHostController, id: Long) {
    val ctx = LocalContext.current
    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("post") }
    var videoUrl by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(id) {
        val r = safeApi { ApiClient.get().tutorialDetail(id) }
        if (r.ok) {
            title = r.data?.title ?: ""
            content = r.data?.content ?: ""
            type = r.data?.type ?: "post"
            videoUrl = r.data?.videoUrl ?: ""
        }
        loading = false
    }

    Scaffold(
        topBar = {
            AppTopBar(title = title.ifBlank { "教程" }, icon = Icons.Default.School, onBack = { nav.popBackStack() })
        },
    ) { pad ->
        if (loading) {
            LoadingBox(Modifier.padding(pad))
        } else {
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp)) {
                if (type == "video") {
                    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                        modifier = Modifier.fillMaxWidth()) {
                        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.OndemandVideo, null, tint = MaterialTheme.colorScheme.error,
                                    modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("视频教程", style = MaterialTheme.typography.titleSmall,
                                    color = MaterialTheme.colorScheme.error)
                            }
                            Text(videoUrl.ifBlank { "（未提供链接）" }, style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            GlassActionButton(filled = true, onClick = {
                                if (videoUrl.isNotBlank()) {
                                    runCatching {
                                        ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(videoUrl)))
                                    }
                                }
                            }, enabled = videoUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                                Icon(Icons.Default.PlayArrow, null, Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("观看视频")
                            }
                        }
                    }
                } else {
                    Text(content, style = MaterialTheme.typography.bodyMedium, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
}
