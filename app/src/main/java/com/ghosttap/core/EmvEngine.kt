package com.ghosttap.core

import android.nfc.Tag
import android.nfc.tech.IsoDep
import java.util.Calendar
import java.util.Locale
import kotlin.random.Random

/**
 * The result of reading a payment card: whether it worked, and the
 * plain-words report to show. The app judges success or failure from
 * these two fields alone.
 */
data class EmvOutcome(
    val success: Boolean,
    val report: String
)

/** Start of every demo-transport mismatch message (file scope so the nested transport can use it). */
private const val DEMO_MISMATCH_PREFIX = "The demo card did not answer as recorded"

/**
 * The payment-card engine — ONE flow, two inputs.
 *
 * The same steps run on a real tapped card and on the built-in demo
 * test card: ask what payment apps the card has, open the app, send
 * the payment details, read the card details, check the one-time
 * code, and build the message a terminal would send to the bank.
 * The demo test data is a test fixture proving the engine — same
 * code path, same checks, just a different input (tapped card vs
 * built-in test data). There is no separate demo replay.
 *
 * Nothing here ever sends anything anywhere, charges anything, or
 * saves a card number. The 0200 message is built and never sent.
 * The chip does not carry the CVV, and this engine never asks for it.
 */
object EmvEngine {

    /** One command in, one full answer out (answer ends with SW1/SW2). */
    private interface EmvTransport {
        fun transceive(command: ByteArray): ByteArray
    }

    /** Live transport: the tapped card, over IsoDep. */
    private class LiveIsoDepTransport(private val isoDep: IsoDep) : EmvTransport {
        override fun transceive(command: ByteArray): ByteArray = isoDep.transceive(command)
    }

    /**
     * Recorded transport: the demo test card. Hands back the recorded
     * answer for each recorded command, in order. Anything else is an
     * honest failure — the engine sent a command the recorded tap
     * does not have next, so the demo cannot prove that step.
     */
    private class RecordedTransport(
        private val exchanges: List<Pair<ByteArray, ByteArray>>
    ) : EmvTransport {
        private var index = 0

        override fun transceive(command: ByteArray): ByteArray {
            if (index >= exchanges.size) {
                throw IllegalStateException(
                    "$DEMO_MISMATCH_PREFIX — the engine asked for one more command than the recorded tap has."
                )
            }
            val (expectedRequest, recordedResponse) = exchanges[index]
            if (!command.contentEquals(expectedRequest)) {
                throw IllegalStateException(
                    "$DEMO_MISMATCH_PREFIX — the engine sent a command the recorded tap does not have next."
                )
            }
            index++
            return recordedResponse
        }
    }

    /** What the terminal tells the card: amounts, codes, dates, random. */
    private data class EmvTerminalProfile(
        val amountHex6Bytes: ByteArray,
        val currencyHex: ByteArray,
        val countryHex: ByteArray,
        val dateHexYYMMDD: ByteArray,
        val typeHex: ByteArray,
        val unpredictableHex: ByteArray,
        val ttqHex: ByteArray
    )

    /** What GET PROCESSING OPTIONS gave back. */
    private data class GpoResult(
        val aip: ByteArray?,
        val afl: ByteArray?,
        val track2: ByteArray?,
        val iad: ByteArray?,
        val arqc: ByteArray?,
        val cid: ByteArray?,
        val atc: ByteArray?
    )

    private const val NO_PAYMENT_APP =
        "This card doesn't offer a payment application — it's not a payment card."
    private const val SELECT_AID_FAILED = "The card wouldn't open its payment application."
    private const val GPO_FAILED = "The card wouldn't answer the payment details request."
    private const val NO_CARD_DETAILS = "The card opened but gave no card details to read."
    private const val MOVED_AWAY =
        "The card moved away — hold it against the back of the phone and try again."
    private const val NOT_A_PAYMENT_CARD =
        "This card doesn't talk the payment-card way — try a payment card."
    private const val DEMO_CODE_CHECK_FAILED =
        "The code check did not match — the demo card is not working right."

