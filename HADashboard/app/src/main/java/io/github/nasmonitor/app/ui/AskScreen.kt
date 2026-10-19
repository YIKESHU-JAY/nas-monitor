package io.github.nasmonitor.app.ui

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.github.nasmonitor.app.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AskScreen(nav: NavHostController) {
    var question by rememberSaveable { mutableStateOf("") }
    var asking by remember { mutableStateOf(false) }
    var answer by remember { mutableStateOf<String?>(null) }
    var sources by remember { mutableStateOf<List<AskSource>>(emptyList()) }
    var error by remember { mutableStateOf<String?>(null) }
    var cancelled by remember { mutableStateOf(false) }
    var askJob by remember { mutableStateOf<Job?>(null) }
    val scope = rememberCoroutineScope()

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            AppPageHeader("知识问答", onBack = { nav.popBackStack() })
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("基于 AnythingLLM 运维知识库（nas-docker）回答", color = Muted, fontSize = 13.sp)
                Text("问题会检索知识库并生成回答，同时列出引用来源", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            item {
                FrostCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Send, null, tint = Blue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("提问", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = question,
                            label = { Text("问题") },
                            enabled = !asking,
                            onValueChange = { question = it },
                            placeholder = { Text("例如：MoviePilot 的部署配置是什么？", color = Muted) },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            maxLines = 6,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Track)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val q = question.trim()
                                if (q.isBlank()) return@Button
                                asking = true
                                error = null
                                cancelled = false
                                askJob = scope.launch {
                                    try {
                                        val resp = ApiClient.ask(q)
                                        if (resp.ok && !resp.answer.isNullOrBlank()) {
                                            answer = resp.answer
                                            sources = resp.sources
                                        } else {
                                            cancelled = false
                                            error = resp.error ?: "知识库无可用回答"
                                        }
                                    } catch (e: CancellationException) {
                                        if (cancelled) error = "已取消本次问答"
                                        throw e
                                    } catch (t: Throwable) {
                                        cancelled = false
                                        error = t.message ?: t.javaClass.simpleName
                                    } finally {
                                        asking = false
                                        askJob = null
                                    }
                                }
                            },
                            enabled = !asking && question.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentFill)
                        ) { Text(if (asking) "检索回答中…" else "提问", color = OnAccent) }
                        if (asking) {
                            TextButton(onClick = { cancelled = true; askJob?.cancel() }) { Text("取消", color = Blue) }
                        }
                    }
                }
            }

            if (asking) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {

                        Text("正在检索知识库并生成回答…", color = Muted, fontSize = 12.sp)
                    }
                }
            }

            error?.let { msg ->
                item {
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (cancelled) BlueSoft else RedSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (cancelled) Icons.Default.Info else Icons.Default.Error, null, tint = if (cancelled) Blue else Red, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(msg, color = if (cancelled) Blue else Red, fontSize = 13.sp)
                    }
                }
            }

            answer?.let { text ->
                item {
                    FrostCard {
                        Column {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(Icons.Default.CheckCircle, null, tint = Green, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(8.dp))
                                Text("回答", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                            }
                            Spacer(Modifier.height(10.dp))
                            SelectionContainer { Text(text, color = Ink, fontSize = 14.sp, lineHeight = 20.sp) }
                        }
                    }
                }
                if (sources.isNotEmpty()) {
                    item {
                        Text("引用来源（${sources.size}）", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = Muted)
                    }
                    items(sources) { src ->
                        LiquidAwareCard(shape = RoundedCornerShape(14.dp), colors = CardDefaults.cardColors(CardWhite), elevation = MonitorCardElevation(1.dp)) {
                            Column(Modifier.padding(12.dp)) {
                                Text(src.title.ifBlank { src.url.ifBlank { "未知来源" } }, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium, maxLines = 2)
                                Spacer(Modifier.height(4.dp))
                                Text("相关度 ${(src.score * 100).toInt()}% · ${src.url}", color = Muted, fontSize = 11.sp, maxLines = 1)
                            }
                        }
                    }
                }
            }
        }
    }
}
