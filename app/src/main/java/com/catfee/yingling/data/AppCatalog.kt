package com.catfee.yingling.data

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap

/** 读取桌面可见应用，供“绑定监听应用”列表使用。 */
object AppCatalog {

    fun load(context: Context): List<AppEntry> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)

        val resolved = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(0L))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, 0)
        }

        val seen = HashSet<String>(resolved.size)
        val result = ArrayList<AppEntry>(resolved.size)

        for (info in resolved) {
            val appInfo = info.activityInfo?.applicationInfo ?: continue
            val pkg = appInfo.packageName
            if (pkg == context.packageName) continue
            if (!seen.add(pkg)) continue

            val label = runCatching { pm.getApplicationLabel(appInfo).toString() }
                .getOrDefault(pkg)
            val icon = runCatching {
                appInfo.loadIcon(pm)
                    .toBitmap(width = 96, height = 96)
                    .asImageBitmap()
            }.getOrNull()

            result += AppEntry(pkg, label, appInfo.uid, icon)
        }

        return result.sortedBy { it.label.lowercase() }
    }
}
