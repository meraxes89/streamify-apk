package com.streamify.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Reply
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.streamify.app.data.models.Track
import com.streamify.app.data.supabase.TrackComment
import com.streamify.app.ui.theme.StreamifyColors
import com.streamify.app.ui.theme.StreamifyDimens
import com.streamify.app.ui.theme.StreamifyType
import com.streamify.app.util.DurationFormatter
import com.streamify.app.util.StreamifyHapticEngine
import com.streamify.app.viewmodel.CommunityViewModel
import kotlinx.coroutines.flow.StateFlow

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CommentsSheet(
    track: Track?,
    positionFlow: StateFlow<Long>,
    communityViewModel: CommunityViewModel,
    onSeekTo: (Long) -> Unit,
    onDismiss: () -> Unit
) {
    val state by communityViewModel.uiState.collectAsState()
    var commentInput by remember { mutableStateOf("") }
    // Gap #34: the comment the input bar currently replies to (null = root).
    var replyingTo by remember { mutableStateOf<TrackComment?>(null) }

    // Collect the 5 Hz position ticker HERE, inside the sheet, so its
    // recomposition scope is confined to the comments UI. Collecting it in
    // the caller recomposed the entire player sheet five times per second.
    val currentPositionMs by positionFlow.collectAsState()

    // Gap #34: fold the flat comment list into reply threads off the
    // recomposition path (recomputed only when the list identity changes).
    val threads = remember(state.currentTrackComments) {
        buildCommentThreads(state.currentTrackComments)
    }

    LaunchedEffect(track) {
        communityViewModel.loadCommentsForTrack(track)
        // Track changed → any in-flight reply context is stale.
        replyingTo = null
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = StreamifyColors.BgElevated,
        dragHandle = { BottomSheetDefaults.DragHandle(color = StreamifyColors.TextDimmed) }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.75f)
                .padding(horizontal = StreamifyDimens.SpaceMD)
        ) {
            // Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text(
                        "Live Song Reactions",
                        style = StreamifyType.HeadlineMedium,
                        color = StreamifyColors.TextMain
                    )
                    val threadSummary = if (threads.size > 1) {
                        "${state.currentTrackComments.size} comments · ${threads.size} threads"
                    } else {
                        "${state.currentTrackComments.size} comments on this track"
                    }
                    Text(
                        threadSummary,
                        style = StreamifyType.Caption,
                        color = StreamifyColors.TextSub
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = StreamifyColors.TextSub)
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceMD))

            // Comments List
            if (state.isCommentsLoading) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator(color = StreamifyColors.Primary, modifier = Modifier.size(32.dp))
                }
            } else if (threads.isEmpty()) {
                Box(modifier = Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("💬", fontSize = 40.sp)
                        Spacer(modifier = Modifier.height(8.dp))
                        Text("No comments yet", style = StreamifyType.BodyLarge, color = StreamifyColors.TextMain)
                        Text("Be the first to react at ${DurationFormatter.formatMs(currentPositionMs)}!", style = StreamifyType.Caption, color = StreamifyColors.TextSub)
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(
                        threads,
                        key = { it.root.id.ifBlank { "root_${it.root.hashCode()}" } }
                    ) { thread ->
                        CommentThread(
                            thread = thread,
                            currentPosMs = currentPositionMs,
                            likedComments = state.likedComments,
                            onTimestampClick = onSeekTo,
                            onLike = { communityViewModel.toggleCommentLike(it) },
                            onReply = { replyingTo = it }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(StreamifyDimens.SpaceSM))

            // Reply context chip (Gap #34): shows whose comment the input
            // bar will thread under; one tap cancels back to a root comment.
            val activeReplyTarget = replyingTo
            if (activeReplyTarget != null) {
                Surface(
                    color = StreamifyColors.Primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 6.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Filled.Reply,
                            contentDescription = null,
                            tint = StreamifyColors.Primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Replying to ${activeReplyTarget.userName}",
                                style = StreamifyType.CaptionBold,
                                color = StreamifyColors.Primary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            if (activeReplyTarget.commentText.isNotBlank()) {
                                Text(
                                    activeReplyTarget.commentText.take(56),
                                    style = StreamifyType.Caption,
                                    color = StreamifyColors.TextSub,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        IconButton(
                            onClick = { replyingTo = null },
                            modifier = Modifier.size(24.dp)
                        ) {
                            Icon(
                                Icons.Default.Close,
                                contentDescription = "Cancel reply",
                                tint = StreamifyColors.TextSub,
                                modifier = Modifier.size(16.dp)
                            )
                        }
                    }
                }
            }

            // Input Bar with Timestamp pill
            Surface(
                color = StreamifyColors.BgCard,
                shape = RoundedCornerShape(24.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = StreamifyDimens.SpaceLG)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Timestamp Badge — anchors the new comment (or reply)
                    // to the current playhead.
                    Surface(
                        color = StreamifyColors.Primary.copy(alpha = 0.2f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = DurationFormatter.formatMs(currentPositionMs),
                            style = StreamifyType.CaptionBold,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    TextField(
                        value = commentInput,
                        onValueChange = { commentInput = it },
                        placeholder = {
                            Text(
                                if (activeReplyTarget != null) {
                                    "Reply to ${activeReplyTarget.userName} at ${DurationFormatter.formatMs(currentPositionMs)}..."
                                } else {
                                    "Add reaction at ${DurationFormatter.formatMs(currentPositionMs)}..."
                                },
                                color = StreamifyColors.TextDimmed,
                                style = StreamifyType.BodySmall
                            )
                        },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Transparent,
                            unfocusedContainerColor = Color.Transparent,
                            focusedTextColor = StreamifyColors.TextMain,
                            unfocusedTextColor = StreamifyColors.TextMain,
                            focusedIndicatorColor = Color.Transparent,
                            unfocusedIndicatorColor = Color.Transparent
                        ),
                        modifier = Modifier.weight(1f),
                        singleLine = true
                    )

                    IconButton(
                        onClick = {
                            if (commentInput.isNotBlank()) {
                                val parent = replyingTo
                                communityViewModel.postComment(
                                    track,
                                    currentPositionMs,
                                    commentInput,
                                    parent?.id
                                ) { posted ->
                                    if (posted) {
                                        commentInput = ""
                                        replyingTo = null
                                    }
                                }
                            }
                        },
                        enabled = commentInput.isNotBlank()
                    ) {
                        Icon(
                            if (activeReplyTarget != null) Icons.Filled.Reply else Icons.Default.Send,
                            contentDescription = if (activeReplyTarget != null) "Send reply" else "Send",
                            tint = if (commentInput.isNotBlank()) StreamifyColors.Primary else StreamifyColors.TextDimmed
                        )
                    }
                }
            }
        }
    }
}

/** One root comment plus its nested replies (Gap #34 threading). */
private data class CommentThread(
    val root: TrackComment,
    val replies: List<TrackComment>
)

/**
 * Folds a flat comment list into threads. Roots order by timestamp; replies
 * nest under their parent ordered by timestamp. Orphaned replies (deleted or
 * unfetched parent) defensively surface as roots so no comment is ever lost.
 */
private fun buildCommentThreads(comments: List<TrackComment>): List<CommentThread> {
    val byId = HashMap<String, TrackComment>(comments.size)
    comments.forEach { if (it.id.isNotBlank()) byId[it.id] = it }

    val roots = mutableListOf<TrackComment>()
    val repliesByParent = HashMap<String, MutableList<TrackComment>>()
    for (comment in comments) {
        val parentId = comment.parentId
        if (parentId == null || byId[parentId] == null) {
            roots += comment
        } else {
            repliesByParent.getOrPut(parentId) { mutableListOf() } += comment
        }
    }

    return roots
        .sortedBy { it.timestampMs }
        .map { root ->
            CommentThread(
                root = root,
                replies = (repliesByParent[root.id] ?: emptyList()).sortedBy { it.timestampMs }
            )
        }
}

@Composable
private fun CommentThread(
    thread: CommentThread,
    currentPosMs: Long,
    likedComments: Set<String>,
    onTimestampClick: (Long) -> Unit,
    onLike: (TrackComment) -> Unit,
    onReply: (TrackComment) -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        CommentItem(
            comment = thread.root,
            isLiked = thread.root.id in likedComments,
            isReply = false,
            replyCount = thread.replies.size,
            currentPosMs = currentPosMs,
            onTimestampClick = onTimestampClick,
            onLike = onLike,
            onReply = onReply
        )

        thread.replies.forEach { reply ->
            // Indented reply with a thread connector line down the left edge.
            Row(
                modifier = Modifier
                    .padding(start = 20.dp)
                    .height(IntrinsicSize.Min)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(2.dp)
                        .background(StreamifyColors.Primary.copy(alpha = 0.35f))
                )
                Spacer(modifier = Modifier.width(10.dp))
                Box(modifier = Modifier.weight(1f)) {
                    CommentItem(
                        comment = reply,
                        isLiked = reply.id in likedComments,
                        isReply = true,
                        replyCount = 0,
                        currentPosMs = currentPosMs,
                        onTimestampClick = onTimestampClick,
                        onLike = onLike,
                        onReply = onReply
                    )
                }
            }
        }
    }
}

