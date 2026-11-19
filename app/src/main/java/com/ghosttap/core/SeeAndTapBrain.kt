package com.ghosttap.core

import android.app.Application
import android.graphics.Bitmap
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

/**
 * PIECE 2 (2026-11-18) — screen-see-and-tap: ask the thinking engine
 * where to tap on a screenshot.
 *
 * This is the SAME brain slot Ghost Tap already has (the Ghost key in
 * Investigations → Thinking Engine, OpenAI-compatible chat
 * completions, model gpt-4o-mini — the one model in the engine that
 * can look at a picture). Nothing new is configured here: no key
 * means no answer, out of credit means no answer, and both are said
 * in plain words by the caller instead of a tap being faked.
 *
 * The screenshot is shrunk to at most 768 pixels on a side and sent
 * as a JPEG — small enough to send quickly, big enough to read.
 * Coordinates come back as 0–1000 across the picture, so the caller
 * scales them to the phone's real screen size.
 */
sealed class TapPointResult {
    /** The brain pointed at a spot: x/y are 0–1000 across the picture. */
    data class Found(val x: Int, val y: Int) : TapPointResult()

    /** The brain answered but couldn't find what was asked for. */
    object NotFound : TapPointResult()

    /** No key saved — the brain isn't set up at all. */
    object NotConfigured : TapPointResult()

    /** The brain couldn't be reached, or its answer was unusable. Plain words in [words]. */
    data class Failed(val words: String) : TapPointResult()
}

object SeeAndTapBrain {

    /**
     * Asks the brain where to tap for [goal] on [screenshot].
     * Never throws — every failure comes back as a [TapPointResult].
     */
    suspend fun askWhereToTap(
        app: Application,
        goal: String,
        screenshot: Bitmap
    ): TapPointResult {
        val endpoint = IntelSettings.getEndpoint(app).trim()
            .ifBlank { RemoteLlmReasoner.DEFAULT_ENDPOINT }
        val apiKey = IntelSettings.getApiKey(app).trim()
        if (apiKey.isBlank()) return TapPointResult.NotConfigured
        val image = runCatching { encodeSmallJpeg(screenshot) }.getOrNull()
            ?: return TapPointResult.Failed("The screenshot couldn't be read — try again.")
        var conn: HttpURLConnection? = null
        return try {
            val body = buildString {
                append("{\"model\":\"")
                append(RemoteLlmReasoner.MODEL)
                append("\",\"messages\":[")
                append("{\"role\":\"system\",\"content\":")
                append(jsonString("You look at a screenshot of an Android phone and say where to tap. Answer with ONLY this JSON, nothing else: {\"x\": <number 0-1000>, \"y\": <number 0-1000>}. x is across the picture, y is down it. The point must be the centre of the thing to tap. If you cannot find it, answer {\"x\": -1, \"y\": -1}."))
                append("},{\"role\":\"user\",\"content\":[")
                append("{\"type\":\"text\",\"text\":")
                append(jsonString("Tap: $goal"))
                append("},{\"type\":\"image_url\",\"image_url\":{\"url\":")
                append(jsonString("data:image/jpeg;base64,$image"))
                append("}}]}]}")
            }
            conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 15000
                readTimeout = 30000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                return when (code) {
                    401, 403 -> TapPointResult.Failed("The Ghost key was rejected — check it in Investigations.")
                    429 -> TapPointResult.Failed("The brain is out of credit right now — nothing was tapped.")
                    in 500..599 -> TapPointResult.Failed("The brain's service had a problem — nothing was tapped.")
                    else -> TapPointResult.Failed("The brain said no (code $code) — nothing was tapped.")
                }
            }
            val resp = conn.inputStream.bufferedReader().readText()
            val content = runCatching {
                JSONObject(resp).optJSONArray("choices")?.optJSONObject(0)
                    ?.optJSONObject("message")?.optString("content").orEmpty()
            }.getOrDefault("")
            parseTapPoint(content)
        } catch (e: java.net.SocketTimeoutException) {
            TapPointResult.Failed("The brain took too long to answer — nothing was tapped.")
        } catch (e: java.net.UnknownHostException) {
            TapPointResult.Failed("The phone couldn't reach the brain — check you're online.")
        } catch (e: Exception) {
            TapPointResult.Failed("Something went wrong asking the brain: ${e.message ?: "unknown problem"}")
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Reads {"x": N, "y": N} out of the brain's answer. */
    private fun parseTapPoint(content: String): TapPointResult {
        val x = Regex("\"x\"\\s*:\\s*(-?\\d+)").find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
        val y = Regex("\"y\"\\s*:\\s*(-?\\d+)").find(content)?.groupValues?.getOrNull(1)?.toIntOrNull()
        if (x == null || y == null) {
            return TapPointResult.Failed("The brain's answer couldn't be read — nothing was tapped.")
        }
        if (x < 0 || y < 0) return TapPointResult.NotFound
        return TapPointResult.Found(x.coerceIn(0, 1000), y.coerceIn(0, 1000))
    }

    /** Shrinks the screenshot to at most 768px on a side and JPEGs it to Base64. */
    private fun encodeSmallJpeg(bitmap: Bitmap): String {
        val maxSide = 768
        val scale = minOf(
            1f,
            maxSide.toFloat() / maxOf(bitmap.width, bitmap.height).toFloat()
        )
        val small = if (scale < 1f) {
            Bitmap.createScaledBitmap(
                bitmap,
                (bitmap.width * scale).toInt().coerceAtLeast(1),
                (bitmap.height * scale).toInt().coerceAtLeast(1),
                true
            )
        } else {
            bitmap
        }
        val out = ByteArrayOutputStream()
        small.compress(Bitmap.CompressFormat.JPEG, 70, out)
        if (small !== bitmap) small.recycle()
        return Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
    }

    /** Minimal JSON string escaping (quotes, backslashes, control characters). */
    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}
