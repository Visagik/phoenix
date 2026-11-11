package com.phoenix.app

import android.content.Context

/** Что делает пользователь сейчас: какое приложение (или сайт в браузере) открыто и к какой цели оно относится. */
object AppContext {
    class Now(
        val pkg: String?,
        val site: String?,
        /** Цель открытого приложения или сайта (если она есть среди целей) */
        val target: Target?,
        /** Цели других недавно открытых приложений */
        val others: List<Target>,
    ) {
        val label: String
            get() = when {
                site != null -> "сайт $site" + (target?.let { " («${it.label}»)" } ?: "")
                target != null -> target.label
                else -> pkg.orEmpty()
            }
        val checkUrl: String? get() = target?.checkUrl ?: site?.let { "https://$it/" }
    }

    fun now(context: Context, targets: List<Target>): Now? {
        val recent = if (ForegroundWatcher.hasAccess(context)) try { ForegroundWatcher.recent(context) } catch (_: Throwable) { emptyList() } else emptyList()
        val seen = CurrentSite.fresh(System.currentTimeMillis())
        val pkg = recent.firstOrNull() ?: seen?.pkg
        if (pkg == null) return null
        val site = if (BrowserBars.isBrowser(pkg) && seen != null && seen.pkg == pkg) seen.host else null
        val target = if (site != null) SiteMatch.targetFor(site, targets) else targets.firstOrNull { it.kind == TargetKind.APP && it.value == pkg }
        val others = recent.drop(1).mapNotNull { p -> targets.firstOrNull { it.kind == TargetKind.APP && it.value == p } }.filter { it.id != target?.id }
        return Now(pkg, site, target, others)
    }
}
