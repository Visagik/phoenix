package com.phoenix.app

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Необязательная служба: читает ТОЛЬКО адресную строку браузеров из списка (см. res/xml/site_watcher.xml) и запоминает в памяти
 * домен открытого сайта. Путь, параметры и содержимое страниц не читаются, ничего не сохраняется на диск и не отправляется.
 * Домен нужен, чтобы при смене сервера подобрать тот, где этот сайт открывается.
 */
class SiteWatcherService : AccessibilityService() {
    private var lastHost = ""
    private var lastAt = 0L

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val pkg = event?.packageName?.toString() ?: return
        if (!BrowserBars.isBrowser(pkg)) return
        val now = System.currentTimeMillis()
        if (now - lastAt < 300) return
        lastAt = now
        try {
            val root = rootInActiveWindow ?: return
            val host = findHost(root, pkg) ?: return
            if (host != lastHost || CurrentSite.fresh(now, 30_000L)?.host != host) {
                lastHost = host
                CurrentSite.set(host, pkg, now)
            }
        } catch (_: Throwable) {
        }
    }

    private fun findHost(root: AccessibilityNodeInfo, pkg: String): String? {
        for (id in BrowserBars.idsFor(pkg)) {
            val nodes = root.findAccessibilityNodeInfosByViewId(id)
            for (n in nodes) BrowserUrl.host(n.text)?.let { return it }
        }
        // запасной путь: первое поле ввода с адресом (идентификатор мог измениться или не известен)
        return scan(root, 0)
    }

    private fun scan(node: AccessibilityNodeInfo, depth: Int): String? {
        if (depth > 8) return null
        if (node.className?.toString()?.endsWith("EditText") == true) BrowserUrl.host(node.text)?.let { return it }
        for (i in 0 until node.childCount) {
            val c = node.getChild(i) ?: continue
            scan(c, depth + 1)?.let { return it }
        }
        return null
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        CurrentSite.clear()
        super.onDestroy()
    }
}
