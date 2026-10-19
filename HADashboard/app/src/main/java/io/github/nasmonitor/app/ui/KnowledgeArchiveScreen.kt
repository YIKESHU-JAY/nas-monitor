package io.github.nasmonitor.app.ui

import android.net.Uri
import android.provider.OpenableColumns
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.CloudUpload
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Error
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import io.github.nasmonitor.app.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnowledgeArchiveScreen(nav: NavHostController) {
    var url by remember { mutableStateOf("") }
    var fileUri by remember { mutableStateOf<Uri?>(null) }
    var fileName by remember { mutableStateOf("") }
    var activeOperation by remember { mutableStateOf<String?>(null) }
    val submitting = activeOperation != null
    var message by remember { mutableStateOf<String?>(null) }
    var messageType by remember { mutableStateOf("ok") } // ok | err
    val context = LocalContext.current
    val restoredTask = remember { ArchiveTaskStore.load(context) }
    var taskId by rememberSaveable { mutableStateOf(restoredTask?.taskId) }
    var submittedAt by rememberSaveable { mutableLongStateOf(restoredTask?.submittedAt ?: 0L) }
    var pollGeneration by rememberSaveable { mutableIntStateOf(0) }
    var confirmClearTask by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val filePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        if (uri != null) {
            fileUri = uri
            fileName = runCatching {
                context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                    if (cursor.moveToFirst()) cursor.getString(0) else null
                }
            }.getOrNull() ?: "文件"
        }
    }

    fun show(msg: String, isErr: Boolean = false) {
        message = msg
        messageType = if (isErr) "err" else "ok"
    }

    if (confirmClearTask) {
        CompactConfirmationDialog(
            onDismissRequest = { confirmClearTask = false },
            title = { Text("清除任务记录？") },
            text = { Text("只会清除本机保存的任务编号，不会取消后端任务。之后重新提交可能造成重复归档。") },
            confirmButton = {
                TextButton(onClick = {
                    confirmClearTask = false
                    taskId = null
                    submittedAt = 0L
                    activeOperation = null
                    ArchiveTaskStore.clear(context)
                    show("已清除本地任务记录；后端任务未取消，重新提交可能重复。", true)
                }) { Text("清除记录", color = Red) }
            },
            dismissButton = { TextButton(onClick = { confirmClearTask = false }) { Text("取消") } }
        )
    }

    // Keep polling tied to this destination lifecycle; it is cancelled automatically on back.
    LaunchedEffect(taskId, pollGeneration) {
        val id = taskId ?: return@LaunchedEffect
        activeOperation = "query"
        show("正在查询归档任务状态…")
        messageType = "pending"
        // 链接归档在 AnythingLLM 侧需同步分片与向量化，长文档可能要 1-3 分钟，
        // 这里轮询约 5 分钟，避免过早显示“未确认”。
        var consecutiveErrors = 0
        repeat(60) { attempt ->
            delay(5000)
            try {
                val task = ApiClient.archiveStatus(id)
                consecutiveErrors = 0
                when (task.status.orEmpty()) {
                    "queued", "processing" -> {
                        show("归档处理中…（已等待 ${(attempt + 1) * 5} 秒）")
                        messageType = "pending"
                    }
                    "completed" -> {
                        show("归档完成：${task.source.orEmpty().ifBlank { task.filename ?: "文档" }}")
                        activeOperation = null
                        taskId = null
                        submittedAt = 0L
                        ArchiveTaskStore.clear(context)
                        return@LaunchedEffect
                    }
                    "failed" -> {
                        show("归档失败：${task.error ?: "未知错误"}", true)
                        activeOperation = null
                        taskId = null
                        submittedAt = 0L
                        ArchiveTaskStore.clear(context)
                        return@LaunchedEffect
                    }
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                consecutiveErrors++
                if (consecutiveErrors >= 3) show("归档状态查询失败，请稍后查看 AnythingLLM", true)
            }
        }
        show("暂未确认归档结果，任务仍可能在后台处理。请继续查询，避免重复提交。")
        messageType = "pending"
        activeOperation = null
    }

    Scaffold(
        containerColor = PageBackground,
        topBar = {
            AppPageHeader("知识归档", onBack = { nav.popBackStack() })
        }
    ) { padding ->
        LazyColumn(
            Modifier.fillMaxSize().padding(padding).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text("把链接或文件交给 AnythingLLM 收藏归档", color = Muted, fontSize = 13.sp)
                Text("归档到工作区：nas-docker", color = Blue, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
            }

            // 链接归档卡片
            item {
                FrostCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.Link, null, tint = Blue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("归档链接", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                        Spacer(Modifier.height(12.dp))
                        OutlinedTextField(
                            value = url,
                            label = { Text("网页链接") },
                            enabled = !submitting && taskId == null,
                            onValueChange = { url = it },
                            placeholder = { Text("粘贴 http(s) 链接", color = Muted) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                            colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = Blue, unfocusedBorderColor = Track)
                        )
                        Spacer(Modifier.height(12.dp))
                        Button(
                            onClick = {
                                val parsed = Uri.parse(url.trim())
                                if (parsed.scheme !in listOf("http", "https") || parsed.host.isNullOrBlank()) {
                                    show("请输入有效的 http(s) 链接", true); return@Button
                                }
                                activeOperation = "link"
                                show("正在归档链接…")
                                scope.launch {
                                    try {
                                        val task = ApiClient.archiveUrl(url.trim())
                                        val id = task.taskId.orEmpty().ifBlank { task.id.orEmpty() }
                                        if (id.isBlank()) {
                                            activeOperation = null
                                            show("服务返回无任务编号", true)
                                            return@launch
                                        }
                                        taskId = id
                                        submittedAt = System.currentTimeMillis()
                                        ArchiveTaskStore.save(context, id, submittedAt)
                                        show("已提交，正在抓取网页内容…")
                                        messageType = "pending"
                                    } catch (t: Throwable) {
                                        if (t is CancellationException) throw t
                                        activeOperation = null
                                        show("提交失败：${t.message ?: t.javaClass.simpleName}", true)
                                    }
                                }
                            },
                            enabled = !submitting && taskId == null && url.isNotBlank(),
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = AccentFill)
                        ) { Text(if (activeOperation == "link") "归档中…" else "归档链接", color = OnAccent) }
                    }
                }
            }

            // 文件归档卡片
            item {
                FrostCard {
                    Column {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.CloudUpload, null, tint = Blue, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text("归档文件", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = Ink)
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("支持 PDF、Word、Markdown、源码文本等；上限 100MB", color = Muted, fontSize = 12.sp)
                        Spacer(Modifier.height(12.dp))
                        OutlinedButton(
                            onClick = { filePicker.launch("*/*") },
                            enabled = !submitting,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue)
                        ) { Text(if (fileUri != null) "更换文件" else "选择文件") }
                        if (fileUri != null) {
                            Text("已选择：$fileName", color = Muted, fontSize = 12.sp)
                            Spacer(Modifier.height(12.dp))
                            Button(
                                onClick = {
                                    val uri = fileUri ?: return@Button
                                    activeOperation = "file"
                                    show("正在上传归档…")
                                    scope.launch {
                                        try {
                                            val bytes = withContext(Dispatchers.IO) {
                                                context.contentResolver.openInputStream(uri)?.use { input ->
                                                    val output = java.io.ByteArrayOutputStream()
                                                    val buffer = ByteArray(8192)
                                                    while (true) {
                                                        val count = input.read(buffer)
                                                        if (count < 0) break
                                                        require(output.size().toLong() + count <= 100L * 1024 * 1024) { "文件超过 100MB 上限" }
                                                        output.write(buffer, 0, count)
                                                    }
                                                    output.toByteArray()
                                                }
                                            }
                                            if (bytes == null || bytes.isEmpty()) {
                                                activeOperation = null
                                                show("文件读取失败", true)
                                                return@launch
                                            }
                                            ApiClient.archiveFile(fileName.ifBlank { "file" }, bytes)
                                            show("文件已入库：${fileName.ifBlank { "file" }}")
                                            messageType = "ok"
                                            fileUri = null
                                            fileName = ""
                                        } catch (t: Throwable) {
                                            if (t is CancellationException) throw t
                                            activeOperation = null
                                            show("上传失败：${t.message ?: t.javaClass.simpleName}", true)
                                        } finally {
                                            activeOperation = null
                                        }
                                    }
                                },
                                enabled = !submitting && taskId == null,
                                modifier = Modifier.fillMaxWidth(),
                                colors = ButtonDefaults.buttonColors(containerColor = AccentFill)
                            ) { Text(if (activeOperation == "file") "上传中…" else "归档文件", color = OnAccent) }
                        }
                    }
                }
            }

            // 状态提示
            message?.let { msg ->
                item {
                    val isErr = messageType == "err"
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (isErr) RedSoft else if (submitting || messageType == "pending") BlueSoft else GreenSoft).padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(if (isErr) Icons.Default.Error else if (submitting || messageType == "pending") Icons.Default.Info else Icons.Default.CheckCircle, null,
                            tint = if (isErr) Red else if (submitting || messageType == "pending") Blue else Green, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(msg, color = if (isErr) Red else if (submitting || messageType == "pending") Blue else Green, fontSize = 13.sp)
                    }
                }
            }

            if (submitting) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {

                        Text("正在处理…", color = Muted, fontSize = 12.sp)
                    }
                }
            }
            if (taskId != null && !submitting) {
                item {
                    Text("任务编号：$taskId${if (submittedAt > 0L) " · 已提交" else ""}", color = Muted, fontSize = 11.sp)
                    OutlinedButton(
                        onClick = { pollGeneration++ },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.outlinedButtonColors(contentColor = Blue)
                    ) { Text("继续查询归档状态") }
                    TextButton(onClick = { confirmClearTask = true }) { Text("清除本地任务记录") }
                }
            }
        }
    }
}