    private const val HONEST_BLOCK =
        "Only the bank can fully prove this code. What I can tell you: the card answered every step right, and its counter moves each tap, which a copied card could not fake."

    // TEST key from the simulator's setup — test data only, cannot approve a real payment.
    private val TEST_MASTER_KEY = "0123456789ABCDEFFEDCBA9876543210".hexToBytes() ?: byteArrayOf()

    /** Terminal-written CVM Results: no cardholder check needed, done. */
    private val CVM_RESULTS = byteArrayOf(0x1F, 0x03, 0x02)

    /**
     * Last tap, kept in memory only, never written anywhere: the card
     * number of the last good read and its counter. A real card's
     * counter goes up by one each tap, so comparing the two taps is
     * the one liveness check the phone can honestly do.
     */
    private var lastPan: String? = null
    private var lastAtc: Int? = null

    /**
     * Read a real payment card that is held against the phone.
     * Connects once, runs the flow, closes the card in all cases.
     */
    fun readLiveCard(tag: Tag): EmvOutcome {
        val isoDep = IsoDep.get(tag)
            ?: return EmvOutcome(false, NOT_A_PAYMENT_CARD)
        return try {
            isoDep.connect()
            isoDep.setTimeout(3000)
            runFlow(LiveIsoDepTransport(isoDep), liveProfile(), isDemo = false)
        } catch (_: Exception) {
            EmvOutcome(false, MOVED_AWAY)
        } finally {
            try {
                isoDep.close()
            } catch (_: Exception) {
                // Already done with the card; nothing left to report.
            }
        }
    }

    /** Run the same flow on the built-in demo test card. */
    fun runDemoCard(): EmvOutcome {
        return runFlow(RecordedTransport(EmvDemoCard.exchanges), demoProfile(), isDemo = true)
    }

    /** Live terminal: zero amount (a read charges zero), today, fresh random. */
    private fun liveProfile(): EmvTerminalProfile {
        val now = Calendar.getInstance()
        val dateHex = java.lang.String.format(
            Locale.US, "%02d%02d%02d",
            now.get(Calendar.YEAR) % 100,
            now.get(Calendar.MONTH) + 1,
            now.get(Calendar.DAY_OF_MONTH)
        ).hexToBytes() ?: byteArrayOf()
        return EmvTerminalProfile(
            amountHex6Bytes = ByteArray(6),
            currencyHex = "0840".hexToBytes() ?: byteArrayOf(),
            countryHex = "0840".hexToBytes() ?: byteArrayOf(),
            dateHexYYMMDD = dateHex,
            typeHex = byteArrayOf(0x00),
            unpredictableHex = Random.nextBytes(4),
            ttqHex = "36004000".hexToBytes() ?: byteArrayOf()
        )
    }

    /** Demo terminal: the exact values the recorded tap was made with. */
    private fun demoProfile(): EmvTerminalProfile {
        return EmvTerminalProfile(
            amountHex6Bytes = EmvDemoCard.AMOUNT_HEX.hexToBytes() ?: byteArrayOf(),
            currencyHex = EmvDemoCard.CURRENCY_HEX.hexToBytes() ?: byteArrayOf(),
            countryHex = EmvDemoCard.COUNTRY_HEX.hexToBytes() ?: byteArrayOf(),
            dateHexYYMMDD = EmvDemoCard.DATE_HEX.hexToBytes() ?: byteArrayOf(),
            typeHex = EmvDemoCard.TYPE_HEX.hexToBytes() ?: byteArrayOf(),
            unpredictableHex = EmvDemoCard.UNPREDICTABLE_HEX.hexToBytes() ?: byteArrayOf(),
            ttqHex = EmvDemoCard.TTQ_HEX.hexToBytes() ?: byteArrayOf()
        )
    }

