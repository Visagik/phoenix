package com.phoenix.app

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.widget.RemoteViews
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/** Intent, открывающий приложение сразу со списком серверов. */
fun switchListIntent(context: Context): Intent =
    Intent(context, MainActivity::class.java).putExtra(SwitchRequest.EXTRA_SHOW, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)

/** Плитка «Сменить сервер» в шторке: открывает список серверов. */
class SwitchTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val s = CoroutineScope(Dispatchers.Main)
        scope = s
        s.launch { VpnState.status.collect { render(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (VpnState.status.value.phase != VpnPhase.CONNECTED) return // подключён — тогда есть что менять
        val intent = switchListIntent(this)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(PendingIntent.getActivity(this, 3, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT))
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.label = "Сменить сервер"
        val on = status.phase == VpnPhase.CONNECTED
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) status.title.ifBlank { "Подключено" } else "VPN выключен"
        tile.updateTile()
    }
}

/** Плитка «Умная смена» в шторке: проверяет сеть и туннель и подбирает сервер под открытое приложение. */
class SmartTileService : TileService() {
    private var scope: CoroutineScope? = null

    override fun onStartListening() {
        super.onStartListening()
        val sc = CoroutineScope(Dispatchers.Main)
        scope = sc
        sc.launch { VpnState.status.collect { render(it) } }
    }

    override fun onStopListening() {
        scope?.cancel()
        scope = null
        super.onStopListening()
    }

    override fun onClick() {
        super.onClick()
        if (VpnState.status.value.phase != VpnPhase.CONNECTED) return
        PhoenixVpnService.smartSwitch(this)
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.label = "Умная смена"
        val on = status.phase == VpnPhase.CONNECTED
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_UNAVAILABLE
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "под это приложение" else "VPN выключен"
        tile.updateTile()
    }
}

/** Виджет на рабочем столе: что подключено сейчас; нажатие открывает список серверов для смены. */
class PhoenixWidgetProvider : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        for (id in ids) manager.updateAppWidget(id, views(context, VpnState.status.value))
    }

    companion object {
        fun views(context: Context, status: VpnStatus): RemoteViews {
            val v = RemoteViews(context.packageName, R.layout.widget_switch)
            val on = status.phase == VpnPhase.CONNECTED
            v.setTextViewText(R.id.widget_title, if (on || status.phase == VpnPhase.CONNECTING) status.title.ifBlank { "Phoenix VPN" } else "Phoenix VPN")
            v.setTextViewText(
                R.id.widget_sub,
                when (status.phase) {
                    VpnPhase.CONNECTED -> status.note.ifBlank { "Подключено" }
                    VpnPhase.CONNECTING -> "Подключение…"
                    VpnPhase.ERROR -> "Ошибка подключения"
                    else -> "Выключен"
                }
            )
            v.setTextViewText(R.id.widget_action, if (on) "Сменить сервер ›" else "Открыть приложение ›")
            val intent = if (on) switchListIntent(context) else Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            v.setOnClickPendingIntent(
                R.id.widget_root,
                PendingIntent.getActivity(context, 4, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            )
            v.setTextViewText(R.id.widget_smart, if (on) "Умная смена ›" else "")
            if (on) {
                v.setOnClickPendingIntent(
                    R.id.widget_smart,
                    PendingIntent.getService(
                        context, 7, Intent(context, PhoenixVpnService::class.java).setAction(PhoenixVpnService.ACTION_SMART),
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                    )
                )
            }
            return v
        }

        /** Перерисовать все виджеты (при смене состояния VPN). */
        fun refresh(context: Context) {
            try {
                val m = AppWidgetManager.getInstance(context)
                val ids = m.getAppWidgetIds(ComponentName(context, PhoenixWidgetProvider::class.java))
                if (ids.isEmpty()) return
                val v = views(context, VpnState.status.value)
                for (id in ids) m.updateAppWidget(id, v)
            } catch (_: Throwable) {
            }
        }
    }
}
