package com.maxrave.simpmusic.ui.component

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import coil3.compose.AsyncImage
import coil3.compose.AsyncImagePainter
import com.maxrave.simpmusic.expect.openUrl
import com.maxrave.simpmusic.ui.theme.seed
import com.maxrave.simpmusic.ui.theme.typo
import org.jetbrains.compose.resources.painterResource
import org.jetbrains.compose.resources.stringResource
import simpmusic.composeapp.generated.resources.*

private const val STREAMIFY_REPO_URL = "https://github.com/meraxes89/streamify-apk"

// GitHub renders this on demand for any public repo - repo name, description, avatar, and LIVE star count.
private const val STREAMIFY_SOCIAL_IMAGE_URL = "https://opengraph.githubassets.com/1/meraxes89/streamify-apk"
private const val SOCIAL_IMAGE_ASPECT_RATIO = 2f

@Composable
@ExperimentalMaterial3Api
fun ReviewDialog(
    onDismissRequest: () -> Unit,
    onDoneReview: () -> Unit,
) {
    val uriHandler = LocalUriHandler.current
    var bannerFailed by remember { mutableStateOf(false) }

    AlertDialog(
        properties =
            DialogProperties(
                dismissOnBackPress = false,
                dismissOnClickOutside = false,
            ),
        onDismissRequest = {
            onDismissRequest.invoke()
        },
        confirmButton = {
            TextButton(onClick = {
                onDoneReview.invoke()
                uriHandler.openUri(STREAMIFY_REPO_URL)
            }) {
                Text(
                    stringResource(Res.string.give_a_star),
                    style = typo().bodySmall,
                )
            }
        },
        dismissButton = {
            TextButton(onClick = {
                onDismissRequest.invoke()
            }) {
                Text(
                    stringResource(Res.string.later),
                    style = typo().bodySmall,
                )
            }
        },
        icon = {
            Icon(painterResource(Res.drawable.mono), "App Icon")
        },
        title = {
            Text(
                stringResource(Res.string.enjoying_simpmusic),
                style = typo().titleMedium,
            )
        },
        text = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (!bannerFailed) {
                    AsyncImage(
                        model = STREAMIFY_SOCIAL_IMAGE_URL,
                        contentDescription = "Streamify GitHub Repository Card",
                        contentScale = ContentScale.FillWidth,
                        onState = { state ->
                            if (state is AsyncImagePainter.State.Error) bannerFailed = true
                        },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .aspectRatio(SOCIAL_IMAGE_ASPECT_RATIO)
                                .clip(RoundedCornerShape(12.dp)),
                    )
                    Spacer(Modifier.height(14.dp))
                }
                Text(
                    buildAnnotatedString {
                        append(stringResource(Res.string.if_you_enjoy_using_simpmusic_star_simpmusic_on_github_or_leave_a_review_on))
                        withLink(
                            LinkAnnotation.Url(
                                "https://www.producthunt.com/products/simpmusic",
                                TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline, color = seed)),
                            ) {
                                onDoneReview.invoke()
                                onDismissRequest.invoke()
                                uriHandler.openUri("https://www.producthunt.com/products/simpmusic")
                            },
                        ) {
                            append(" ProductHunt")
                        }
                        append("\n")
                        // Every locale wraps this in quotes to keep the trailing space, Android style, and Compose
                        // Resources prints them as text. Strip them and add the space here, like " ProductHunt" above.
                        append(stringResource(Res.string.if_you_love_my_work_consider).removeSurrounding("\"").trimEnd() + " ")
                        withLink(
                            LinkAnnotation.Url(
                                STREAMIFY_REPO_URL,
                                TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline, color = seed)),
                            ) {
                                onDoneReview.invoke()
                                onDismissRequest.invoke()
                                uriHandler.openUri(STREAMIFY_REPO_URL)
                            },
                        ) {
                            append(stringResource(Res.string.buying_me_a_coffee))
                        }
                        append("\n")
                        withLink(
                            LinkAnnotation.Url(
                                "https://x.com/sireenyadav",
                                TextLinkStyles(style = SpanStyle(textDecoration = TextDecoration.Underline, color = seed)),
                            ) {
                                onDoneReview.invoke()
                                onDismissRequest.invoke()
                                // openUrl rather than the uri handler, which throws where no browser answers.
                                runCatching { openUrl("https://x.com/sireenyadav") }
                            },
                        ) {
                            append(stringResource(Res.string.follow_me_on_x))
                        }
                    },
                    textAlign = TextAlign.Center,
                    style = typo().bodySmall,
                )
            }
        },
    )
}
