package com.example.sentriai.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.sentriai.BuildConfig
import com.example.sentriai.models.ModelAsset

/**
 * Debug-only shortcut for pulling a gated `.task` model straight from Hugging Face using the
 * token in `local.properties`.
 *
 * This exists so a model can be put on a test device without standing up hosting or running
 * `adb push` — it is not the production download path, which is [ModelSetupScreen].
 *
 * Renders nothing at all in a release build, and nothing once every hub-backed model is
 * already on disk.
 */
@Composable
fun HuggingFaceDownloadCard(
    modifier: Modifier = Modifier,
    onDownloadDone: () -> Unit = {},
    viewModel: HuggingFaceDownloadViewModel = viewModel(),
) {
    if (!BuildConfig.DEBUG) return

    val state by viewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(state) {
        if (state is HuggingFaceDownloadViewModel.State.Done) {
            onDownloadDone()
        }
    }

    // Recomputed per composition rather than held in state: a finished download changes what
    // is on disk, and this is the cheap filesystem check that notices.
    val pending = viewModel.downloadable()
    if (pending.isEmpty() && state !is HuggingFaceDownloadViewModel.State.Downloading) return

    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = CardBackground),
        shape = RoundedCornerShape(16.dp),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = "Developer · Hugging Face",
                color = MutedText,
                fontSize = 10.sp,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
            )

            Spacer(Modifier.height(8.dp))

            when (val current = state) {
                is HuggingFaceDownloadViewModel.State.Downloading -> DownloadingBody(current, viewModel::cancel)

                is HuggingFaceDownloadViewModel.State.Failed -> {
                    Text(
                        text = current.message,
                        color = Color(0xFFC62828),
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                    )
                    Spacer(Modifier.height(12.dp))
                    pending.forEach { DownloadButton(it, viewModel::download) }
                }

                is HuggingFaceDownloadViewModel.State.Done -> Text(
                    text = "Downloaded. Loading model into engine...",
                    color = NavyInk,
                    fontSize = 13.sp,
                )

                HuggingFaceDownloadViewModel.State.Idle -> {
                    if (!viewModel.hasToken()) {
                        Text(
                            text = "Add hfToken=hf_… to local.properties and rebuild to enable this.",
                            color = MutedText,
                            fontSize = 13.sp,
                            lineHeight = 18.sp,
                        )
                        Spacer(Modifier.height(12.dp))
                    }
                    pending.forEach { DownloadButton(it, viewModel::download) }
                }
            }
        }
    }
}

@Composable
private fun DownloadButton(asset: ModelAsset, onDownload: (ModelAsset) -> Unit) {
    val megabytes = asset.sizeBytes?.let { " · ${it / 1_048_576} MB" }.orEmpty()
    Button(
        onClick = { onDownload(asset) },
        modifier = Modifier
            .fillMaxWidth()
            .height(48.dp),
        shape = RoundedCornerShape(12.dp),
        colors = ButtonDefaults.buttonColors(containerColor = NavyInk),
    ) {
        Text(
            text = "Download ${asset.displayName}$megabytes",
            color = Color.White,
            fontSize = 14.sp,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DownloadingBody(
    state: HuggingFaceDownloadViewModel.State.Downloading,
    onCancel: () -> Unit,
) {
    val progress = state.progress
    if (progress != null) {
        LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
    } else {
        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    }

    Spacer(Modifier.height(8.dp))

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (state.bytesTotal > 0) {
                "${state.bytesDone / 1_048_576} MB of ${state.bytesTotal / 1_048_576} MB"
            } else {
                "${state.bytesDone / 1_048_576} MB"
            },
            color = MutedText,
            fontSize = 13.sp,
        )
        TextButton(onClick = onCancel) {
            Text("Cancel", color = AccentBlue, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}
