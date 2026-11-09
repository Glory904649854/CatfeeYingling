package com.catfee.yingling.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.catfee.yingling.core.Prefs
import com.catfee.yingling.data.AppEntry
import com.catfee.yingling.ui.components.TagChip
import com.catfee.yingling.ui.theme.BorderDark
import com.catfee.yingling.ui.theme.Cyan
import com.catfee.yingling.ui.theme.SurfaceDark
import com.catfee.yingling.ui.theme.SurfaceHigh
import com.catfee.yingling.ui.theme.TextPrimary
import com.catfee.yingling.ui.theme.TextSecondary
import com.catfee.yingling.ui.theme.TextTertiary

@Composable
fun AppsScreen(vm: MainViewModel) {
    var search by rememberSaveable { mutableStateOf("") }
    val filtered = remember(vm.apps, search) {
        if (search.isBlank()) {
            vm.apps
        } else {
            vm.apps.filter {
                it.label.contains(search, ignoreCase = true) ||
                    it.packageName.contains(search, ignoreCase = true)
            }
        }
    }
    val interactive = vm.mode == Prefs.MODE_APPS

    Column(
        Modifier
            .fillMaxSize()
            .statusBarsPadding(),
    ) {
        Column(Modifier.padding(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 12.dp)) {
            Text(
                text = "应用",
                style = MaterialTheme.typography.headlineSmall,
                color = TextPrimary,
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = "绑定要监听的音频来源",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }

        SearchField(
            value = search,
            onValueChange = { search = it },
            modifier = Modifier.padding(horizontal = 18.dp),
        )

        Spacer(Modifier.height(12.dp))

        Row(
            Modifier.padding(horizontal = 18.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            TagChip(
                text = "指定应用",
                selected = vm.mode == Prefs.MODE_APPS,
                onClick = { vm.selectMode(Prefs.MODE_APPS) },
            )
            TagChip(
                text = "全部媒体",
                selected = vm.mode == Prefs.MODE_ALL,
                onClick = { vm.selectMode(Prefs.MODE_ALL) },
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(Modifier.padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = if (interactive) {
                    "已选 ${vm.selected.size} / ${vm.apps.size}"
                } else {
                    "全部媒体模式：按类型捕获所有可采集应用"
                },
                color = TextTertiary,
                fontSize = 10.5f.sp,
            )
            Spacer(Modifier.weight(1f))
            if (interactive && vm.selected.isNotEmpty()) {
                Text(
                    text = "清空",
                    color = TextTertiary,
                    fontSize = 10.5f.sp,
                    modifier = Modifier.clickable { vm.clearApps() },
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        if (vm.loading) {
            Box(Modifier.fillMaxWidth().padding(top = 40.dp), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Cyan, strokeWidth = 2.dp, modifier = Modifier.size(26.dp))
            }
        } else {
            LazyColumn(
                contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(filtered, key = { it.packageName }) { app ->
                    AppRow(
                        app = app,
                        selected = app.packageName in vm.selected,
                        enabled = interactive,
                        onToggle = { vm.toggleApp(app.packageName) },
                    )
                }
                if (filtered.isEmpty()) {
                    item {
                        Text(
                            text = "没有匹配的应用",
                            color = TextTertiary,
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SearchField(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(SurfaceHigh)
            .border(1.dp, BorderDark, RoundedCornerShape(14.dp))
            .padding(horizontal = 14.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.size(15.dp)) {
            val strokeWidth = size.width * 0.1f
            drawCircle(
                color = TextTertiary,
                radius = size.width * 0.32f,
                center = Offset(size.width * 0.42f, size.height * 0.42f),
                style = Stroke(width = strokeWidth),
            )
            drawLine(
                color = TextTertiary,
                start = Offset(size.width * 0.66f, size.height * 0.66f),
                end = Offset(size.width * 0.9f, size.height * 0.9f),
                strokeWidth = strokeWidth * 1.1f,
                cap = StrokeCap.Round,
            )
        }
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f)) {
            if (value.isEmpty()) {
                Text("搜索应用", color = TextTertiary, fontSize = 13.sp)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = TextStyle(color = TextPrimary, fontSize = 13.sp),
                cursorBrush = SolidColor(Cyan),
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (value.isNotEmpty()) {
            Text(
                text = "清除",
                color = TextTertiary,
                fontSize = 10.5f.sp,
                modifier = Modifier.clickable { onValueChange("") },
            )
        }
    }
}

@Composable
private fun AppRow(
    app: AppEntry,
    selected: Boolean,
    enabled: Boolean,
    onToggle: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else 0.45f)
            .clip(RoundedCornerShape(16.dp))
            .background(SurfaceDark)
            .border(
                1.dp,
                if (selected) Cyan.copy(alpha = 0.35f) else BorderDark,
                RoundedCornerShape(16.dp),
            )
            .clickable(enabled = enabled, onClick = onToggle)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(SurfaceHigh),
            contentAlignment = Alignment.Center,
        ) {
            val icon = app.icon
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
                    text = app.label.take(1),
                    color = TextSecondary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        }

        Spacer(Modifier.width(12.dp))

        Column(Modifier.weight(1f)) {
            Text(
                text = app.label,
                color = TextPrimary,
                fontSize = 14.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(2.dp))
            Text(
                text = app.packageName,
                color = TextTertiary,
                fontSize = 10.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }

        Spacer(Modifier.width(10.dp))

        Box(
            Modifier
                .size(22.dp)
                .clip(RoundedCornerShape(7.dp))
                .background(if (selected) Cyan else Color.Transparent)
                .border(
                    1.dp,
                    if (selected) Cyan else BorderDark,
                    RoundedCornerShape(7.dp),
                ),
            contentAlignment = Alignment.Center,
        ) {
            if (selected) {
                Text(
                    text = "✓",
                    color = Color(0xFF04122E),
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Bold,
                )
            }
        }
    }
}
