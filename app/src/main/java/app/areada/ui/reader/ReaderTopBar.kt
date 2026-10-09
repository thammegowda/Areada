package app.areada.ui.reader

import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Bookmark
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material.icons.outlined.Cast
import androidx.compose.material.icons.outlined.CastConnected
import androidx.compose.material.icons.automirrored.outlined.FormatListBulleted
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Pause
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.RecordVoiceOver
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.SkipNext
import androidx.compose.material.icons.outlined.SkipPrevious
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.areada.R

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ReaderTopBar(
    title: String,
    subtitle: String,
    onBack: () -> Unit,
    onSettings: (() -> Unit)?,
    onSearch: (() -> Unit)? = null,
    onTableOfContents: (() -> Unit)? = null,
    onBookmarkToggle: (() -> Unit)? = null,
    isBookmarked: Boolean = false,
    onBookCast: (() -> Unit)? = null,
    bookCastActive: Boolean = false,
    onReadAloud: (() -> Unit)? = null,
    readAloudActive: Boolean = false,
    readAloudPlaying: Boolean = false,
    readAloudCanPlay: Boolean = false,
    readAloudPreviousEnabled: Boolean = false,
    readAloudNextEnabled: Boolean = false,
    onReadAloudPrevious: (() -> Unit)? = null,
    onReadAloudNext: (() -> Unit)? = null,
    onReadAloudStop: (() -> Unit)? = null,
) {
    TopAppBar(
        colors = TopAppBarDefaults.topAppBarColors(
            containerColor = MaterialTheme.colorScheme.background,
            titleContentColor = MaterialTheme.colorScheme.onBackground,
        ),
        navigationIcon = {
            TextButton(onClick = onBack) {
                Text(text = stringResource(R.string.library))
            }
        },
        title = {
            Column {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    overflow = TextOverflow.Visible,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 300,
                        velocity = 30.dp
                    )
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.basicMarquee(
                        iterations = Int.MAX_VALUE,
                        initialDelayMillis = 300,
                        velocity = 30.dp
                    )
                )
            }
        },
        actions = {
            onBookCast?.let { toggleBookCast ->
                IconButton(onClick = toggleBookCast) {
                    Icon(
                        imageVector = if (bookCastActive) {
                            Icons.Outlined.CastConnected
                        } else {
                            Icons.Outlined.Cast
                        },
                        contentDescription = if (bookCastActive) {
                            stringResource(R.string.stop_usb_book_cast)
                        } else {
                            stringResource(R.string.start_usb_book_cast)
                        },
                        tint = if (bookCastActive) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        },
                    )
                }
            }
            if (readAloudActive) {
                IconButton(
                    onClick = { onReadAloudPrevious?.invoke() },
                    enabled = readAloudPreviousEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipPrevious,
                        contentDescription = stringResource(R.string.previous_sentence),
                    )
                }
                IconButton(
                    onClick = { onReadAloud?.invoke() },
                    enabled = readAloudCanPlay,
                ) {
                    Icon(
                        imageVector = if (readAloudPlaying) Icons.Outlined.Pause else Icons.Outlined.PlayArrow,
                        contentDescription = if (readAloudPlaying) {
                            stringResource(R.string.pause_read_aloud)
                        } else {
                            stringResource(R.string.resume_read_aloud)
                        },
                    )
                }
                IconButton(
                    onClick = { onReadAloudNext?.invoke() },
                    enabled = readAloudNextEnabled,
                ) {
                    Icon(
                        imageVector = Icons.Outlined.SkipNext,
                        contentDescription = stringResource(R.string.next_sentence),
                    )
                }
                IconButton(onClick = { onReadAloudStop?.invoke() }) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(R.string.stop_read_aloud),
                    )
                }
            } else {
                onReadAloud?.let { toggleReadAloud ->
                    IconButton(onClick = toggleReadAloud) {
                        Icon(
                            imageVector = Icons.Outlined.RecordVoiceOver,
                            contentDescription = stringResource(R.string.read_aloud),
                        )
                    }
                }
                onSearch?.let { openSearch ->
                    IconButton(onClick = openSearch) {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = stringResource(R.string.search_current_chapter),
                        )
                    }
                }
                onTableOfContents?.let { openToc ->
                    IconButton(onClick = openToc) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Outlined.FormatListBulleted,
                            contentDescription = stringResource(R.string.table_of_contents),
                        )
                    }
                }
                onBookmarkToggle?.let { toggleBookmark ->
                    IconButton(onClick = toggleBookmark) {
                        Icon(
                            imageVector = if (isBookmarked) Icons.Outlined.Bookmark else Icons.Outlined.BookmarkBorder,
                            contentDescription = if (isBookmarked) {
                                stringResource(R.string.remove_bookmark)
                            } else {
                                stringResource(R.string.add_bookmark)
                            },
                            tint = if (isBookmarked) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                onSettings?.let { openSettings ->
                    IconButton(onClick = openSettings) {
                        Icon(
                            imageVector = Icons.Outlined.Settings,
                            contentDescription = stringResource(R.string.settings),
                        )
                    }
                }
            }
        },
    )
}