    /** Bytes as compact uppercase hex with no spaces, for example "9F26". */
    private fun ByteArray.toCompactHex(): String = joinToString("") { "%02X".format(it) }

    /** Printable ASCII from bytes (other bytes dropped), trimmed, or null. */
    private fun printableAscii(bytes: ByteArray): String? {
        val sb = StringBuilder()
        for (b in bytes) {
            val v = b.toInt() and 0xFF
            if (v in 32..126) sb.append(v.toChar())
        }
        val text = sb.toString().trim()
        return text.ifEmpty { null }
    }

    /** Card number grouped in fours, for example "1234 5600 1234 5608". */
    private fun groupPan(pan: String): String = pan.chunked(4).joinToString(" ")

    /**
     * Send one command and handle the card's two "send it again"
     * answers: 61 means more answer bytes are waiting (GET RESPONSE,
     * up to 3 times), 6C means the card wants a different reply
     * length (resend with Le = SW2, up to 2 times). Returns the final
     * full answer, data plus SW1/SW2. Transport trouble throws, and
     * the flow turns it into plain words.
     */
    private fun sendApdu(transport: EmvTransport, command: ByteArray): ByteArray {
        var current = command
        var getResponseTries = 0
        var wrongLengthTries = 0
        while (true) {
            val response = transport.transceive(current)
            if (response.size < 2) return response
            val sw1 = response[response.size - 2].toInt() and 0xFF
            val sw2 = response[response.size - 1].toInt() and 0xFF
            if (sw1 == 0x61 && getResponseTries < 3) {
                getResponseTries++
                current = byteArrayOf(0x00, 0xC0.toByte(), 0x00, 0x00, sw2.toByte())
                continue
            }
            if (sw1 == 0x6C && wrongLengthTries < 2) {
                wrongLengthTries++
                current = command.copyOf().also { it[it.size - 1] = sw2.toByte() }
                continue
            }
            return response
        }
    }

    /** Data bytes of a 90 00 answer, or null for any other status. */
    private fun responseData(response: ByteArray): ByteArray? {
        if (response.size < 2) return null
        val sw1 = response[response.size - 2].toInt() and 0xFF
        val sw2 = response[response.size - 1].toInt() and 0xFF
        return if (sw1 == 0x90 && sw2 == 0x00) {
            response.copyOfRange(0, response.size - 2)
        } else {
            null
        }
    }

    /** Pick the card's application: Visa first, then Mastercard, else first found. */
    private fun pickApplication(applications: List<EmvApplication>): EmvApplication {
        return applications.firstOrNull { it.aid.startsWith("A000000003") }
            ?: applications.firstOrNull { it.aid.startsWith("A000000004") }
            ?: applications.first()
    }

    /** Brand name from the application's RID (first 5 bytes of the AID). */
    private fun brandForAid(aid: String): String = when {
        aid.startsWith("A000000003") -> "Visa"
        aid.startsWith("A000000004") -> "Mastercard"
        aid.startsWith("A000000025") -> "American Express"
        aid.startsWith("A000000065") -> "JCB"
        aid.startsWith("A000000152") -> "Discover"
        else -> aid
    }

    /**
     * Build the PDOL data the card asked for, from the terminal
     * profile. Any tag the profile has no value for gets zeroes of
     * the asked length. Also returns the CDOL1 data — the same bytes
     * minus the TTQ — which is what the one-time code is made over.
     */
    private fun buildPdolData(
        entries: List<Pair<String, Int>>,
        profile: EmvTerminalProfile
    ): Triple<ByteArray, ByteArray, Map<String, ByteArray>> {
        val terminalValues: Map<String, ByteArray> = mapOf(
            "9F66" to profile.ttqHex,
            "9F02" to profile.amountHex6Bytes,
            "9F03" to ByteArray(6),
            "9F1A" to profile.countryHex,
            "95" to ByteArray(5),
            "5F2A" to profile.currencyHex,
            "9A" to profile.dateHexYYMMDD,
            "9C" to profile.typeHex,
            "9F37" to profile.unpredictableHex
        )
        var data = byteArrayOf()
        var cdol1RelatedData = byteArrayOf()
        val values = mutableMapOf<String, ByteArray>()
        for ((tag, length) in entries) {
            val wanted = terminalValues[tag] ?: ByteArray(length)
            val value = when {
                wanted.size == length -> wanted
                wanted.size > length -> wanted.copyOfRange(0, length)
                else -> wanted + ByteArray(length - wanted.size)
            }
            data += value
            values[tag] = value
            if (tag != "9F66") cdol1RelatedData += value
        }
        return Triple(data, cdol1RelatedData, values)
    }

