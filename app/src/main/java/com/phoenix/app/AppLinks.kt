package com.phoenix.app

import android.content.Context
import android.content.pm.verify.domain.DomainVerificationManager
import android.os.Build

/** Домены, которые приложение объявило своими (Android 12+, без особых разрешений); если система не отдаёт — пусто. */
object AppLinks {
    fun hostsFor(context: Context, pkg: String): List<String> {
        if (Build.VERSION.SDK_INT < 31) return emptyList()
        return try {
            val m = context.getSystemService(DomainVerificationManager::class.java) ?: return emptyList()
            m.getDomainVerificationUserState(pkg)?.hostToStateMap?.keys?.toList().orEmpty()
        } catch (_: Throwable) {
            emptyList()
        }
    }

    /** Правила для всех [packages] разом; ошибки одного приложения не мешают остальным. */
    fun rulesFor(context: Context, packages: Collection<String>): List<String> =
        AppLinkDomains.rules(packages.flatMap { p -> hostsFor(context, p) })
}
