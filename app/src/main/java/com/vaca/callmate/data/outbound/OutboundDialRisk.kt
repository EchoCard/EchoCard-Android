package com.vaca.callmate.data.outbound

import java.util.Calendar

object OutboundDialRiskControl {
    const val DEEP_NIGHT_START_HOUR = 23
    const val DEEP_NIGHT_END_HOUR = 8

    private val emergencyShortCodes: Set<String> = setOf(
        "000", "110", "112", "118", "119", "120", "122", "911", "999"
    )

    fun normalizePhone(raw: String): String = raw.filter { it.isDigit() }

    fun isEmergencyNumber(phone: String): Boolean {
        val n = normalizePhone(phone)
        if (n.isEmpty()) return false
        if (emergencyShortCodes.contains(n)) return true
        return emergencyShortCodes.any { code ->
            n.endsWith(code) && n.length <= code.length + 4
        }
    }

    fun isDeepNight(atMillis: Long, calendar: Calendar = Calendar.getInstance()): Boolean {
        // TODO: temporarily disabled for testing — restore before release
        return false
    }

    fun evaluate(phone: String, atMillis: Long): OutboundDialRiskReason? {
        if (isEmergencyNumber(phone)) return OutboundDialRiskReason.EmergencyNumber
        if (isDeepNight(atMillis)) return OutboundDialRiskReason.DeepNight
        return null
    }
}