    /** Read a GET PROCESSING OPTIONS answer: template 77, or format 2 (tag 80). */
    private fun parseGpo(data: ByteArray): GpoResult? {
        val top = parseTlv(data)
        val template77 = top.firstOrNull { it.tag == "77" }?.value
        if (template77 != null) {
            val flat = flattenTlv(template77)
            return GpoResult(
                aip = flat["82"],
                afl = flat["94"],
                track2 = flat["57"],
                iad = flat["9F10"],
                arqc = flat["9F26"],
                cid = flat["9F27"],
                atc = flat["9F36"]
            )
        }
        val template80 = top.firstOrNull { it.tag == "80" }?.value
        if (template80 != null && template80.size >= 2) {
            return GpoResult(
                aip = template80.copyOfRange(0, 2),
                afl = template80.copyOfRange(2, template80.size),
                track2 = null,
                iad = null,
                arqc = null,
                cid = null,
                atc = null
            )
        }
        return null
    }

    /**
     * Order the AFL entries for reading. Entries whose records are
     * the static data to be authenticated (their 4th byte is not
     * zero) go first — they hold the card number, expiry and data
     * lists — then the rest, in AFL order. Reading stops as soon as
     * the number, expiry and CDOL1 list are all in hand, so a card
     * whose other entries hold only certificates is read with the
     * fewest commands. If those entries do not hold everything, the
     * rest are read too.
     *
     * This matters for the demo card: its AFL lists SFI 2 records
     * 1–3 (certificates) before SFI 3 records 1–2 (the card data),
     * and the recorded tap only reads the SFI 3 records.
     */
    private fun orderAflRefs(afl: ByteArray?, refs: List<EmvRecordRef>): List<EmvRecordRef> {
        if (afl == null) return refs
        return refs.mapIndexed { index, ref ->
            val offlineCount =
                if (index * 4 + 3 < afl.size) afl[index * 4 + 3].toInt() and 0xFF else 0
            Triple(ref, offlineCount, index)
        }.sortedWith(
            compareByDescending<Triple<EmvRecordRef, Int, Int>> { it.second }.thenBy { it.third }
        ).map { it.first }
    }

    /** Card number from track 2 (the digits before the 'D'), or null. */
    private fun track2Pan(track2: ByteArray): String? {
        val nibbles = track2.toCompactHex()
        val separator = nibbles.indexOf('D')
        if (separator < 0) return null
        return nibbles.substring(0, separator).trimEnd('F').ifEmpty { null }
    }

    /** Expiry YYMM from track 2 (the 4 digits after the 'D'), or null. */
    private fun track2ExpiryYyMm(track2: ByteArray): String? {
        val nibbles = track2.toCompactHex()
        val separator = nibbles.indexOf('D')
        if (separator < 0 || nibbles.length < separator + 5) return null
        return nibbles.substring(separator + 1, separator + 5)
    }

