package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.streamify.app.data.models.Track
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.viewmodel.CommunityViewModel

@Composable
fun LyricsEditorDialog(
    track: Track?,
    communityViewModel: CommunityViewModel,
    onDismiss: () -> Unit
) {
    if (track == null) return

    var lrcInput by remember { mutableStateOf("") }
    var isSubmitting by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = StreamifyColors.BgElevated,
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier.padding(20.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column {
                        Text("Contribute Lyrics", style = StreamifyType.HeadlineSmall, color = StreamifyColors.TextMain)
                        Text(track.title, style = StreamifyType.Caption, color = StreamifyColors.Primary)
                    }
                    IconButton(onClick = onDismiss) {
                        Icon(Icons.Default.Close, contentDescription = "Close", tint = StreamifyColors.TextSub)
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    "Paste synced LRC lyrics (e.g. [00:12.30] Line text) to sync with Supabase cloud catalog for everyone.",
                    style = StreamifyType.Caption,
                    color = StreamifyColors.TextSub
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = lrcInput,
                    onValueChange = { lrcInput = it },
                    placeholder = { Text("[00:15.00] First lyric line...\n[00:20.50] Second lyric line...", color = StreamifyColors.TextDimmed, style = StreamifyType.BodySmall) },
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = StreamifyColors.Primary,
                        unfocusedBorderColor = StreamifyColors.Border,
                        focusedTextColor = StreamifyColors.TextMain,
                        unfocusedTextColor = StreamifyColors.TextMain,
                        cursorColor = StreamifyColors.Primary
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(180.dp),
                    shape = RoundedCornerShape(12.dp)
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        if (lrcInput.isNotBlank()) {
                            isSubmitting = true
                            communityViewModel.submitLyrics(track, lrcInput) {
                                isSubmitting = false
                                onDismiss()
                            }
                        }
                    },
                    enabled = lrcInput.isNotBlank() && !isSubmitting,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = StreamifyColors.Primary,
                        contentColor = StreamifyColors.BgBase
                    ),
                    shape = RoundedCornerShape(24.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    if (isSubmitting) {
                        CircularProgressIndicator(color = StreamifyColors.BgBase, modifier = Modifier.size(18.dp))
                    } else {
                        Text("Submit to Cloud", style = StreamifyType.BodyMediumBold)
                    }
                }
            }
        }
    }
}
