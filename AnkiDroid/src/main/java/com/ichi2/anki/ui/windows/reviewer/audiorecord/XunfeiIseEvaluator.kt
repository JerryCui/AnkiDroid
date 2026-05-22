/*
 * Copyright (c) 2026
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 */
package com.ichi2.anki.ui.windows.reviewer.audiorecord

import android.util.Base64
import android.util.Log
import com.ichi2.anki.BuildConfig
import kotlinx.coroutines.CompletableDeferred
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONObject
import org.w3c.dom.Element
import timber.log.Timber
import java.io.ByteArrayInputStream
import java.io.File
import java.net.URLEncoder
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import javax.xml.parsers.DocumentBuilderFactory

data class XunfeiIseWordResult(
    val text: String,
    val score: Float?,
)

data class XunfeiIseResult(
    val totalScore: Float,
    val accuracyScore: Float?,
    val fluencyScore: Float?,
    val integrityScore: Float?,
    val standardScore: Float?,
    val words: List<XunfeiIseWordResult>,
    val rawXml: String,
)

class XunfeiIseEvaluator {
    private val client =
        OkHttpClient
            .Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()

    suspend fun evaluate(
        pcmFile: File,
        targetText: String,
    ): XunfeiIseResult {
        val deferred = CompletableDeferred<XunfeiIseResult>()
        val request = Request.Builder().url(buildAuthUrl()).build()
        client.newWebSocket(
            request,
            object : WebSocketListener() {
                override fun onOpen(
                    webSocket: WebSocket,
                    response: Response,
                ) {
                    Thread {
                        runCatching {
                            sendAssessmentRequest(webSocket, pcmFile, targetText)
                        }.onFailure {
                            if (deferred.completeExceptionally(it)) {
                                webSocket.close(1000, "send failed")
                            }
                        }
                    }.start()
                }

                override fun onMessage(
                    webSocket: WebSocket,
                    text: String,
                ) {
                    Log.i(TAG, "xunfei response: $text")
                    val json = JSONObject(text)
                    val code = json.optInt("code", -1)
                    if (code != 0) {
                        deferred.completeExceptionally(IllegalStateException(json.optString("message", "Xunfei ISE error $code")))
                        webSocket.close(1000, "ise error")
                        return
                    }
                    val data = json.optJSONObject("data") ?: return
                    if (data.optInt("status") != 2) return
                    val encodedXml = data.optString("data")
                    val xml = String(Base64.decode(encodedXml, Base64.DEFAULT), Charsets.UTF_8)
                    deferred.complete(parseResult(xml))
                    webSocket.close(1000, "done")
                }

                override fun onFailure(
                    webSocket: WebSocket,
                    t: Throwable,
                    response: Response?,
                ) {
                    Timber.w(t, "Xunfei ISE websocket failed")
                    deferred.completeExceptionally(t)
                }

                override fun onClosed(
                    webSocket: WebSocket,
                    code: Int,
                    reason: String,
                ) {
                    if (!deferred.isCompleted) {
                        deferred.completeExceptionally(IllegalStateException("Xunfei ISE websocket closed: $code $reason"))
                    }
                }
            },
        )
        return deferred.await()
    }

    private fun sendAssessmentRequest(
        webSocket: WebSocket,
        pcmFile: File,
        targetText: String,
    ) {
        webSocket.send(buildStartPayload(targetText).toString())
        val bytes = pcmFile.readBytes()
        Log.i(TAG, "sending xunfei pcm: bytes=${bytes.size} target='$targetText'")
        Thread.sleep(80)
        var offset = 0
        var frameIndex = 0
        while (offset < bytes.size) {
            val size = minOf(PcmAudioRecorder.FRAME_SIZE_BYTES, bytes.size - offset)
            val frame = bytes.copyOfRange(offset, offset + size)
            val aus = if (offset == 0) 1 else 2
            webSocket.send(buildAudioPayload(frame, aus, 1).toString())
            offset += size
            frameIndex += 1
            Thread.sleep(40)
        }
        Log.i(TAG, "sent xunfei pcm frames: $frameIndex")
        webSocket.send(buildAudioPayload(ByteArray(0), 4, 2).toString())
    }

    private fun buildStartPayload(targetText: String): JSONObject =
        JSONObject()
            .put("common", JSONObject().put("app_id", APP_ID))
            .put(
                "business",
                buildBusinessPayload(targetText),
            ).put("data", JSONObject().put("status", 0))

