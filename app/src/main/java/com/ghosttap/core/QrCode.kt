package com.ghosttap.core

/**
 * PIECE 2 (2026-11-18) — QR code encoder, pure Kotlin, zero dependencies.
 *
 * Encodes text (UTF-8, byte mode) as a QR code, error-correction level L,
 * versions 1–10 (up to 271 bytes). The matrix is drawn by the Network
 * Tools screen's QR panel — no image library, no new dependency.
 *
 * The algorithm follows the QR specification (ISO/IEC 18004): Reed-Solomon
 * error correction over GF(256) with primitive 0x11D, BCH format/version
 * info, function patterns, and mask selection by penalty score. The two
 * BCH computations were verified against the published tables before this
 * file was written (format bits for every error level + mask, version
 * bits for versions 7 and 10 — all match).
 *
 * Honest limits: level L only (about 7% damage tolerance — enough for a
 * screen-shown code), byte mode only, 271 bytes maximum. Anything longer
 * returns null and the caller says so in plain words instead of drawing
 * a code that cannot be read.
 */

/** Error-correction level. Only LOW is used by the encoder. */
enum class QrEcc(val formatBits: Int) {
    LOW(1),
    MEDIUM(0),
    QUARTILE(3),
    HIGH(2)
}

/** One finished QR code: a square grid of dark/light modules. */
class QrCode(
    val version: Int,
    val size: Int,
    private val modules: BooleanArray
) {
    /** True when the module at (x, y) is dark. */
    fun get(x: Int, y: Int): Boolean = modules[y * size + x]
}

object QrCodeEncoder {

    // Total codewords (data + error correction) per version, versions 1–10.
    private val TOTAL_CODEWORDS = intArrayOf(26, 44, 70, 100, 134, 172, 196, 242, 292, 346)

    // Error-correction codewords per block, indexed [ecl][version].
    // Only the LOW row is used, the rest are kept so the table is complete.
    private val ECC_PER_BLOCK = arrayOf(
        intArrayOf(7, 10, 15, 20, 26, 18, 20, 24, 30, 18),   // LOW
        intArrayOf(10, 16, 26, 18, 24, 16, 18, 22, 22, 26),  // MEDIUM
        intArrayOf(13, 22, 18, 26, 18, 24, 18, 22, 20, 24),  // QUARTILE
        intArrayOf(17, 28, 22, 16, 22, 28, 26, 26, 24, 28)   // HIGH
    )

    // Number of error-correction blocks, indexed [ecl][version].
    // Version 10 level L is 4 blocks (verified against the spec's
    // byte capacity: 271 bytes need 274 data codewords, 346 - 274
    // = 72 error-correction codewords = 4 x 18).
    private val NUM_BLOCKS = arrayOf(
        intArrayOf(1, 1, 1, 1, 1, 2, 2, 2, 2, 4),  // LOW
        intArrayOf(1, 1, 1, 2, 2, 4, 4, 4, 5, 5),  // MEDIUM
        intArrayOf(1, 1, 2, 2, 4, 4, 6, 6, 8, 8),  // QUARTILE
        intArrayOf(1, 1, 2, 4, 4, 4, 5, 6, 8, 8)   // HIGH
    )

    // Alignment pattern centre coordinates per version (empty for version 1).
    private val ALIGNMENT_POSITIONS = arrayOf(
        intArrayOf(),
        intArrayOf(6, 18),
        intArrayOf(6, 22),
        intArrayOf(6, 26),
        intArrayOf(6, 30),
        intArrayOf(6, 34),
        intArrayOf(6, 22, 38),
        intArrayOf(6, 24, 42),
        intArrayOf(6, 26, 46),
        intArrayOf(6, 28, 50)
    )

    const val MAX_BYTES = 271 // Version 10, level L, byte mode.

