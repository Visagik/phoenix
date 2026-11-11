package com.phoenix.app

import android.app.PendingIntent
import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch

/** Плитка «Phoenix VPN» в шторке уведомлений: включает и выключает VPN. */
class PhoenixTileService : TileService() {
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
        val phase = VpnState.status.value.phase
        if (phase == VpnPhase.CONNECTED || phase == VpnPhase.CONNECTING) {
            PhoenixVpnService.stop(this)
            return
        }
        if (VpnService.prepare(this) != null) {
            openApp() // нужно один раз дать системное разрешение на VPN
            return
        }
        // выбор читает все конфиги — не на главном потоке; решение возвращается на него
        val ctx = applicationContext
        Thread({
            val has = try { QuickConnect.pick(ctx) != null } catch (_: Throwable) { false }
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (!has) openApp() // подключаться не к чему — пусть пользователь выберет конфиг
                else PhoenixVpnService.start(ctx) // пустая ссылка: последний или лучший конфиг
            }
        }, "phoenix-tile-pick").start()
    }

    private fun openApp() {
        val intent = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render(status: VpnStatus) {
        val tile = qsTile ?: return
        tile.label = "Phoenix VPN"
        when (status.phase) {
            VpnPhase.CONNECTED -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = status.title.ifBlank { "Подключено" }
            }
            VpnPhase.CONNECTING -> {
                tile.state = Tile.STATE_ACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = "Подключение…"
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (status.phase == VpnPhase.ERROR) "Ошибка" else "Выключен"
            }
        }
        tile.updateTile()
    }
}
