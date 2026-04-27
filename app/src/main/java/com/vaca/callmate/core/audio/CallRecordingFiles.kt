package com.vaca.callmate.core.audio

import android.content.Context
import java.io.File

/** 与 iOS `CallAudioStore` 类似：应用私有目录下的通话录音文件。 */
object CallRecordingFiles {
    fun directory(context: Context): File =
        File(context.filesDir, "call_recordings").apply { mkdirs() }

    fun file(context: Context, fileName: String): File = File(directory(context), fileName)
}