    /**
     * Encodes [text] as a QR code. Null when the text is too long
     * (over 271 UTF-8 bytes) or empty.
     */
    fun encode(text: String): QrCode? {
        val data = text.toByteArray(Charsets.UTF_8)
        if (data.isEmpty() || data.size > MAX_BYTES) return null
        val ecl = QrEcc.LOW
        // Smallest version that fits: mode (4) + count (8/16) + data + terminator.
        var version = -1
        for (v in 1..10) {
            val dataCodewords = dataCodewords(v, ecl)
            val charCountBits = if (v <= 9) 8 else 16
            val bitsNeeded = 4 + charCountBits + data.size * 8
            if (bitsNeeded <= dataCodewords * 8) {
                version = v
                break
            }
        }
        if (version < 1) return null
        val dataCodewords = dataCodewords(version, ecl)
        val bb = BitBuffer()
        bb.appendBits(0b0100, 4) // Byte mode.
        bb.appendBits(data.size, if (version <= 9) 8 else 16)
        for (b in data) bb.appendBits(b.toInt() and 0xFF, 8)
        val capacityBits = dataCodewords * 8
        bb.appendBits(0, minOf(4, capacityBits - bb.bitLength()))
        bb.appendBits(0, (8 - bb.bitLength() % 8) % 8)
        var pad = 0xEC
        while (bb.bitLength() < capacityBits) {
            bb.appendBits(pad, 8)
            pad = pad xor 0xEC xor 0x11
        }
        val codewords = interleave(bb.bytes(), version, ecl)
        return QrCode(version, version * 4 + 17, buildMatrix(codewords, version, ecl))
    }

    private fun dataCodewords(version: Int, ecl: QrEcc): Int {
        val e = ecl.ordinal
        return TOTAL_CODEWORDS[version - 1] -
            ECC_PER_BLOCK[e][version - 1] * NUM_BLOCKS[e][version - 1]
    }

    /** Splits data codewords into blocks, adds error correction, interleaves. */
    private fun interleave(data: ByteArray, version: Int, ecl: QrEcc): ByteArray {
        val e = ecl.ordinal
        val numBlocks = NUM_BLOCKS[e][version - 1]
        val eccLen = ECC_PER_BLOCK[e][version - 1]
        val dataLen = dataCodewords(version, ecl)
        val divisor = reedSolomonDivisor(eccLen)
        // Short blocks hold floor(dataLen / numBlocks) data codewords,
        // long blocks one more. (For level L versions 1–10 every block
        // is the same length, but the general split costs nothing.)
        val shortLen = dataLen / numBlocks
        val numShort = numBlocks - dataLen % numBlocks
        val blocks = Array(numBlocks) { i ->
            val len = shortLen + if (i < numShort) 0 else 1
            val start = i * shortLen + maxOf(0, i - numShort)
            val blockData = data.copyOfRange(start, start + len)
            blockData + reedSolomonRemainder(blockData, divisor)
        }
        // Interleave: data codewords first (column by column), then ECC.
        val out = mutableListOf<Byte>()
        val maxDataLen = blocks.maxOf { it.size - eccLen }
        for (i in 0 until maxDataLen) {
            for (b in blocks) {
                if (i < b.size - eccLen) out.add(b[i])
            }
        }
        for (i in 0 until eccLen) {
            for (b in blocks) out.add(b[b.size - eccLen + i])
        }
        return out.toByteArray()
    }

    private fun reedSolomonDivisor(degree: Int): ByteArray {
        val result = ByteArray(degree)
        result[degree - 1] = 1
        var root = 1
        for (i in 0 until degree) {
            for (j in 0 until degree) {
                result[j] = reedSolomonMultiply(result[j].toInt() and 0xFF, root).toByte()
                if (j + 1 < degree) {
                    result[j] = (result[j].toInt() xor (result[j + 1].toInt() and 0xFF)).toByte()
                }
            }
            root = reedSolomonMultiply(root, 0x02)
        }
        return result
    }

    private fun reedSolomonRemainder(data: ByteArray, divisor: ByteArray): ByteArray {
        val result = ByteArray(divisor.size)
        for (b in data) {
            val factor = (b.toInt() and 0xFF) xor (result[0].toInt() and 0xFF)
            for (i in result.indices) {
                result[i] = if (i + 1 < result.size) result[i + 1] else 0
            }
            for (i in result.indices) {
                result[i] = (result[i].toInt() xor
                    reedSolomonMultiply(divisor[i].toInt() and 0xFF, factor)).toByte()
            }
        }
        return result
    }

    private fun reedSolomonMultiply(x: Int, y: Int): Int {
        var z = 0
        for (i in 7 downTo 0) {
            z = (z shl 1) xor ((z shr 7) * 0x11D)
            z = z xor ((y shr i) and 1) * x
        }
        return z
    }

