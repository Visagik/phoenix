package com.phoenix.app

/** Светофор «успеет ли автосбор сработать ночью»: зелёный — всё выдано, жёлтый — сработает, но возможны сбои, красный — не запланирован. */
enum class HealthLevel { OK, WARN, BAD }

object BackgroundHealth {
    fun level(batteryUnrestricted: Boolean, exactAlarmAllowed: Boolean, alarmSet: Boolean): HealthLevel = when {
        !alarmSet -> HealthLevel.BAD
        batteryUnrestricted && exactAlarmAllowed -> HealthLevel.OK
        else -> HealthLevel.WARN
    }
}
