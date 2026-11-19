package com.ghosttap.core

/**
 * BER-TLV reading for payment cards — the little labelled parcels a chip
 * card sends back (each one is a tag number, a length, and a value).
 *
 * Nothing here ever throws: bad bytes give back an empty result instead
 * of a crash, so the engine can answer in plain words.
 */

/** One TLV parcel: its tag as compact uppercase hex (for example "9F26"). */
data class EmvTag(
    val tag: String,
    val value: ByteArray
)

/** One payment application the card offers (from the PPSE answer). */
data class EmvApplication(
    /** Application ID as compact uppercase hex, for example "A0000000031010". */
    val aid: String,
    /** The name the card gives the application, for example "VISA ELECTRON". */
    val label: String?
)

/** One entry of the Application File Locator: which records to read. */
data class EmvRecordRef(
    /** Short File Identifier — which file on the card holds the records. */
    val sfi: Int,
    /** First record number to read in that file. */
    val first: Int,
    /** Last record number to read in that file. */
    val last: Int
)

/** Tags whose value is itself a list of TLV parcels — look inside them. */
private val CONSTRUCTED_TEMPLATES = setOf("6F", "70", "77", "A5", "BF0C", "61")

/** Bytes as compact uppercase hex with no spaces, for example "9F26". */
private fun compactHex(bytes: ByteArray): String =
    bytes.joinToString("") { "%02X".format(it) }

/**
 * Read a run of TLV parcels.
 * Handles multi-byte tags (a first byte ending in 0x1F means more tag
 * bytes follow) and both short and long form lengths. A malformed run
 * gives back an empty list — never a crash.
 */
fun parseTlv(data: ByteArray): List<EmvTag> {
    val out = mutableListOf<EmvTag>()
    var i = 0
    while (i < data.size) {
        // Tag: first byte, then more bytes while the low 5 bits are 0x1F
        // and the high bit of each following byte stays set.
        val tagBytes = mutableListOf<Byte>()
        var b = data[i].toInt() and 0xFF
        i++
        tagBytes += b.toByte()
        if (b and 0x1F == 0x1F) {
            var more = true
            while (more) {
                if (i >= data.size) return emptyList()
                b = data[i].toInt() and 0xFF
                i++
                tagBytes += b.toByte()
                more = b and 0x80 != 0
            }
        }
        // Length: one byte, or long form (high bit set, low bits say how
        // many length bytes follow).
        if (i >= data.size) return emptyList()
        val lengthByte = data[i].toInt() and 0xFF
        i++
        val length: Int
        if (lengthByte and 0x80 == 0) {
            length = lengthByte
        } else {
            val lengthBytes = lengthByte and 0x7F
            if (lengthBytes == 0 || lengthBytes > 4) return emptyList()
            if (i + lengthBytes > data.size) return emptyList()
            var value = 0
            repeat(lengthBytes) {
                value = (value shl 8) or (data[i].toInt() and 0xFF)
                i++
            }
            length = value
        }
        if (length < 0 || i + length > data.size) return emptyList()
        val value = data.copyOfRange(i, i + length)
        i += length
        out += EmvTag(compactHex(tagBytes.toByteArray()), value)
    }
    return out
}

/**
 * Flatten a TLV answer into tag -> value, looking inside the constructed
 * templates (6F, 70, 77, A5, BF0C, 61). When a tag shows up twice, the
 * later value wins — same rule as the lab's Python version.
 */
fun flattenTlv(data: ByteArray): Map<String, ByteArray> {
    val flat = mutableMapOf<String, ByteArray>()
    fun walk(items: List<EmvTag>) {
        for (item in items) {
            if (item.tag in CONSTRUCTED_TEMPLATES) {
                walk(parseTlv(item.value))
            } else {
                flat[item.tag] = item.value
            }
        }
    }
    walk(parseTlv(data))
    return flat
}

/**
 * Read an Application File Locator (tag 94): 4-byte entries of
 * SFI, first record, last record, records used for offline checks.
 * The SFI sits in the top 5 bits of the first byte. A trailing
 * partial entry is ignored.
 */
fun parseAfl(afl: ByteArray): List<EmvRecordRef> {
    val out = mutableListOf<EmvRecordRef>()
    var i = 0
    while (i + 4 <= afl.size) {
        val sfi = (afl[i].toInt() and 0xFF) shr 3
        val first = afl[i + 1].toInt() and 0xFF
        val last = afl[i + 2].toInt() and 0xFF
        out += EmvRecordRef(sfi, first, last)
        i += 4
    }
    return out
}

/**
 * Read a tag list (PDOL tag 9F38, or CDOL tag 8C): tag + one length byte,
 * repeated. Returns each tag with the number of value bytes it asks for.
 * Stops at a truncated tail instead of crashing.
 */
fun parsePdol(pdol: ByteArray): List<Pair<String, Int>> {
    val out = mutableListOf<Pair<String, Int>>()
    var i = 0
    while (i < pdol.size) {
        val tagBytes = mutableListOf<Byte>()
        var b = pdol[i].toInt() and 0xFF
        i++
        tagBytes += b.toByte()
        if (b and 0x1F == 0x1F) {
            var more = true
            while (more) {
                if (i >= pdol.size) return out
                b = pdol[i].toInt() and 0xFF
                i++
                tagBytes += b.toByte()
                more = b and 0x80 != 0
            }
        }
        if (i >= pdol.size) return out
        val length = pdol[i].toInt() and 0xFF
        i++
        out += compactHex(tagBytes.toByteArray()) to length
    }
    return out
}

/** Printable ASCII from bytes (other bytes dropped), trimmed, or null. */
private fun asciiOrNull(bytes: ByteArray): String? {
    val sb = StringBuilder()
    for (b in bytes) {
        val v = b.toInt() and 0xFF
        if (v in 32..126) sb.append(v.toChar())
    }
    val text = sb.toString().trim()
    return text.ifEmpty { null }
}

/**
 * Read the payment applications out of a SELECT PPSE answer: each 61
 * entry under 6F / A5 / BF0C carries an AID (tag 4F) and a name
 * (tag 50). If the card skips the 61 wrapper, a lone 4F / 50 pair
 * at the top level still counts as one application.
 */
fun parsePpseApplications(fciData: ByteArray): List<EmvApplication> {
    val out = mutableListOf<EmvApplication>()
    fun walk(items: List<EmvTag>) {
        for (item in items) {
            when (item.tag) {
                "61" -> {
                    val inner = parseTlv(item.value)
                    val aid = inner.firstOrNull { it.tag == "4F" }?.value
                    if (aid != null) {
                        val label = inner.firstOrNull { it.tag == "50" }?.value?.let { asciiOrNull(it) }
                        out += EmvApplication(compactHex(aid), label)
                    }
                }
                "6F", "A5", "BF0C" -> walk(parseTlv(item.value))
            }
        }
    }
    walk(parseTlv(fciData))
    if (out.isEmpty()) {
        val flat = flattenTlv(fciData)
        val aid = flat["4F"]
        if (aid != null) {
            out += EmvApplication(compactHex(aid), flat["50"]?.let { asciiOrNull(it) })
        }
    }
    return out
}