    private fun buildBusinessPayload(targetText: String): JSONObject {
        val normalizedText = normalizePromptText(targetText)
        val isShortPhrase = normalizedText.split(Regex("\\s+")).size <= MAX_READ_WORD_COUNT
        val category = if (isShortPhrase) "read_word" else "read_sentence"
        val prompt = if (isShortPhrase) "[word]\n$normalizedText" else "[content]\n$normalizedText"
        return JSONObject()
            .put("sub", "ise")
            .put("ent", "en_vip")
            .put("category", category)
            .put("cmd", "ssb")
            .put("aue", "raw")
            .put("auf", "audio/L16;rate=16000")
            .put("rstcd", "utf8")
            .put("rst", "entirety")
            .put("ise_unite", "1")
            .put("plev", "0")
            .put("extra_ability", "multi_dimension;syll_phone_err_msg")
            .put("ttp_skip", true)
            .put("tte", "utf-8")
            .put("text", "\uFEFF$prompt")
    }

    private fun normalizePromptText(text: String): String =
        text
            .replace('’', '\'')
            .replace(Regex("[^A-Za-z0-9 .\\-'!?]+"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()

    private fun buildAudioPayload(
        bytes: ByteArray,
        aus: Int,
        status: Int,
    ): JSONObject =
        JSONObject()
            .put("business", JSONObject().put("cmd", "auw").put("aus", aus))
            .put(
                "data",
                JSONObject()
                    .put("status", status)
                    .put("data", Base64.encodeToString(bytes, Base64.NO_WRAP)),
            )

    private fun buildAuthUrl(): String {
        val date =
            SimpleDateFormat("EEE, dd MMM yyyy HH:mm:ss z", Locale.US)
                .apply {
                    timeZone = TimeZone.getTimeZone("GMT")
                }.format(Date())
        val signatureOrigin = "host: $HOST\ndate: $date\nGET $PATH HTTP/1.1"
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(API_SECRET.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val signature = Base64.encodeToString(mac.doFinal(signatureOrigin.toByteArray(Charsets.UTF_8)), Base64.NO_WRAP)
        val authorizationOrigin =
            "api_key=\"$API_KEY\", algorithm=\"hmac-sha256\", headers=\"host date request-line\", signature=\"$signature\""
        val authorization = Base64.encodeToString(authorizationOrigin.toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
        return "wss://$HOST$PATH" +
            "?authorization=${urlEncode(authorization)}" +
            "&date=${urlEncode(date)}" +
            "&host=${urlEncode(HOST)}"
    }

    private fun parseResult(xml: String): XunfeiIseResult {
        val document =
            DocumentBuilderFactory
                .newInstance()
                .newDocumentBuilder()
                .parse(ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)))
        val scoredNode =
            (0 until document.getElementsByTagName("*").length)
                .map { document.getElementsByTagName("*").item(it) as Element }
                .firstOrNull { it.hasAttribute("total_score") }
                ?: error("No score in Xunfei ISE result")
        val words = document.getElementsByTagName("word")
        val wordResults =
            (0 until words.length)
                .map { words.item(it) as Element }
                .mapNotNull { word ->
                    val text = word.getAttribute("content").ifBlank { return@mapNotNull null }
                    XunfeiIseWordResult(text = text, score = word.scoreAttribute())
                }
        return XunfeiIseResult(
            totalScore = scoredNode.floatAttribute("total_score") ?: 0f,
            accuracyScore = scoredNode.floatAttribute("accuracy_score"),
            fluencyScore = scoredNode.floatAttribute("fluency_score"),
            integrityScore = scoredNode.floatAttribute("integrity_score"),
            standardScore = scoredNode.floatAttribute("standard_score"),
            words = wordResults,
            rawXml = xml,
        )
    }

    private fun Element.scoreAttribute(): Float? =
        floatAttribute("total_score")
            ?: floatAttribute("phone_score")
            ?: floatAttribute("accuracy_score")

    private fun Element.floatAttribute(name: String): Float? = getAttribute(name).takeIf { it.isNotBlank() }?.toFloatOrNull()

    private fun urlEncode(value: String): String = URLEncoder.encode(value, "UTF-8")

    companion object {
        private const val TAG = "AnkiPronunciation"
        private const val HOST = "ise-api.xfyun.cn"
        private const val PATH = "/v2/open-ise"
        private val APP_ID = BuildConfig.XUNFEI_APP_ID
        private val API_KEY = BuildConfig.XUNFEI_API_KEY
        private val API_SECRET = BuildConfig.XUNFEI_API_SECRET
        private const val MAX_READ_WORD_COUNT = 5
    }
}
