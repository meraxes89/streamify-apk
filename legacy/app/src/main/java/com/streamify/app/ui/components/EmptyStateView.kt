package com.streamify.app.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType

@Composable
fun EmptyStateView(
    title: String,
    subtitle: String,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(StreamifyDimens.SpaceGiant),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = title,
            style = StreamifyType.HeadlineMedium,
            color = StreamifyColors.TextMain,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))
        Text(
            text = subtitle,
            style = StreamifyType.BodyMedium,
            color = StreamifyColors.TextSub,
            textAlign = TextAlign.Center
        )
        if (actionText != null && onActionClick != null) {
            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceXL))
            Button(
                onClick = onActionClick,
                colors = ButtonDefaults.buttonColors(
                    containerColor = StreamifyColors.TextMain,
                    contentColor = StreamifyColors.BgBase
                ),
                contentPadding = PaddingValues(
                    horizontal = StreamifyDimens.SpaceXXL,
                    vertical = StreamifyDimens.SpaceMD
                )
            ) {
                Text(
                    text = actionText,
                    style = StreamifyType.TitleSmall
                )
            }
        }
    }
}