@Composable
private fun CommentItem(
    comment: TrackComment,
    isLiked: Boolean,
    isReply: Boolean,
    replyCount: Int,
    currentPosMs: Long,
    onTimestampClick: (Long) -> Unit,
    onLike: (TrackComment) -> Unit,
    onReply: (TrackComment) -> Unit
) {
    val isNearCurrentTime = kotlin.math.abs(comment.timestampMs - currentPosMs) < 3000
    val avatarSize = if (isReply) 24.dp else 32.dp

    Surface(
        color = if (isNearCurrentTime) StreamifyColors.Primary.copy(alpha = 0.12f) else StreamifyColors.BgCard,
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.Top
        ) {
            // Avatar
            if (comment.userAvatar.isNotBlank()) {
                AsyncImage(
                    model = comment.userAvatar,
                    contentDescription = null,
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(avatarSize)
                        .clip(CircleShape)
                        .background(StreamifyColors.PrimaryDark),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        comment.userName.take(1).uppercase(),
                        style = StreamifyType.CaptionBold,
                        color = StreamifyColors.TextMain
                    )
                }
            }

            Spacer(modifier = Modifier.width(10.dp))

            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            comment.userName,
                            style = StreamifyType.BodySmallBold,
                            color = StreamifyColors.TextMain,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (!isReply && replyCount > 0) {
                            Surface(
                                color = StreamifyColors.Primary.copy(alpha = 0.14f),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text(
                                    "$replyCount ${if (replyCount == 1) "reply" else "replies"}",
                                    style = StreamifyType.Caption.copy(fontSize = 9.sp),
                                    color = StreamifyColors.Primary,
                                    modifier = Modifier.padding(horizontal = 5.dp, vertical = 1.dp)
                                )
                            }
                        }
                    }

                    // Clickable Timestamp Badge — one tap seeks the player.
                    Surface(
                        color = StreamifyColors.BgElevated,
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.clickable {
                            StreamifyHapticEngine.magneticDetent()
                            onTimestampClick(comment.timestampMs)
                        }
                    ) {
                        Text(
                            DurationFormatter.formatMs(comment.timestampMs),
                            style = StreamifyType.CaptionBold,
                            color = StreamifyColors.Primary,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(4.dp))
                Text(comment.commentText, style = StreamifyType.BodyMedium, color = StreamifyColors.TextSub)

                // ── Gap #34 action row: upvote + reply ─────────────────────
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                StreamifyHapticEngine.magneticDetent()
                                onLike(comment)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = if (isLiked) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                            contentDescription = if (isLiked) "Unlike" else "Like",
                            tint = if (isLiked) StreamifyColors.Primary else StreamifyColors.TextSub,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = if (comment.likesCount > 0) "${comment.likesCount}" else "Like",
                            style = StreamifyType.Caption.copy(fontSize = 10.sp),
                            color = if (isLiked) StreamifyColors.Primary else StreamifyColors.TextSub
                        )
                    }

                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .clickable {
                                StreamifyHapticEngine.magneticDetent()
                                onReply(comment)
                            }
                            .padding(horizontal = 4.dp, vertical = 2.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Reply,
                            contentDescription = "Reply",
                            tint = StreamifyColors.TextSub,
                            modifier = Modifier.size(14.dp)
                        )
                        Text(
                            text = "Reply",
                            style = StreamifyType.Caption.copy(fontSize = 10.sp),
                            color = StreamifyColors.TextSub
                        )
                    }
                }
            }
        }
    }
}
