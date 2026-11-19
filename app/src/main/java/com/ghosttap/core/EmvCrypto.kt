package com.ghosttap.core

import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/**
 * The card maths: session keys and the Retail MAC that prove a card's
 * one-time code — the EMV Book 2 recipes, using only javax.crypto.
 *
 * Every function here returns null instead of throwing when the crypto
 * cannot run, so a bad answer from a card stays a plain-words failure.
 */

/**
 * Two-key triple DES comes as 16 bytes (K1, K2); Java's DESede
 * wants 24 bytes, so expand it to K1, K2, K1 — the exact same
 * cipher PyCryptodome runs with a 16-byte key, and the form
 * Android's triple DES accepts too.
 */
private fun expandDesEdeKey(key: ByteArray): ByteArray =
    if (key.size == 16) key + key.copyOfRange(0, 8) else key

/**
 * Make the per-tap session key from the card's master key and its
 * transaction counter (EMV Book 2 A1.3.1, common derivation): a
 * 16-byte block holding the counter twice around 0xF0 / 0x0F,
 * encrypted with triple DES under the master key. A 16-byte key is
 * two-key triple DES — the same as the lab's Python version.
 */
fun emvSessionKey(masterKey: ByteArray, atc: ByteArray): ByteArray? {
    // Size checks first — the crypto below assumes them.
    if (masterKey.size != 16 && masterKey.size != 24) return null
    if (atc.size < 2) return null
    return try {
        val counter = atc.copyOfRange(atc.size - 2, atc.size)
        val block = ByteArray(16)
        block[0] = counter[0]
        block[1] = counter[1]
        block[2] = 0xF0.toByte()
        block[8] = counter[0]
        block[9] = counter[1]
        block[10] = 0x0F.toByte()
        val cipher = Cipher.getInstance("DESede/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(expandDesEdeKey(masterKey), "DESede"))
        cipher.doFinal(block)
    } catch (_: Exception) {
        null
    }
}

/**
 * Retail MAC, ISO/IEC 9797-1 Algorithm 3 (EMV Book 2 A1.2.1): pad the
 * data with 0x80 then 0x00s up to a whole number of 8-byte blocks, run
 * single DES (first 8 bytes of the key) chained through every block
 * but the last, and triple DES (the whole 16-byte key) on the last
 * block. Starts from 8 zero bytes.
 */
fun emvRetailMac(key16: ByteArray, data: ByteArray): ByteArray? {
    // Size check first — the crypto below assumes it.
    if (key16.size != 16 && key16.size != 24) return null
    return try {
        val padded = data.toMutableList()
        padded += 0x80.toByte()
        while (padded.size % 8 != 0) padded += 0x00.toByte()
        val bytes = padded.toByteArray()
        val blockCount = bytes.size / 8
        val des = Cipher.getInstance("DES/ECB/NoPadding")
        des.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key16.copyOfRange(0, 8), "DES"))
        val des3 = Cipher.getInstance("DESede/ECB/NoPadding")
        des3.init(Cipher.ENCRYPT_MODE, SecretKeySpec(expandDesEdeKey(key16), "DESede"))
        var block = ByteArray(8)
        for (i in 0 until blockCount) {
            val chunk = bytes.copyOfRange(i * 8, i * 8 + 8)
            val mixed = ByteArray(8) { j -> (block[j].toInt() xor chunk[j].toInt()).toByte() }
            block = if (i < blockCount - 1) des.doFinal(mixed) else des3.doFinal(mixed)
        }
        block
    } catch (_: Exception) {
        null
    }
}

/**
 * Work out what the card's one-time code (tag 9F26) should be, from the
 * test master key: session key from the counter, then the Retail MAC
 * over the CDOL1 data + AIP + counter + Issuer Application Data.
 * Returns two tries: the full Issuer Application Data, and — only
 * when it is not empty — the same data without its leading length
 * byte. Bad crypto drops out of the list instead of crashing.
 */
fun arqcCandidates(
    masterKey: ByteArray,
    cdol1RelatedData: ByteArray,
    aip: ByteArray,
    atc: ByteArray,
    iad: ByteArray
): List<ByteArray> {
    val sessionKey = emvSessionKey(masterKey, atc) ?: return emptyList()
    val first = emvRetailMac(sessionKey, cdol1RelatedData + aip + atc + iad)
    val second = if (iad.isNotEmpty()) {
        emvRetailMac(sessionKey, cdol1RelatedData + aip + atc + iad.copyOfRange(1, iad.size))
    } else {
        null
    }
    return listOfNotNull(first, second)
}