    /**
     * Track 2 as the DE35 field: PAN=expiry+service+discretionary.
     * The discretionary part is the 4 digits after the service code,
     * trailing F padding dropped — the same construction the lab's
     * simulation uses, so the demo 0200 matches it byte for byte.
     */
    private fun track2De35(track2: ByteArray): String? {
        val nibbles = track2.toCompactHex()
        val separator = nibbles.indexOf('D')
        if (separator < 0 || nibbles.length < separator + 8) return null
        val pan = nibbles.substring(0, separator).trimEnd('F')
        val expiry = nibbles.substring(separator + 1, separator + 5)
        val service = nibbles.substring(separator + 5, separator + 8)
        val discretionaryEnd = minOf(separator + 12, nibbles.length)
        val discretionary = nibbles.substring(separator + 8, discretionaryEnd).trimEnd('F')
        return "$pan=$expiry$service$discretionary"
    }

    /** One TLV parcel: tag bytes, one length byte, value (values here are short). */
    private fun tlvBytes(tagHex: String, value: ByteArray): ByteArray {
        val tagBytes = tagHex.hexToBytes() ?: byteArrayOf()
        if (tagBytes.isEmpty()) return byteArrayOf()
        val lengthBytes = if (value.size < 128) {
            byteArrayOf(value.size.toByte())
        } else {
            byteArrayOf(0x81.toByte(), value.size.toByte())
        }
        return tagBytes + lengthBytes + value
    }

    /**
     * DE55, the chip data for the 0200: the 14 tags in order, leaving
     * out any the card did not send. 9F34 (CVM Results) is written by
     * the terminal, so it is always there.
     */
    private fun buildDe55(entries: List<Pair<String, ByteArray?>>): ByteArray {
        var out = byteArrayOf()
        for ((tag, value) in entries) {
            if (value == null) continue
            out += tlvBytes(tag, value)
        }
        return out
    }

    /**
     * Build the ISO 8583 0200 authorisation request — MTI as 4 ASCII
     * bytes, 8-byte bitmap, then the fields in number order. Pure
     * construction: the message is built and never sent anywhere.
     * DE2 LLVAR, DE3/4/7/11/14/22/49 fixed numeric, DE35 LLVAR,
     * DE41/42 fixed alphanumeric space-padded, DE55 LLLVAR binary.
     */
    private fun build0200(
        pan: String,
        amountHex12: String,
        de7: String,
        expiryYyMm: String,
        track2De35: String?,
        currency3: String,
        de55: ByteArray
    ): ByteArray {
        val fields = mutableMapOf<Int, ByteArray>()
        fun ascii(text: String): ByteArray = text.toByteArray(Charsets.US_ASCII)
        fun llvarAscii(value: String): ByteArray =
            ascii(java.lang.String.format(Locale.US, "%02d", value.length) + value)
        fields[2] = llvarAscii(pan)
        fields[3] = ascii("000000")
        fields[4] = ascii(amountHex12)
        fields[7] = ascii(de7)
        fields[11] = ascii("000001")
        fields[14] = ascii(expiryYyMm)
        fields[22] = ascii("071")
        if (track2De35 != null) fields[35] = llvarAscii(track2De35)
        fields[41] = ascii("TEST0001".padEnd(8, ' '))
        fields[42] = ascii("TESTMERCHANT001".padEnd(15, ' '))
        fields[49] = ascii(currency3)
        fields[55] = ascii(java.lang.String.format(Locale.US, "%03d", de55.size)) + de55
        val bitmap = ByteArray(8)
        for (field in fields.keys) {
            val bit = field - 1
            bitmap[bit / 8] = (bitmap[bit / 8].toInt() or (0x80 shr (bit % 8))).toByte()
        }
        var out = ascii("0200") + bitmap
        for (field in fields.keys.sorted()) out += fields.getValue(field)
        return out
    }

