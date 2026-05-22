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

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.flowWithLifecycle
import androidx.lifecycle.lifecycleScope
import com.ichi2.anki.R
import com.ichi2.anki.databinding.FragmentCheckPronunciationBinding
import com.ichi2.anki.ui.windows.reviewer.ReviewerViewModel
import com.ichi2.anki.utils.ext.collectIn
import com.ichi2.utils.show
import dev.androidbroadcast.vbpd.viewBinding

/**
 * Integrates [AudioRecordView] with [AudioPlayView] to play the recorded audios and show
 * a simple pronunciation assessment.
 */
class CheckPronunciationFragment : Fragment(R.layout.fragment_check_pronunciation) {
    private val viewModel: CheckPronunciationViewModel by viewModels()
    private val studyScreenViewModel: ReviewerViewModel by viewModels({ requireParentFragment() })

    private val binding by viewBinding(FragmentCheckPronunciationBinding::bind)

    private val requestPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted: Boolean ->
            if (!isGranted) {
                AlertDialog.Builder(requireContext()).show {
                    setTitle(R.string.permission_denied)
                    setMessage(R.string.recording_permission_denied_message)
                    setPositiveButton(R.string.dialog_ok) { _, _ ->
                        val intent = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                        val uri = Uri.fromParts("package", requireContext().packageName, null)
                        intent.data = uri
                        startActivity(intent)
                    }
                    setNegativeButton(R.string.dialog_cancel, null)
                }
            }
        }

    override fun onViewCreated(
        view: View,
        savedInstanceState: Bundle?,
    ) {
        super.onViewCreated(view, savedInstanceState)

        setupViewListeners()
        observeViewModel()
        observeStudyScreenViewModel()
    }

    override fun onPause() {
        super.onPause()
        if (requireActivity().isChangingConfigurations) {
            return
        }
        if (binding.recordView.isRecording) {
            binding.recordView.finishRecording()
        }
        viewModel.pausePlayback()
    }

    private fun setupViewListeners() {
        binding.playView.setButtonPressListener(
            object : AudioPlayView.ButtonPressListener {
                override fun onPlayButtonPressed() {
                    viewModel.onPlayOrReplay()
                }

                override fun onCancelButtonPressed() {
                    viewModel.onCancelPlayback()
                }
            },
        )

        binding.recordView.setRecordingListener(
            object : AudioRecordView.RecordingListener {
                override fun onRecordingPermissionRequired() {
                    requestPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                }

                override fun onRecordingStarted() {
                    viewModel.onRecordingStarted()
                }

                override fun onRecordingCanceled() {
                    viewModel.onRecordingCancelled()
                }

                override fun onRecordingCompleted() {
                    viewModel.onRecordingCompleted()
                }
            },
        )
    }

    private fun observeViewModel() {
        viewModel.isPlaybackVisibleFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { isVisible ->
            binding.playView.isVisible = isVisible
            binding.recordView.setRecordDisplayVisibility(!isVisible)
        }
        viewModel.playbackProgressFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { progress ->
                binding.playView.setPlaybackProgress(progress)
            }
        viewModel.playbackProgressBarMaxFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { max ->
                binding.playView.setPlaybackProgressBarMax(max)
            }
        viewModel.isPlayingFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { isPlaying ->
            val iconRes = if (isPlaying) R.drawable.ic_replay else R.drawable.ic_play
            binding.playView.changePlayIcon(iconRes)
        }
        viewModel.replayFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) {
            binding.playView.rotateReplayIcon()
        }

        viewModel.pronunciationTargetFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { target ->
            binding.targetPhrase.text = target
            binding.targetPhrase.isVisible = target.isNotBlank()
            binding.targetPhraseTitle.isVisible = target.isNotBlank()
            if (target.isBlank()) {
                binding.expectedPhraseSectionTitle.isVisible = false
                binding.expectedPhraseText.isVisible = false
                binding.spokenPhraseSectionTitle.isVisible = false
                binding.spokenPhraseText.isVisible = false
                binding.scoreText.isVisible = false
            }
        }

        viewModel.pronunciationResultFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { result ->
                if (result == null) {
                    binding.expectedPhraseSectionTitle.isVisible = false
                    binding.expectedPhraseText.isVisible = false
                    binding.spokenPhraseSectionTitle.isVisible = false
                    binding.spokenPhraseText.isVisible = false
                    binding.scoreText.isVisible = false
                    return@collectIn
                }

                binding.expectedPhraseSectionTitle.isVisible = true
                binding.expectedPhraseText.isVisible = true
                binding.spokenPhraseSectionTitle.isVisible = true
                binding.spokenPhraseText.isVisible = true
                binding.scoreText.isVisible = true
                binding.scoreText.text = getString(R.string.pronunciation_score, result.scorePercent)
                binding.expectedPhraseText.text = formatPronunciationLine(result.expectedTokens)
                binding.spokenPhraseText.text = formatPronunciationLine(result.spokenTokens)
            }

        viewModel.recognitionStatusFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { status ->
                binding.statusText.isVisible = status.isNotBlank()
                binding.statusText.text = status
            }

        viewModel.recognitionErrorFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { error ->
                binding.statusText.isVisible = true
                binding.statusText.text = error
            }
    }

    private fun formatPronunciationLine(tokens: List<PronunciationAlignedToken>): Spanned {
        val builder = SpannableStringBuilder()
        val errorColor = ContextCompat.getColor(requireContext(), R.color.material_red_600)
        var firstToken = true

        tokens.forEach { token ->
            if (token.text.isBlank()) return@forEach
            if (!firstToken) {
                builder.append(" ")
            }
            firstToken = false

            val start = builder.length
            builder.append(token.text)
            if (token.state != PronunciationTokenState.MATCH) {
                builder.setSpan(
                    ForegroundColorSpan(errorColor),
                    start,
                    builder.length,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
                )
            }
        }

        if (builder.isEmpty()) {
            builder.append(getString(R.string.pronunciation_no_text))
        }
        return builder
    }

    private fun observeStudyScreenViewModel() {
        studyScreenViewModel.voiceRecorderEnabledFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) { isEnabled ->
                if (!isEnabled) {
                    viewModel.resetAll()
                    binding.recordView.forceReset()
                }
            }
        studyScreenViewModel.replayVoiceFlow
            .flowWithLifecycle(lifecycle)
            .collectIn(lifecycleScope) {
                viewModel.onPlayOrReplay()
            }
        studyScreenViewModel.pronunciationTargetTextFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) { target ->
            viewModel.setPronunciationTargetText(target)
        }
        studyScreenViewModel.onCardUpdatedFlow.flowWithLifecycle(lifecycle).collectIn(lifecycleScope) {
            binding.playView.isVisible = false
            viewModel.onCancelPlayback()
            binding.recordView.setRecordDisplayVisibility(true)
        }
    }
}
