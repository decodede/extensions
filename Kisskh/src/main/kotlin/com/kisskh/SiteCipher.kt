package com.kisskh

class SiteCipher(private val roundKeys: IntArray, private val iv: IntArray) {
    init {
        require(roundKeys.size == 44) { "expected 44 round-key words, got ${roundKeys.size}" }
        require(iv.size == 4) { "expected a 4-word IV, got ${iv.size}" }
    }

    fun encrypt(text: String): String {
        val padded = pkcs7(text)
        val words = toWords(padded)
        var offset = 0
        while (offset < words.size) {
            encryptBlock(words, offset)
            offset += 4
        }
        return toHex(words, padded.length)
    }

    private fun encryptBlock(state: IntArray, offset: Int) {
        val previous = if (offset == 0) iv else state.copyOfRange(offset - 4, offset)
        for (i in 0..3) state[offset + i] = state[offset + i] xor previous[i]

        var a = state[offset] xor roundKeys[0]
        var b = state[offset + 1] xor roundKeys[1]
        var c = state[offset + 2] xor roundKeys[2]
        var d = state[offset + 3] xor roundKeys[3]
        var k = 4

        repeat(9) {
            val na = T0[a ushr 24] xor T1[(b ushr 16) and 0xFF] xor T2[(c ushr 8) and 0xFF] xor
                T3[d and 0xFF] xor roundKeys[k++]
            val nb = T0[b ushr 24] xor T1[(c ushr 16) and 0xFF] xor T2[(d ushr 8) and 0xFF] xor
                T3[a and 0xFF] xor roundKeys[k++]
            val nc = T0[c ushr 24] xor T1[(d ushr 16) and 0xFF] xor T2[(a ushr 8) and 0xFF] xor
                T3[b and 0xFF] xor roundKeys[k++]
            val nd = T0[d ushr 24] xor T1[(a ushr 16) and 0xFF] xor T2[(b ushr 8) and 0xFF] xor
                T3[c and 0xFF] xor roundKeys[k++]
            a = na; b = nb; c = nc; d = nd
        }

        state[offset] = ((SBOX[a ushr 24].toInt() shl 24) or (SBOX[(b ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(c ushr 8) and 0xFF].toInt() shl 8) or SBOX[d and 0xFF].toInt()) xor roundKeys[k++]
        state[offset + 1] = ((SBOX[b ushr 24].toInt() shl 24) or (SBOX[(c ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(d ushr 8) and 0xFF].toInt() shl 8) or SBOX[a and 0xFF].toInt()) xor roundKeys[k++]
        state[offset + 2] = ((SBOX[c ushr 24].toInt() shl 24) or (SBOX[(d ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(a ushr 8) and 0xFF].toInt() shl 8) or SBOX[b and 0xFF].toInt()) xor roundKeys[k++]
        state[offset + 3] = ((SBOX[d ushr 24].toInt() shl 24) or (SBOX[(a ushr 16) and 0xFF].toInt() shl 16) or
            (SBOX[(b ushr 8) and 0xFF].toInt() shl 8) or SBOX[c and 0xFF].toInt()) xor roundKeys[k]
    }

    companion object {
        private val SBOX = intArrayOf(
            0x63, 0x7C, 0x77, 0x7B, 0xF2, 0x6B, 0x6F, 0xC5, 0x30, 0x01, 0x67, 0x2B, 0xFE, 0xD7, 0xAB, 0x76,
            0xCA, 0x82, 0xC9, 0x7D, 0xFA, 0x59, 0x47, 0xF0, 0xAD, 0xD4, 0xA2, 0xAF, 0x9C, 0xA4, 0x72, 0xC0,
            0xB7, 0xFD, 0x93, 0x26, 0x36, 0x3F, 0xF7, 0xCC, 0x34, 0xA5, 0xE5, 0xF1, 0x71, 0xD8, 0x31, 0x15,
            0x04, 0xC7, 0x23, 0xC3, 0x18, 0x96, 0x05, 0x9A, 0x07, 0x12, 0x80, 0xE2, 0xEB, 0x27, 0xB2, 0x75,
            0x09, 0x83, 0x2C, 0x1A, 0x1B, 0x6E, 0x5A, 0xA0, 0x52, 0x3B, 0xD6, 0xB3, 0x29, 0xE3, 0x2F, 0x84,
            0x53, 0xD1, 0x00, 0xED, 0x20, 0xFC, 0xB1, 0x5B, 0x6A, 0xCB, 0xBE, 0x39, 0x4A, 0x4C, 0x58, 0xCF,
            0xD0, 0xEF, 0xAA, 0xFB, 0x43, 0x4D, 0x33, 0x85, 0x45, 0xF9, 0x02, 0x7F, 0x50, 0x3C, 0x9F, 0xA8,
            0x51, 0xA3, 0x40, 0x8F, 0x92, 0x9D, 0x38, 0xF5, 0xBC, 0xB6, 0xDA, 0x21, 0x10, 0xFF, 0xF3, 0xD2,
            0xCD, 0x0C, 0x13, 0xEC, 0x5F, 0x97, 0x44, 0x17, 0xC4, 0xA7, 0x7E, 0x3D, 0x64, 0x5D, 0x19, 0x73,
            0x60, 0x81, 0x4F, 0xDC, 0x22, 0x2A, 0x90, 0x88, 0x46, 0xEE, 0xB8, 0x14, 0xDE, 0x5E, 0x0B, 0xDB,
            0xE0, 0x32, 0x3A, 0x0A, 0x49, 0x06, 0x24, 0x5C, 0xC2, 0xD3, 0xAC, 0x62, 0x91, 0x95, 0xE4, 0x79,
            0xE7, 0xC8, 0x37, 0x6D, 0x8D, 0xD5, 0x4E, 0xA9, 0x6C, 0x56, 0xF4, 0xEA, 0x65, 0x7A, 0xAE, 0x08,
            0xBA, 0x78, 0x25, 0x2E, 0x1C, 0xA6, 0xB4, 0xC6, 0xE8, 0xDD, 0x74, 0x1F, 0x4B, 0xBD, 0x8B, 0x8A,
            0x70, 0x3E, 0xB5, 0x66, 0x48, 0x03, 0xF6, 0x0E, 0x61, 0x35, 0x57, 0xB9, 0x86, 0xC1, 0x1D, 0x9E,
            0xE1, 0xF8, 0x98, 0x11, 0x69, 0xD9, 0x8E, 0x94, 0x9B, 0x1E, 0x87, 0xE9, 0xCE, 0x55, 0x28, 0xDF,
            0x8C, 0xA1, 0x89, 0x0D, 0xBF, 0xE6, 0x42, 0x68, 0x41, 0x99, 0x2D, 0x0F, 0xB0, 0x54, 0xBB, 0x16,
        )

        private val T0 = IntArray(256)
        private val T1 = IntArray(256)
        private val T2 = IntArray(256)
        private val T3 = IntArray(256)

        init {
            for (i in 0 until 256) {
                val s = SBOX[i]
                val u = ((s shl 1) xor if (s and 0x80 != 0) 0x11B else 0) and 0xFF
                val v = s xor u
                T0[i] = (u shl 24) or (s shl 16) or (s shl 8) or v
                T1[i] = (v shl 24) or (u shl 16) or (s shl 8) or s
                T2[i] = (s shl 24) or (v shl 16) or (u shl 8) or s
                T3[i] = (s shl 24) or (s shl 16) or (v shl 8) or u
            }
        }

        private fun pkcs7(text: String): String {
            val pad = 16 - text.length % 16
            return text + pad.toChar().toString().repeat(pad)
        }

        private fun toWords(text: String): IntArray {
            val words = IntArray(text.length / 4)
            for (i in text.indices) {
                words[i ushr 2] = words[i ushr 2] or ((text[i].code and 0xFF) shl (24 - (i % 4) * 8))
            }
            return words
        }

        private fun toHex(words: IntArray, length: Int): String {
            val out = StringBuilder(length * 2)
            for (i in 0 until length) {
                val byte = (words[i ushr 2] ushr (24 - (i % 4) * 8)) and 0xFF
                out.append("%02X".format(byte))
            }
            return out.toString()
        }

        private fun stringHash(text: String): Double {
            var hash = 0.0
            for (ch in text) hash = (hash.toInt() shl 5).toDouble() - hash + ch.code
            return hash
        }

        fun plaintext(
            episodeId: Long,
            appVer: String,
            guid: String,
            platformVer: Int,
            appName: String,
            salt: String,
        ): String {
            val parts = ArrayList<Any?>(15)
            parts += ""
            parts += episodeId
            parts += null
            parts += salt
            parts += appVer
            parts += guid
            parts += platformVer
            repeat(6) { parts += appName }
            parts += "00"
            parts += ""
            val joined = parts.joinToString("|")
            val withHash = ArrayList<Any?>(parts.size + 1)
            withHash += ""
            withHash += formatNumber(stringHash(joined))
            withHash += parts.subList(1, parts.size)
            return withHash.joinToString("|")
        }

        private fun formatNumber(value: Double): String =
            if (value == Math.floor(value)) value.toLong().toString() else value.toString()
    }
}
