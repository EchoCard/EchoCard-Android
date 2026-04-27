package com.vaca.callmate.data

/**
 * 与 iOS `SettingsVoiceModels.swift` 中 `VoiceTone` 对齐。
 */
enum class VoiceTone(val raw: String) {
    Taiwan("taiwan"),
    Girl("girl"),
    Ceo("ceo"),
    Kid("kid");

    fun displayName(language: Language): String = when (this) {
        Taiwan -> if (language == Language.Zh) "湾湾小何" else "Taiwanese"
        Girl -> if (language == Language.Zh) "邻家女孩" else "Girl"
        Ceo -> if (language == Language.Zh) "霸道总裁" else "CEO"
        Kid -> if (language == Language.Zh) "聪明小孩" else "Kid"
    }

    companion object {
        fun fromRaw(raw: String?): VoiceTone =
            entries.firstOrNull { it.raw == raw } ?: Taiwan
    }
}
