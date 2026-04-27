@file:OptIn(kotlin.ExperimentalUnsignedTypes::class)

package com.vaca.callmate.core.firmware

/**
 * 与 iOS `CRC32MPEG2.swift` 一致（MPEG-2 / J.2 多项式 0x04C11DB7）。
 */
object CRC32MPEG2 {

    private val table: UIntArray = UIntArray(256) { i ->
        var crc = i.toUInt() shl 24
        repeat(8) {
            crc = if ((crc and 0x8000_0000u) != 0u) {
                (crc shl 1) xor 0x04C11DB7u
            } else {
                crc shl 1
            }
        }
        crc
    }

    fun checksum(data: ByteArray, seed: UInt = 0xFFFF_FFFFu): UInt {
        var crc = seed
        for (b in data) {
            val byteU = (b.toInt() and 0xFF).toUInt()
            val idx = (((crc shr 24) xor byteU) and 0xFFu).toInt()
            crc = (crc shl 8) xor table[idx]
        }
        return crc
    }
}
