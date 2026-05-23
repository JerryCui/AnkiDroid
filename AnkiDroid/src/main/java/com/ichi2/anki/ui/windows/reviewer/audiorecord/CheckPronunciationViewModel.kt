/*
 * Copyright (c) 2025 Brayan Oliveira <69634269+brayandso@users.noreply.github.com>
 *
 * This program is free software; you can redistribute it and/or modify it under
 * the terms of the GNU General Public License as published by the Free Software
 * Foundation; either version 3 of the License, or (at your option) any later
 * version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY
 * WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS FOR A
 * PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program. If not, see <http://www.gnu.org/licenses/>.
 */
package com.ichi2.anki.ui.windows.reviewer.audiorecord

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ichi2.anki.AnkiDroidApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

enum class PronunciationTokenState {
    MATCH,
    MISMATCH,
    MISSING,
    EXTRA,
    EMPTY,
}

data class PronunciationAlignedToken(
    val text: String,
    val state: PronunciationTokenState,
)

data class PronunciationAssessment(
    val scorePercent: Int,
    val expectedTokens: List<PronunciationAlignedToken>,
    val spokenTokens: List<PronunciationAlignedToken>,
)

class CheckPronunciationViewModel(
    private val audioRecorder: PcmAudioRecorder = PcmAudioRecorder(AnkiDroidApp.instance),
    private val audioPlayer: AudioPlayer = AudioPlayer(),
    private val xunfeiIseEvaluator: XunfeiIseEvaluator = XunfeiIseEvaluator(),
) : ViewModel() {
    private var isSpeechListening = false
    private var currentTargetText = ""
    private var lastRecognitionError: String? = null
    private var recognitionSessionId = 0

    val playbackProgressFlow = MutableStateFlow(0)
    val playbackProgressBarMaxFlow = MutableStateFlow(1)
    val isPlayingFlow = MutableStateFlow(false)
    val replayFlow = MutableSharedFlow<Unit>()
    val isPlaybackVisibleFlow = MutableStateFlow(false)
    val pronunciationResultFlow = MutableStateFlow<PronunciationAssessment?>(null)
    val pronunciationTargetFlow = MutableStateFlow("")
    val isRecognizingSpeechFlow = MutableStateFlow(false)
    val recognitionStatusFlow = MutableStateFlow("")
    val recognitionErrorFlow = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private var progressBarUpdateJob: Job? = null
    private val currentFile get() = audioRecorder.currentFile
    private val isPlaying get() = audioPlayer.isPlaying

    init {
        addCloseable(audioPlayer)
        addCloseable(audioRecorder)

        audioPlayer.onCompletion = {
            viewModelScope.launch {
                playbackProgressFlow.emit(playbackProgressBarMaxFlow.value)
                isPlayingFlow.emit(false)
            }
        }
    }

    fun setPronunciationTargetText(text: String) {
        currentTargetText = text.trim()
        viewModelScope.launch {
            pronunciationTargetFlow.emit(currentTargetText)
        }
        pronunciationResultFlow.value = null
    }

    fun onRecordingStarted() {
        recognitionSessionId += 1
        resetPlayback()
        lastRecognitionError = null
        pronunciationResultFlow.value = null
        Log.i(TAG, "recording started; session=$recognitionSessionId target='$currentTargetText'")
        audioRecorder.start()
        isSpeechListening = true
        isRecognizingSpeechFlow.value = true
        recognitionStatusFlow.value = "Listening..."
    }

    fun onRecordingCancelled() {
        viewModelScope.launch {
            audioRecorder.stop(keepFile = false)
            cancelSpeechRecognizer()
        }
    }

    fun onRecordingCompleted() {
        viewModelScope.launch {
            val recordedFile = audioRecorder.stop(keepFile = true)
            if (!isSpeechListening || recordedFile == null || !recordedFile.exists()) {
                recognitionStatusFlow.value = ""
                recognitionErrorFlow.tryEmit(lastRecognitionError ?: "Speech recording did not start.")
                Log.w(TAG, "recording completed but no PCM file is available; session=$recognitionSessionId")
                return@launch
            }
            isSpeechListening = false
            recognitionStatusFlow.value = "Checking pronunciation..."
            isPlaybackVisibleFlow.emit(false)
            isPlayingFlow.emit(false)
            playbackProgressFlow.emit(0)
            evaluateWithXunfei(recordedFile)
        }
    }

    fun pausePlayback() {
        if (isPlaying) {
            progressBarUpdateJob?.cancel()
            audioPlayer.pause()
            viewModelScope.launch {
                isPlayingFlow.emit(false)
            }
        }
    }

    fun onPlayOrReplay() {
        if (!isPlaybackVisibleFlow.value) return

        if (isPlaying) {
            replayCurrentFile()
            viewModelScope.launch {
                replayFlow.emit(Unit)
            }
        } else if (audioPlayer.isPaused) {
            viewModelScope.launch { isPlayingFlow.emit(true) }
            audioPlayer.resume()
            launchProgressBarUpdateJob()
        } else {
            viewModelScope.launch { isPlayingFlow.emit(true) }
            playCurrentFile()
        }
    }

    fun onCancelPlayback() {
        progressBarUpdateJob?.cancel()
        audioPlayer.close()
        resetPlayback()
    }

    fun resetAll() {
        onRecordingCancelled()
        onCancelPlayback()
    }

    private fun cancelSpeechRecognizer() {
        if (!isSpeechListening) return
        isSpeechListening = false
        isRecognizingSpeechFlow.value = false
        recognitionStatusFlow.value = ""
    }

    private fun resetPlayback() {
        isPlaybackVisibleFlow.value = false
        playbackProgressFlow.value = 0
        isPlayingFlow.value = false
    }

    private suspend fun evaluateWithXunfei(recordedFile: File) {
        if (currentTargetText.isBlank()) {
            pronunciationResultFlow.emit(null)
            recognitionStatusFlow.emit("")
            recognitionErrorFlow.emit("Show the answer before pronunciation check.")
            return
        }
        runCatching {
            xunfeiIseEvaluator.evaluate(recordedFile, currentTargetText)
        }.onSuccess { result ->
            saveDebugRecording("xunfei_${result.totalScore.roundToInt()}")
            pronunciationResultFlow.emit(buildXunfeiAssessment(result))
            recognitionStatusFlow.emit("")
            isRecognizingSpeechFlow.emit(false)
        }.onFailure { error ->
            Log.w(TAG, "xunfei pronunciation evaluation failed", error)
            saveDebugRecording("xunfei_error")
            pronunciationResultFlow.emit(null)
            recognitionStatusFlow.emit("")
            recognitionErrorFlow.emit("Xunfei ISE failed: ${error.message.orEmpty()}")
            isRecognizingSpeechFlow.emit(false)
        }
    }

    private fun buildXunfeiAssessment(result: XunfeiIseResult): PronunciationAssessment {
        val expectedTokens = tokenize(currentTargetText)
        val wordScores = result.words.associate { normalizeToken(it.text) to it.score }
        val aligned =
            expectedTokens.map { token ->
                val score = wordScores[normalizeToken(token)]
                PronunciationAlignedToken(
                    text = token,
                    state =
                        if (score == null ||
                            score >= WORD_PASS_SCORE
                        ) {
                            PronunciationTokenState.MATCH
                        } else {
                            PronunciationTokenState.MISMATCH
                        },
                )
            }
        val weakWords =
            result.words
                .filter { (it.score ?: 100f) < WORD_PASS_SCORE }
                .map {
                    PronunciationAlignedToken(
                        text = "${it.text} ${it.score?.roundToInt() ?: 0}",
                        state = PronunciationTokenState.MISMATCH,
                    )
                }
        return PronunciationAssessment(
            scorePercent = result.totalScore.roundToInt().coerceIn(0, 100),
            expectedTokens = aligned,
            spokenTokens = weakWords.ifEmpty { listOf(PronunciationAlignedToken("All words passed", PronunciationTokenState.MATCH)) },
        )
    }

    private fun tokenize(text: String): List<String> =
        text
            .lowercase(Locale.getDefault())
            .replace('’', '\'')
            .replace("'", "")
            .replace(Regex("[^\\p{L}\\p{N}'-]+"), " ")
            .trim()
            .split(Regex("\\s+"))
            .filter { it.isNotBlank() }

    private fun normalizeToken(text: String): String =
        text
            .lowercase(Locale.US)
            .replace('’', '\'')
            .replace("'", "")
            .replace(Regex("[^\\p{L}\\p{N}-]+"), "")

    private fun playCurrentFile() {
        val filePath = currentFile?.absolutePath ?: return
        audioPlayer.play(filePath) {
            viewModelScope.launch {
                playbackProgressBarMaxFlow.emit(audioPlayer.duration)
                launchProgressBarUpdateJob()
            }
        }
    }

    private fun replayCurrentFile() {
        audioPlayer.replay()
        launchProgressBarUpdateJob()
    }

    private fun launchProgressBarUpdateJob() {
        progressBarUpdateJob?.cancel()
        progressBarUpdateJob =
            viewModelScope.launch {
                while (isPlaying) {
                    playbackProgressFlow.emit(audioPlayer.currentPosition)
                    delay(50L)
                }
            }
    }

    private suspend fun saveDebugRecording(recognizedText: String) {
        val source = currentFile ?: return
        if (!source.exists()) return
        withContext(Dispatchers.IO) {
            runCatching {
                val directory = File(AnkiDroidApp.instance.getExternalFilesDir(null), DEBUG_RECORDING_DIRECTORY)
                if (!directory.exists()) {
                    directory.mkdirs()
                }
                val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
                val target = safeFilePart(currentTargetText).ifBlank { "blank_target" }
                val recognized = safeFilePart(recognizedText).ifBlank { "blank_result" }
                val destination = File(directory, "${timestamp}__target_${target}__said_$recognized.pcm")
                source.copyTo(destination, overwrite = true)
                Log.i(TAG, "saved pronunciation debug recording: ${destination.absolutePath}")
            }.onFailure {
                Log.w(TAG, "failed to save pronunciation debug recording", it)
            }
        }
    }

    private fun safeFilePart(text: String): String =
        text
            .lowercase(Locale.US)
            .replace('’', '\'')
            .replace(Regex("[^a-z0-9]+"), "_")
            .trim('_')
            .take(MAX_DEBUG_FILE_PART_LENGTH)

    companion object {
        private const val TAG = "AnkiPronunciation"
        private const val DEBUG_RECORDING_DIRECTORY = "pronunciation-debug"
        private const val MAX_DEBUG_FILE_PART_LENGTH = 48
        private const val WORD_PASS_SCORE = 60f
    }
}