    /** Builds the module matrix for the finished codeword stream. */
    private fun buildMatrix(codewords: ByteArray, version: Int, ecl: QrEcc): BooleanArray {
        val size = version * 4 + 17
        val modules = BooleanArray(size * size)
        val isFunction = BooleanArray(size * size)
        fun set(x: Int, y: Int, value: Boolean) {
            modules[y * size + x] = value
            isFunction[y * size + x] = true
        }
        // Timing patterns.
        for (i in 0 until size) {
            set(6, i, i % 2 == 0)
            set(i, 6, i % 2 == 0)
        }
        // Finder patterns (top-left, top-right, bottom-left).
        val finderCentres = listOf(3 to 3, size - 4 to 3, 3 to size - 4)
        for ((cx, cy) in finderCentres) {
            for (dy in -4..4) {
                for (dx in -4..4) {
                    val dist = maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy))
                    val x = cx + dx
                    val y = cy + dy
                    if (x in 0 until size && y in 0 until size) {
                        set(x, y, dist != 2 && dist != 4)
                    }
                }
            }
        }
        // Alignment patterns (skip the three that overlap a finder).
        val align = ALIGNMENT_POSITIONS[version - 1]
        val alignLast = align.lastOrNull() ?: -1
        for (ay in align) {
            for (ax in align) {
                val overlapsFinder = (ax == 6 && ay == 6) ||
                    (ax == 6 && ay == alignLast) ||
                    (ax == alignLast && ay == 6)
                if (overlapsFinder) continue
                for (dy in -2..2) {
                    for (dx in -2..2) {
                        set(ax + dx, ay + dy, maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) != 1)
                    }
                }
            }
        }
        // Reserve the format-info areas (filled in after masking).
        for (i in 0..5) set(8, i, false)
        set(8, 7, false)
        set(8, 8, false)
        set(7, 8, false)
        for (i in 9..14) set(14 - i, 8, false)
        for (i in 0..7) set(size - 1 - i, 8, false)
        for (i in 8..14) set(8, size - 15 + i, false)
        // Dark module: column 8, row 4V+9 — i.e. (x=8, y=size-8).
        // (Transposing this shifts every later data bit by one.)
        set(8, size - 8, true)
        // Version info (versions 7 and up).
        if (version >= 7) {
            var rem = version
            for (i in 0 until 12) {
                rem = (rem shl 1) xor ((rem shr 11) * 0x1F25)
            }
            val bits = (version shl 12) or rem
            for (i in 0 until 18) {
                val bit = getBit(bits, i)
                val a = size - 11 + i % 3
                val b = i / 3
                set(a, b, bit)
                set(b, a, bit)
            }
        }
        // Data codewords, zig-zagging up the two-column bands.
        var bitIndex = 0
        var right = size - 1
        while (right >= 1) {
            if (right == 6) right--
            for (vert in 0 until size) {
                for (j in 0..1) {
                    val x = right - j
                    val upward = ((right + 1) and 2) == 0
                    val y = if (upward) size - 1 - vert else vert
                    if (!isFunction[y * size + x] && bitIndex < codewords.size * 8) {
                        modules[y * size + x] =
                            getBit(codewords[bitIndex ushr 3].toInt() and 0xFF, 7 - (bitIndex and 7))
                        bitIndex++
                    }
                }
            }
            right -= 2
        }
        // Try every mask, keep the one with the lowest penalty score.
        var bestMask = 0
        var bestScore = Int.MAX_VALUE
        val trial = modules.copyOf()
        for (mask in 0..7) {
            applyMask(modules, isFunction, size, mask)
            val score = penaltyScore(modules, size)
            if (score < bestScore) {
                bestScore = score
                bestMask = mask
                trial.indices.forEach { trial[it] = modules[it] }
            }
            applyMask(modules, isFunction, size, mask) // Undo: masking twice restores.
        }
        trial.indices.forEach { modules[it] = trial[it] }
        // Format info for the winning mask.
        var rem = (ecl.formatBits shl 3) or bestMask
        for (i in 0 until 10) {
            rem = (rem shl 1) xor ((rem shr 9) * 0x537)
        }
        val bits = (((ecl.formatBits shl 3) or bestMask) shl 10 or rem) xor 0x5412
        for (i in 0..5) set(8, i, getBit(bits, i))
        set(8, 7, getBit(bits, 6))
        set(8, 8, getBit(bits, 7))
        set(7, 8, getBit(bits, 8))
        for (i in 9..14) set(14 - i, 8, getBit(bits, i))
        for (i in 0..7) set(size - 1 - i, 8, getBit(bits, i))
        for (i in 8..14) set(8, size - 15 + i, getBit(bits, i))
        set(8, size - 8, true) // Dark module (column 8, row 4V+9).
        return modules
    }

    private fun applyMask(
        modules: BooleanArray,
        isFunction: BooleanArray,
        size: Int,
        mask: Int
    ) {
        for (y in 0 until size) {
            for (x in 0 until size) {
                if (isFunction[y * size + x]) continue
                val invert = when (mask) {
                    0 -> (x + y) % 2 == 0
                    1 -> y % 2 == 0
                    2 -> x % 3 == 0
                    3 -> (x + y) % 3 == 0
                    4 -> (y / 2 + x / 3) % 2 == 0
                    5 -> (x * y) % 2 + (x * y) % 3 == 0
                    6 -> ((x * y) % 2 + (x * y) % 3) % 2 == 0
                    else -> ((x + y) % 2 + (x * y) % 3) % 2 == 0
                }
                if (invert) modules[y * size + x] = !modules[y * size + x]
            }
        }
    }

    /** Penalty score: lower is easier for a scanner to read. */
    private fun penaltyScore(modules: BooleanArray, size: Int): Int {
        var score = 0
        // Runs of 5+ same-colour modules in a row or column.
        for (y in 0 until size) {
            var runColor = modules[y * size]
            var runLen = 1
            for (x in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) runLen++ else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }
        for (x in 0 until size) {
            var runColor = modules[x]
            var runLen = 1
            for (y in 1 until size) {
                val c = modules[y * size + x]
                if (c == runColor) runLen++ else {
                    if (runLen >= 5) score += 3 + (runLen - 5)
                    runColor = c
                    runLen = 1
                }
            }
            if (runLen >= 5) score += 3 + (runLen - 5)
        }
        // 2x2 blocks of one colour.
        for (y in 0 until size - 1) {
            for (x in 0 until size - 1) {
                val c = modules[y * size + x]
                if (c == modules[y * size + x + 1] &&
                    c == modules[(y + 1) * size + x] &&
                    c == modules[(y + 1) * size + x + 1]
                ) {
                    score += 3
                }
            }
        }
        // Finder-like patterns (dark-light-dark-dark-dark-light-dark
        // with light borders) in rows and columns.
        val finderLike = booleanArrayOf(true, false, true, true, true, false, true)
        for (y in 0 until size) {
            for (x in 0 until size - 6) {
                var match = true
                for (i in 0..6) {
                    if (modules[y * size + x + i] != finderLike[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    val before = (0 until 4).all { x - 1 - it >= 0 && !modules[y * size + x - 1 - it] }
                    val after = (0 until 4).all { x + 7 + it < size && !modules[y * size + x + 7 + it] }
                    if (before || after) score += 40
                }
            }
        }
        for (x in 0 until size) {
            for (y in 0 until size - 6) {
                var match = true
                for (i in 0..6) {
                    if (modules[(y + i) * size + x] != finderLike[i]) {
                        match = false
                        break
                    }
                }
                if (match) {
                    val before = (0 until 4).all { y - 1 - it >= 0 && !modules[(y - 1 - it) * size + x] }
                    val after = (0 until 4).all { y + 7 + it < size && !modules[(y + 7 + it) * size + x] }
                    if (before || after) score += 40
                }
            }
        }
        // Dark-module ratio: the further from half dark, the worse.
        var dark = 0
        for (m in modules) if (m) dark++
        val total = size * size
        val percent = dark * 100 / total
        score += (kotlin.math.abs(percent - 50) / 5) * 10
        return score
    }

    private fun getBit(value: Int, index: Int): Boolean = ((value ushr index) and 1) != 0

    /** Growable bit list, most-significant-bit first. */
    private class BitBuffer {
        private val bits = mutableListOf<Int>()
        fun bitLength(): Int = bits.size
        fun appendBits(value: Int, length: Int) {
            for (i in length - 1 downTo 0) {
                bits.add((value ushr i) and 1)
            }
        }
        fun bytes(): ByteArray {
            val out = ByteArray(bits.size / 8)
            for (i in bits.indices) {
                if (bits[i] != 0) {
                    out[i / 8] = (out[i / 8].toInt() or (1 shl (7 - i % 8))).toByte()
                }
            }
            return out
        }
    }
}