    /**
     * The single flow, run by both entry points. Never throws: a bad
     * card answer comes back as a plain-words failure outcome.
     */
    private fun runFlow(
        transport: EmvTransport,
        profile: EmvTerminalProfile,
        isDemo: Boolean
    ): EmvOutcome {
        try {
            // a. SELECT PPSE — ask the card what payment apps it has.
            val ppseCommand = byteArrayOf(0x00, 0xA4.toByte(), 0x04, 0x00, 0x0E) +
                "2PAY.SYS.DDF01".toByteArray(Charsets.US_ASCII) + byteArrayOf(0x00)
            val ppseData = responseData(sendApdu(transport, ppseCommand))
                ?: return EmvOutcome(false, NO_PAYMENT_APP)
            val applications = parsePpseApplications(ppseData)
            if (applications.isEmpty()) return EmvOutcome(false, NO_PAYMENT_APP)
            val app = pickApplication(applications)
            val aidBytes = app.aid.hexToBytes() ?: byteArrayOf()
            if (aidBytes.isEmpty()) return EmvOutcome(false, NO_PAYMENT_APP)

            // b. SELECT AID — open the application, learn its PDOL.
            val selectAidCommand = byteArrayOf(
                0x00, 0xA4.toByte(), 0x04, 0x00, aidBytes.size.toByte()
            ) + aidBytes + byteArrayOf(0x00)
            val aidData = responseData(sendApdu(transport, selectAidCommand))
                ?: return EmvOutcome(false, SELECT_AID_FAILED)
            val pdolEntries = parsePdol(flattenTlv(aidData)["9F38"] ?: byteArrayOf())

            // c. GET PROCESSING OPTIONS — send the payment details.
            val (pdolData, cdol1RelatedData, pdolValues) = buildPdolData(pdolEntries, profile)
            val gpoCommand = byteArrayOf(
                0x80.toByte(), 0xA8.toByte(), 0x00, 0x00,
                (pdolData.size + 2).toByte(), 0x83.toByte(), pdolData.size.toByte()
            ) + pdolData + byteArrayOf(0x00)
            val gpoData = responseData(sendApdu(transport, gpoCommand))
                ?: return EmvOutcome(false, GPO_FAILED)
            val gpo = parseGpo(gpoData)
                ?: return EmvOutcome(false, GPO_FAILED)

            // d. READ RECORDs per the AFL — the card number, expiry,
            // name, and the CDOL1 list. A record the card does not
            // have is skipped; the flow only fails if nothing useful
            // comes back at all.
            var pan: String? = null
            var expiryYyMm: String? = null
            var cardholderName: String? = null
            var cdol1List: List<Pair<String, Int>>? = null
            var recordTrack2: ByteArray? = null
            val aflRefs = parseAfl(gpo.afl ?: byteArrayOf())
            for (ref in orderAflRefs(gpo.afl, aflRefs)) {
                if (pan != null && expiryYyMm != null && cdol1List != null) break
                if (ref.first < 1 || ref.last < ref.first) continue
                for (record in ref.first..ref.last) {
                    if (pan != null && expiryYyMm != null && cdol1List != null) break
                    val readCommand = byteArrayOf(
                        0x00, 0xB2.toByte(), record.toByte(),
                        ((ref.sfi shl 3) or 0x04).toByte(), 0x00
                    )
                    val recordData = responseData(sendApdu(transport, readCommand)) ?: continue
                    val tags = flattenTlv(recordData)
                    tags["5A"]?.let { pan = it.toCompactHex().trimEnd('F') }
                    tags["5F24"]?.let { expiryYyMm = it.toCompactHex().take(4) }
                    tags["5F20"]?.let { cardholderName = printableAscii(it) }
                    tags["8C"]?.let { cdol1List = parsePdol(it) }
                    tags["57"]?.let { if (recordTrack2 == null) recordTrack2 = it }
                }
            }

            val track2 = gpo.track2 ?: recordTrack2
            val panDigits = pan ?: track2?.let { track2Pan(it) }
            val expiry = expiryYyMm ?: track2?.let { track2ExpiryYyMm(it) }
            if (panDigits.isNullOrEmpty() || expiry.isNullOrEmpty() || expiry.length != 4) {
                return EmvOutcome(false, NO_CARD_DETAILS)
            }

            // CDOL1 values: slice the CDOL1 data by the card's own
            // CDOL1 list when the lengths line up exactly, else fall
            // back to the values the terminal sent. The list is copied
            // to a fresh local first: it is a captured local var, so
            // the compiler cannot smart-cast it directly.
            val cdol1 = cdol1List
            val cdol1Values: Map<String, ByteArray> =
                if (cdol1 != null &&
                    cdol1.sumOf { it.second } == cdol1RelatedData.size
                ) {
                    val sliced = mutableMapOf<String, ByteArray>()
                    var pos = 0
                    for ((tag, length) in cdol1) {
                        sliced[tag] = cdol1RelatedData.copyOfRange(pos, pos + length)
                        pos += length
                    }
                    sliced
                } else {
                    pdolValues
                }

            val atcBytes = gpo.atc
            val atcInt = atcBytes?.fold(0) { acc, b -> (acc shl 8) or (b.toInt() and 0xFF) }
            val arqc = gpo.arqc

            // f. Cryptogram check. Demo only: recompute from the test
            // master key and match byte for byte. Live: no recompute —
            // only the bank holds the key — just show the code.
            var codeMatches: Boolean? = null
            if (isDemo) {
                val aip = gpo.aip
                val iad = gpo.iad
                codeMatches = if (arqc != null && aip != null && atcBytes != null && iad != null) {
                    arqcCandidates(
                        TEST_MASTER_KEY, cdol1RelatedData, aip, atcBytes, iad
                    ).any { it.contentEquals(arqc) }
                } else {
                    false
                }
            }

            // g. Build the 0200 (pure construction, never sent).
            val de55 = buildDe55(
                listOf(
                    "9F26" to arqc,
                    "9F27" to gpo.cid,
                    "9F36" to atcBytes,
                    "9F10" to gpo.iad,
                    "9F37" to cdol1Values["9F37"],
                    "9F02" to cdol1Values["9F02"],
                    "9F03" to cdol1Values["9F03"],
                    "9F1A" to cdol1Values["9F1A"],
                    "95" to cdol1Values["95"],
                    "5F2A" to cdol1Values["5F2A"],
                    "9A" to cdol1Values["9A"],
                    "9C" to cdol1Values["9C"],
                    "82" to gpo.aip,
                    "9F34" to CVM_RESULTS
                )
            )
            val de7 = if (isDemo) {
                EmvDemoCard.TEST_0210_DE7
            } else {
                val now = Calendar.getInstance()
                java.lang.String.format(
                    Locale.US, "%02d%02d%02d%02d%02d",
                    now.get(Calendar.MONTH) + 1,
                    now.get(Calendar.DAY_OF_MONTH),
                    now.get(Calendar.HOUR_OF_DAY),
                    now.get(Calendar.MINUTE),
                    now.get(Calendar.SECOND)
                )
            }
            val currency3 = profile.currencyHex.toCompactHex().trimStart('0')
                .ifEmpty { "0" }.padStart(3, '0')
            val message0200 = build0200(
                pan = panDigits,
                amountHex12 = profile.amountHex6Bytes.toCompactHex(),
                de7 = de7,
                expiryYyMm = expiry,
                track2De35 = track2?.let { track2De35(it) },
                currency3 = currency3,
                de55 = de55
            )

            if (isDemo) return demoReport(
                panDigits = panDigits,
                expiryYyMm = expiry,
                arqc = arqc,
                atcInt = atcInt,
                codeMatches = codeMatches == true,
                message0200Hex = message0200.toCompactHex()
            )

            // Live report.
            val lines = mutableListOf<String>()
            lines += "Payment card: ${brandForAid(app.aid)}"
            lines += "Applications on this card: " + applications.joinToString(", ") { found ->
                if (found.label != null) "${found.label} (${found.aid})" else found.aid
            }
            lines += "Card number: ${groupPan(panDigits)}"
            lines += "Good until: ${expiry.substring(2, 4)}/${expiry.substring(0, 2)}"
            if (cardholderName != null) lines += "Name on the card: $cardholderName"
            lines += "This card answered every step: payment app found, card details read."
            if (arqc != null && arqc.size == 8) {
                lines += "One-time code the card made: ${arqc.toHexString()}"
                lines += "That code can never pay again later — it only fits this one tap, this amount, this counter, this random number."
            } else {
                lines += "The card did not send a one-time code to check."
            }
            if (atcInt != null) {
                lines += "Counter: $atcInt"
                // The counter is a mutable property, so it is copied
                // to a local first — the compiler cannot smart-cast it.
                val prevAtc = lastAtc
                lines += if (lastPan == panDigits && prevAtc != null && atcInt == prevAtc + 1) {
                    "The counter went up by one since the last tap — exactly as a real card should."
                } else {
                    "Tap this card again — the counter should go up by one."
                }
            }
            lines += HONEST_BLOCK
            lines += "Built the message a terminal would send to the bank — using test terminal details, and it was not sent anywhere."
            lines += "Reading a card costs zero — this only reads what the card says."

            // Remember this tap (in memory only) for the next tap's check.
            lastPan = panDigits
            if (atcInt != null) lastAtc = atcInt
            return EmvOutcome(true, lines.joinToString("\n"))
        } catch (e: Exception) {
            val message = e.message
            return if (isDemo && message != null && message.startsWith(DEMO_MISMATCH_PREFIX)) {
                EmvOutcome(false, message)
            } else {
                EmvOutcome(false, MOVED_AWAY)
            }
        }
    }

