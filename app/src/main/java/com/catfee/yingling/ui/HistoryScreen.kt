package com.catfee.yingling.ui

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.catfee.yingling.core.Languages
import com.catfee.yingling.data.HistoryEntry
import com.catfee.yingling.data.HistorySession
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.Danger
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun HistoryScreen(vm: MainViewModel) {
    val sessions by vm.historySessions.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    var showClear by remember { mutableStateOf(false) }

    val visibleSessions = remember(sessions) { sessions.filter { it.entries.isNotEmpty() } }
    val filtered = remember(visibleSessions, query) {
        val keyword = query.trim()
        if (keyword.isEmpty()) {
            visibleSessions
        } else {
            visibleSessions.mapNotNull { session ->
                val matchedEntries = session.entries.filter { entry ->
                    entry.source.contains(keyword, ignoreCase = true) ||
                        entry.translated.contains(keyword, ignoreCase = true) ||
                        entry.appName.contains(keyword, ignoreCase = true)
                }
                val appMatched = session.appName.contains(keyword, ignoreCase = true)
                if (matchedEntries.isEmpty() && !appMatched) {
                    null
                } else {
                    session.copy(
                        entries = if (matchedEntries.isEmpty()) session.entries else matchedEntries,
                    )
                }
            }
        }
    }

    val totalEntries = visibleSessions.sumOf { it.entries.size }

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(horizontal = 18.dp, vertical = 14.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    text = "历史记录",
                    style = MaterialTheme.typography.headlineSmall,
                    color = TextPrimary,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = "学习复盘 · 原文与译文",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                )
            }
            if (visibleSessions.isNotEmpty()) {
                Text(
                    text = "清空",
                    color = Danger,
                    fontSize = 11.5.sp,
                    modifier = Modifier.clickable { showClear = true },
                )
            }
        }

        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = {
                Text("搜索原文 / 译文 / App", color = TextTertiary, fontSize = 12.sp)
            },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodySmall,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Cyan,
                unfocusedBorderColor = BorderDark,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                cursorColor = Cyan,
                focusedContainerColor = Color.Transparent,
                unfocusedContainerColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(8.dp))
        Text(
            text = "${visibleSessions.size} 次监听 · $totalEntries 条字幕",
            color = TextTertiary,
            fontSize = 10.5.sp,
        )
        Spacer(Modifier.height(10.dp))

        if (filtered.isEmpty()) {
            Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = if (visibleSessions.isEmpty()) "还没有翻译记录" else "没有匹配的记录",
                        color = TextSecondary,
                        fontSize = 14.sp,
                    )
                    Spacer(Modifier.height(5.dp))
                    Text(
                        text = if (visibleSessions.isEmpty()) {
                            "启动监听后，识别到的原文和译文会自动归档在这里"
                        } else {
                            "换个关键词试试"
                        },
                        color = TextTertiary,
                        fontSize = 10.5.sp,
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(filtered, key = { it.id }) { session ->
                    HistorySessionCard(session, vm)
                }
                item { Spacer(Modifier.height(8.dp)) }
            }
        }
    }

    if (showClear) {
        AlertDialog(
            onDismissRequest = { showClear = false },
            title = { Text("清空历史记录") },
            text = { Text("会删除所有监听会话和字幕记录，不可恢复。") },
            confirmButton = {
                TextButton(
                    onClick = {
                        vm.clearHistory()
                        showClear = false
                    },
                ) {
                    Text("清空", color = Danger)
                }
            },
            dismissButton = {
                TextButton(onClick = { showClear = false }) {
                    Text("取消")
                }
            },
        )
    }
}

@Composable
private fun HistorySessionCard(session: HistorySession, vm: MainViewModel) {
    var expanded by rememberSaveable(session.id) { mutableStateOf(false) }
    val entries = session.entries
    val entryApps = entries.map { it.appName }.filter { it.isNotBlank() }.distinct()
    val appLabel = when {
        entryApps.size == 1 -> entryApps.first()
        entryApps.size > 1 -> "多个应用"
        session.appName.isNotBlank() -> session.appName
        else -> "未知应用"
    }
    val appPackage = entries.firstOrNull { it.appPackage.isNotBlank() }?.appPackage
        ?: session.appPackage
    val icon = vm.apps.firstOrNull { it.packageName == appPackage }?.icon
    val endedAt = session.endedAt
        ?: entries.lastOrNull()?.timestamp
        ?: session.startedAt

    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(Brush.verticalGradient(listOf(Color(0xFF0C1D3E), Color(0xFF081733))))
            .border(1.dp, BorderDark, RoundedCornerShape(20.dp))
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) {
                if (icon != null) {
                    Image(
                        bitmap = icon,
                        contentDescription = null,
                        modifier = Modifier
                            .size(32.dp)
                            .clip(RoundedCornerShape(8.dp)),
                    )
                } else {
                    Text(
                        text = appLabel.take(1),
                        color = TextSecondary,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Medium,
                    )
                }
            }

            Spacer(Modifier.width(11.dp))

            Column(Modifier.weight(1f)) {
                Text(
                    text = appLabel,
                    color = TextPrimary,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.Medium,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = timeRange(session.startedAt, endedAt),
                    color = TextTertiary,
                    fontSize = 10.sp,
                )
                Spacer(Modifier.height(1.dp))
                Text(
                    text = "${Languages.label(session.sourceLang)} → " +
                        Languages.targetLabel(session.targetLang) +
                        " · ${entries.size} 条",
                    color = TextTertiary,
                    fontSize = 10.sp,
                )
            }

            Text(
                text = if (expanded) "收起" else "展开",
                color = Cyan,
                fontSize = 10.5.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(RoundedCornerShape(50))
                    .clickable { expanded = !expanded }
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )
        }

        if (expanded) {
            Spacer(Modifier.height(10.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(1.dp)
                    .background(BorderDark.copy(alpha = 0.55f)),
            )
            Spacer(Modifier.height(4.dp))
            SelectionContainer {
                Column {
                    entries.forEachIndexed { index, entry ->
                        HistoryEntryRow(
                            entry = entry,
                            showApp = entryApps.size > 1 && entry.appName.isNotBlank(),
                        )
                        if (index != entries.lastIndex) {
                            Box(
                                Modifier
                                    .fillMaxWidth()
                                    .height(1.dp)
                                    .background(BorderDark.copy(alpha = 0.35f)),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun HistoryEntryRow(entry: HistoryEntry, showApp: Boolean) {
    Column(Modifier.padding(vertical = 7.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = timeOnly(entry.timestamp),
                color = TextTertiary,
                fontSize = 9.5.sp,
            )
            if (showApp) {
                Text(
                    text = " · ${entry.appName}",
                    color = Cyan,
                    fontSize = 9.5.sp,
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        Text(
            text = entry.source,
            color = TextSecondary,
            fontSize = 11.5.sp,
            lineHeight = 16.sp,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            text = entry.translated,
            color = TextPrimary,
            fontSize = 14.5.sp,
            lineHeight = 20.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

private fun timeRange(startedAt: Long, endedAt: Long): String {
    val start = Date(startedAt)
    val end = Date(endedAt)
    val day = SimpleDateFormat("MM-dd", Locale.getDefault())
    val second = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
    val dayKey = SimpleDateFormat("yyyyMMdd", Locale.getDefault())
    return if (dayKey.format(start) == dayKey.format(end)) {
        "${day.format(start)} ${second.format(start)} – ${second.format(end)}"
    } else {
        "${day.format(start)} ${second.format(start)} – ${day.format(end)} ${second.format(end)}"
    }
}

private fun timeOnly(timestamp: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(timestamp))
