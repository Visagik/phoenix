package com.phoenix.app

import android.app.Activity
import android.os.Bundle

/**
 * Невидимая точка входа для ярлыков на значке приложения (долгое нажатие): «Не работает», «Сменить сервер», «Отключить».
 * Ярлык может запускать только экран, поэтому экран тут же закрывается, а действие уходит службе.
 */
class ShortcutActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val phase = VpnState.status.value.phase
        when (intent?.action) {
            ACTION_FIX -> if (phase == VpnPhase.CONNECTED) PhoenixVpnService.fix(this) else notConnected()
            ACTION_SWITCH -> if (phase == VpnPhase.CONNECTED) PhoenixVpnService.switchServer(this) else notConnected()
            ACTION_STOP -> if (phase != VpnPhase.IDLE) PhoenixVpnService.stop(this) else notConnected()
        }
        finish()
    }

    private fun notConnected() {
        android.widget.Toast.makeText(this, tr("VPN не подключён"), android.widget.Toast.LENGTH_SHORT).show()
    }

    companion object {
        const val ACTION_FIX = "com.phoenix.app.shortcut.FIX"
        const val ACTION_SWITCH = "com.phoenix.app.shortcut.SWITCH"
        const val ACTION_STOP = "com.phoenix.app.shortcut.STOP"
    }
}
