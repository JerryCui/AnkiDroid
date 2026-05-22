/*
 * Copyright (c) 2026
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 */
package com.ichi2.anki.ui.windows.reviewer.audiorecord

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicBoolean

class PcmAudioRecorder(
    private val context: Context,
) : Closeable {
    private val isRecording = AtomicBoolean(false)
    private var recordJob: Job? = null
    private var audioRecord: AudioRecord? = null

    var currentFile: File? = null
        private set

    @SuppressLint("MissingPermission")
    fun start() {
        if (!isRecording.compareAndSet(false, true)) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            isRecording.set(false)
            return
        }

        val bufferSize =
            maxOf(
                AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT),
                FRAME_SIZE_BYTES * 4,
            )
        val file = File.createTempFile("pronunciation_", ".pcm", context.cacheDir)
        currentFile = file
        val recorder =
            AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                CHANNEL_CONFIG,
                AUDIO_FORMAT,
                bufferSize,
            )
        audioRecord = recorder
        recorder.startRecording()
        recordJob =
            CoroutineScope(Dispatchers.IO).launch {
                FileOutputStream(file).use { output ->
                    val buffer = ByteArray(FRAME_SIZE_BYTES)
                    while (isRecording.get()) {
                        val read = recorder.read(buffer, 0, buffer.size)
                        if (read > 0) {
                            output.write(buffer, 0, read)
                        }
                    }
                }
            }
    }

    suspend fun stop(keepFile: Boolean = true): File? {
        if (!isRecording.compareAndSet(true, false)) return currentFile
        runCatching { audioRecord?.stop() }.onFailure { Timber.w(it, "Failed to stop PCM recorder") }
        recordJob?.join()
        audioRecord?.release()
        audioRecord = null
        recordJob = null
        val file = currentFile
        if (!keepFile) {
            file?.delete()
            currentFile = null
        }
        return file
    }

    override fun close() {
        if (isRecording.get()) {
            isRecording.set(false)
        }
        runCatching { audioRecord?.stop() }
        audioRecord?.release()
        audioRecord = null
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val FRAME_SIZE_BYTES = 1280
        private const val CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO
        private const val AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT
    }
}
