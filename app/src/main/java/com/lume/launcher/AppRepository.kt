package com.lume.launcher

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.graphics.drawable.toBitmap
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AppRepository(private val ctx: Context) {

    @Suppress("DEPRECATION")
    suspend fun load(): List<AppEntry> = withContext(Dispatchers.Default) {
        val pm = ctx.packageManager
        val seen = HashSet<String>()
        val out = ArrayList<AppEntry>()

        fun collect(category: String) {
            val query = Intent(Intent.ACTION_MAIN).addCategory(category)
            for (ri in pm.queryIntentActivities(query, 0)) {
                val ai = ri.activityInfo ?: continue
                val pkg = ai.packageName
                if (pkg == ctx.packageName || !seen.add(pkg)) continue
                try {
                    val banner = (ai.loadBanner(pm) ?: ai.applicationInfo.loadBanner(pm))
                        ?.toBitmap(320, 180)?.asImageBitmap()
                    val icon = ri.loadIcon(pm).toBitmap(128, 128).asImageBitmap()
                    val flags = ai.applicationInfo.flags
                    val isSystem = (flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                        (flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0
                    out.add(AppEntry(pkg, ri.loadLabel(pm).toString(), banner, icon, !isSystem))
                } catch (e: Exception) {
                    // Skip apps whose resources fail to load rather than breaking the launcher.
                }
            }
        }

        collect(Intent.CATEGORY_LEANBACK_LAUNCHER)
        collect(Intent.CATEGORY_LAUNCHER)
        out.sortedBy { it.label.lowercase() }
    }
}