    /**
     * The demo report, in order. A failed code check is a failure outcome.
     * The label and brand lines name the test card as the lab does
     * ("VISA ELECTRON", Visa): the raw label bytes in the recorded tap
     * actually spell "VESA ELECTRON" — a typo in the simulator's own
     * test-card setup, carried into tag 50 and tag 9F12 — while the
     * lab's simulation names it VISA ELECTRON. The number, expiry,
     * one-time code and counter below are the parsed values, not
     * fixed strings, and they match the recorded tap exactly.
     */
    private fun demoReport(
        panDigits: String,
        expiryYyMm: String,
        arqc: ByteArray?,
        atcInt: Int?,
        codeMatches: Boolean,
        message0200Hex: String
    ): EmvOutcome {
        val lines = mutableListOf<String>()
        lines += "DEMO CARD — test card, fake money. This card can never pay anywhere."
        lines += "Step 1 — Asked the card what payment apps it has. It answered: VISA ELECTRON."
        lines += "Step 2 — Opened the Visa app. It asked for the payment details."
        lines += "Step 3 — Sent the payment details. The card made its one-time code."
        lines += "Step 4 — Read the card details: number and expiry."
        lines += "Card number: ${groupPan(panDigits)} (test number, made up)"
        lines += "Good until: ${expiryYyMm.substring(2, 4)}/${expiryYyMm.substring(0, 2)}"
        lines += "Brand: Visa (test) — VISA ELECTRON"
        if (arqc != null) lines += "One-time code the card made: ${arqc.toHexString()}"
        if (atcInt != null) lines += "Counter: $atcInt"
        if (!codeMatches) {
            lines += DEMO_CODE_CHECK_FAILED
            return EmvOutcome(false, lines.joinToString("\n"))
        }
        lines += "Code checks out — matches byte for byte."
        if (message0200Hex.equals(EmvDemoCard.EXPECTED_0200_HEX, ignoreCase = true)) {
            lines += "The bank message built from this tap matches the recorded one byte for byte."
        }
        lines += "Test bank answer: approved — authorisation code ${EmvDemoCard.TEST_0210_AUTH_CODE}. The test host checked the test card only — it is not a real bank."
        lines += "This demo uses a made-up test card. Real cards work the same way up to the code check — only the bank can fully prove a real card's code."
        return EmvOutcome(true, lines.joinToString("\n"))
    }
}
