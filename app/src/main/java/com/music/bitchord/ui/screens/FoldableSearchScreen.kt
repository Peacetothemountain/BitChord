package com.music.bitchord.ui.screens

import android.app.Activity
import android.graphics.Rect
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Keyboard
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.window.layout.DisplayFeature
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import coil3.compose.AsyncImage
import com.music.bitchord.R
import com.music.bitchord.data.model.BrowseItem
import com.music.bitchord.data.model.BrowseType
import com.music.bitchord.data.model.ROW_ART_PX
import com.music.bitchord.data.model.SearchFilter
import com.music.bitchord.data.model.SearchHistoryEntity
import com.music.bitchord.data.model.SearchResult
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.model.UiState
import com.music.bitchord.data.model.artworkAt
import com.music.bitchord.ui.components.PAGE_GUTTER
import com.music.bitchord.ui.components.thumbnailBorder
import kotlinx.coroutines.flow.collectLatest

/**
 * Physical posture of a bi-fold foldable device.
 */
sealed interface FoldablePosture {
    /** Device is fully flat (or folded shut using standard single display). */
    object Flat : FoldablePosture

    /**
     * Device is half-opened around a horizontal hinge (75° - 115°).
     * Upper screen is angled towards the viewer; lower screen is flat on a table.
     */
    data class Tabletop(val hingeBounds: Rect) : FoldablePosture

    /**
     * Device is half-opened around a vertical hinge (75° - 115°),
     * held like a physical book with left and right panels.
     */
    data class Book(val hingeBounds: Rect) : FoldablePosture
}

/**
 * Window width classifications tailored for foldables and tablets.
 */
enum class FoldableWidthClass {
    /** Typical phone or bi-fold cover display (< 600dp). */
    Compact,

    /** Medium tablet or unfolded vertical clamshell (600dp ..< 840dp). */
    Medium,

    /** Expansive unfolded book-style display or large tablet (>= 840dp). */
    Expanded,
}

/**
 * Tracks physical folding postures reactively using Jetpack WindowManager [WindowInfoTracker].
 */
@Composable
fun rememberFoldablePosture(): State<FoldablePosture> {
    val context = LocalContext.current
    val postureState = remember { mutableStateOf<FoldablePosture>(FoldablePosture.Flat) }

    LaunchedEffect(context) {
        val activity = context as? Activity ?: return@LaunchedEffect
        val tracker = WindowInfoTracker.getOrCreate(activity)
        tracker.windowLayoutInfo(activity).collectLatest { layoutInfo ->
            val foldingFeature = layoutInfo.displayFeatures
                .filterIsInstance<FoldingFeature>()
                .firstOrNull { it.isSeparating }

            postureState.value = when {
                foldingFeature == null -> FoldablePosture.Flat
                foldingFeature.state == FoldingFeature.State.HALF_OPENED &&
                        foldingFeature.orientation == FoldingFeature.Orientation.HORIZONTAL -> {
                    FoldablePosture.Tabletop(foldingFeature.bounds)
                }
                foldingFeature.state == FoldingFeature.State.HALF_OPENED &&
                        foldingFeature.orientation == FoldingFeature.Orientation.VERTICAL -> {
                    FoldablePosture.Book(foldingFeature.bounds)
                }
                else -> FoldablePosture.Flat
            }
        }
    }

    return postureState
}

/**
 * Represents an entity selected in the search feed for detailed inspection
 * in the secondary pane on unfolded book-style displays.
 */
sealed interface SelectedSearchPreview {
    data class TrackPreview(val song: Song) : SelectedSearchPreview
    data class BrowsePreview(val item: BrowseItem) : SelectedSearchPreview
}

/**
 * Dedicated Foldable Intelligent Search Screen.
 *
 * Dynamically scales, formats, and adapts across:
 * 1. **Compact Cover Mode** (<600dp): Fast single-column search stream.
 * 2. **Expanded Book Mode** (>=840dp or Book Posture): Dual-pane List-Detail canonical layout.
 *    Left pane hosts query, filters, suggestions, and results.
 *    Right pane hosts high-res album art, metadata, and quick playback controls.
 * 3. **Tabletop / Flex Mode** (Horizontal hinge 75°-115°):
 *    Upper pane showcases search results and top hits angled towards user.
 *    Lower pane anchors persistent search input, voice trigger, filter chips, and soft keyboard.
 */
@Composable
fun FoldableSearchScreen(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)? = null,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    onTypeaheadLongPress: ((Song) -> Unit)? = null,
    modifier: Modifier = Modifier,
    contentPadding: PaddingValues = PaddingValues(),
) {
    val posture by rememberFoldablePosture()
    var selectedPreview by remember { mutableStateOf<SelectedSearchPreview?>(null) }

    // Update preview automatically when top hit or new results appear
    LaunchedEffect(results) {
        if (results is UiState.Success && selectedPreview == null) {
            val firstSong = results.data.filterIsInstance<SearchResult.Track>().firstOrNull()?.song
            if (firstSong != null) {
                selectedPreview = SelectedSearchPreview.TrackPreview(firstSong)
            }
        }
    }

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        val widthClass = when {
            maxWidth < 600.dp -> FoldableWidthClass.Compact
            maxWidth < 840.dp -> FoldableWidthClass.Medium
            else -> FoldableWidthClass.Expanded
        }

        when {
            // Case 1: Tabletop / Flex Posture (Clamshell or Book rested half-opened)
            posture is FoldablePosture.Tabletop -> {
                TabletopSearchLayout(
                    hingeBounds = (posture as FoldablePosture.Tabletop).hingeBounds,
                    query = query,
                    onQueryChange = onQueryChange,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    results = results,
                    loadingMore = loadingMore,
                    onLoadMore = onLoadMore,
                    listState = listState,
                    scrollResetTrigger = scrollResetTrigger,
                    focusRequested = focusRequested,
                    onFocusHandled = onFocusHandled,
                    onSongClick = onSongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    onTopResultPlay = onTopResultPlay,
                    onTopResultPlaylist = onTopResultPlaylist,
                    onBrowseClick = onBrowseClick,
                    onBrowseLongPress = onBrowseLongPress,
                    history = history,
                    suggestions = suggestions,
                    typeaheadResults = typeaheadResults,
                    onSubmit = onSubmit,
                    onSuggestionClick = onSuggestionClick,
                    onHistoryClick = onHistoryClick,
                    onHistoryRemove = onHistoryRemove,
                    onHistoryClear = onHistoryClear,
                    onTypeaheadLongPress = onTypeaheadLongPress,
                    contentPadding = contentPadding,
                )
            }

            // Case 2: Expanded Canvas (Book-Style Unfolded >=840dp or Book Posture)
            widthClass == FoldableWidthClass.Expanded || posture is FoldablePosture.Book -> {
                DualPaneBookSearchLayout(
                    query = query,
                    onQueryChange = onQueryChange,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    results = results,
                    loadingMore = loadingMore,
                    onLoadMore = onLoadMore,
                    listState = listState,
                    scrollResetTrigger = scrollResetTrigger,
                    focusRequested = focusRequested,
                    onFocusHandled = onFocusHandled,
                    onSongClick = { songs, index ->
                        songs.getOrNull(index)?.let {
                            selectedPreview = SelectedSearchPreview.TrackPreview(it)
                        }
                        onSongClick(songs, index)
                    },
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    onTopResultPlay = { song ->
                        selectedPreview = SelectedSearchPreview.TrackPreview(song)
                        onTopResultPlay(song)
                    },
                    onTopResultPlaylist = onTopResultPlaylist,
                    onBrowseClick = { item ->
                        selectedPreview = SelectedSearchPreview.BrowsePreview(item)
                        onBrowseClick(item)
                    },
                    onBrowseLongPress = onBrowseLongPress,
                    history = history,
                    suggestions = suggestions,
                    typeaheadResults = typeaheadResults,
                    onSubmit = onSubmit,
                    onSuggestionClick = onSuggestionClick,
                    onHistoryClick = onHistoryClick,
                    onHistoryRemove = onHistoryRemove,
                    onHistoryClear = onHistoryClear,
                    onTypeaheadLongPress = onTypeaheadLongPress,
                    contentPadding = contentPadding,
                    selectedPreview = selectedPreview,
                    onClearPreview = { selectedPreview = null },
                )
            }

            // Case 3: Compact Cover Display or Standard Single Screen (<600dp)
            else -> {
                SearchScreen(
                    query = query,
                    onQueryChange = onQueryChange,
                    filter = filter,
                    onFilterChange = onFilterChange,
                    results = results,
                    loadingMore = loadingMore,
                    onLoadMore = onLoadMore,
                    listState = listState,
                    scrollResetTrigger = scrollResetTrigger,
                    focusRequested = focusRequested,
                    onFocusHandled = onFocusHandled,
                    onSongClick = onSongClick,
                    onSongLongPress = onSongLongPress,
                    onSongSwipe = onSongSwipe,
                    onTopResultPlay = onTopResultPlay,
                    onTopResultPlaylist = onTopResultPlaylist,
                    onBrowseClick = onBrowseClick,
                    onBrowseLongPress = onBrowseLongPress,
                    history = history,
                    suggestions = suggestions,
                    typeaheadResults = typeaheadResults,
                    onSubmit = onSubmit,
                    onSuggestionClick = onSuggestionClick,
                    onHistoryClick = onHistoryClick,
                    onHistoryRemove = onHistoryRemove,
                    onHistoryClear = onHistoryClear,
                    onTypeaheadLongPress = onTypeaheadLongPress,
                    contentPadding = contentPadding,
                )
            }
        }
    }
}

/**
 * 2-Pane Canonical Layout for Book-style unfolded foldables.
 * Left Pane: Primary Search Bar, Filter Chips, Suggestions, Results Stream.
 * Right Pane: Rich Inspector & High-Resolution Entity Showcase.
 */
@Composable
private fun DualPaneBookSearchLayout(
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)?,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    onTypeaheadLongPress: ((Song) -> Unit)?,
    contentPadding: PaddingValues,
    selectedPreview: SelectedSearchPreview?,
    onClearPreview: () -> Unit,
) {
    Row(modifier = Modifier.fillMaxSize()) {
        // Left Pane: Search Input & Results (52% width)
        Box(
            modifier = Modifier
                .weight(1.15f)
                .fillMaxHeight()
        ) {
            SearchScreen(
                query = query,
                onQueryChange = onQueryChange,
                filter = filter,
                onFilterChange = onFilterChange,
                results = results,
                loadingMore = loadingMore,
                onLoadMore = onLoadMore,
                listState = listState,
                scrollResetTrigger = scrollResetTrigger,
                focusRequested = focusRequested,
                onFocusHandled = onFocusHandled,
                onSongClick = onSongClick,
                onSongLongPress = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onTopResultPlay = onTopResultPlay,
                onTopResultPlaylist = onTopResultPlaylist,
                onBrowseClick = onBrowseClick,
                onBrowseLongPress = onBrowseLongPress,
                history = history,
                suggestions = suggestions,
                typeaheadResults = typeaheadResults,
                onSubmit = onSubmit,
                onSuggestionClick = onSuggestionClick,
                onHistoryClick = onHistoryClick,
                onHistoryRemove = onHistoryRemove,
                onHistoryClear = onHistoryClear,
                onTypeaheadLongPress = onTypeaheadLongPress,
                contentPadding = contentPadding,
            )
        }

        // Vertical Divider along the fold
        VerticalDivider(
            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
            thickness = 1.dp,
        )

        // Right Pane: Rich Inspector Preview (48% width)
        Box(
            modifier = Modifier
                .weight(1.0f)
                .fillMaxHeight()
                .padding(contentPadding)
                .padding(24.dp)
        ) {
            if (selectedPreview != null) {
                SearchEntityInspectorPane(
                    preview = selectedPreview,
                    onPlaySong = onTopResultPlay,
                    onPlaylistSong = onTopResultPlaylist,
                    onClose = onClearPreview,
                )
            } else {
                EmptySearchInspectorPlaceholder()
            }
        }
    }
}

/**
 * Rich Detail Inspector displayed in the secondary pane of an unfolded foldable.
 */
@Composable
private fun SearchEntityInspectorPane(
    preview: SelectedSearchPreview,
    onPlaySong: (Song) -> Unit,
    onPlaylistSong: (Song) -> Unit,
    onClose: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxSize(),
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
        ),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(20.dp)
                .verticalScroll(rememberScrollState()),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Entity Preview",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
                IconButton(onClick = onClose) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close preview")
                }
            }

            Spacer(Modifier.height(16.dp))

            when (preview) {
                is SelectedSearchPreview.TrackPreview -> {
                    val song = preview.song
                    AsyncImage(
                        model = song.thumbnailUrl?.artworkAt(512),
                        contentDescription = song.title,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .thumbnailBorder(RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = song.artist,
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )

                    Spacer(Modifier.height(24.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Button(
                            onClick = { onPlaySong(song) },
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Play Radio")
                        }

                        FilledTonalButton(
                            onClick = { onPlaylistSong(song) },
                            modifier = Modifier.weight(1f),
                            shape = CircleShape,
                        ) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null)
                            Spacer(Modifier.width(6.dp))
                            Text("Add to Playlist")
                        }
                    }
                }

                is SelectedSearchPreview.BrowsePreview -> {
                    val item = preview.item
                    AsyncImage(
                        model = item.thumbnailUrl?.artworkAt(512),
                        contentDescription = item.title,
                        modifier = Modifier
                            .size(220.dp)
                            .clip(if (item.type == BrowseType.ARTIST) CircleShape else RoundedCornerShape(16.dp))
                            .thumbnailBorder(if (item.type == BrowseType.ARTIST) CircleShape else RoundedCornerShape(16.dp)),
                        contentScale = ContentScale.Crop,
                    )
                    Spacer(Modifier.height(18.dp))
                    Text(
                        text = item.title,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = TextAlign.Center,
                    )
                    Spacer(Modifier.height(6.dp))
                    Text(
                        text = item.subtitle.ifBlank { item.type.name },
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

/**
 * Placeholder displayed when no entity is actively selected in the right pane.
 */
@Composable
private fun EmptySearchInspectorPlaceholder() {
    Box(
        modifier = Modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(24.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Rounded.QueueMusic,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.4f),
                modifier = Modifier.size(64.dp),
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Tap a result to inspect",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                text = "Track details, high-res artwork, and quick actions appear here on your unfolded display.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * Tabletop / Flex mode layout (clamshell or book half-opened 75°-115° resting on a surface).
 * Upper pane: Angled results and top hit preview.
 * Crease: Physical separation gap matching hinge bounds.
 * Lower pane: Search input, voice controls, filter chips, and IME soft keyboard.
 */
@Composable
private fun TabletopSearchLayout(
    hingeBounds: Rect,
    query: String,
    onQueryChange: (String) -> Unit,
    filter: SearchFilter,
    onFilterChange: (SearchFilter) -> Unit,
    results: UiState<List<SearchResult>>?,
    loadingMore: Boolean,
    onLoadMore: () -> Unit,
    listState: LazyListState,
    scrollResetTrigger: Int,
    focusRequested: Boolean,
    onFocusHandled: () -> Unit,
    onSongClick: (List<Song>, Int) -> Unit,
    onSongLongPress: (Song) -> Unit,
    onSongSwipe: (Song) -> Unit,
    onTopResultPlay: (Song) -> Unit,
    onTopResultPlaylist: (Song) -> Unit,
    onBrowseClick: (BrowseItem) -> Unit,
    onBrowseLongPress: ((BrowseItem) -> Unit)?,
    history: List<SearchHistoryEntity>,
    suggestions: List<String>,
    typeaheadResults: List<SearchResult>,
    onSubmit: () -> Unit,
    onSuggestionClick: (String) -> Unit,
    onHistoryClick: (SearchHistoryEntity) -> Unit,
    onHistoryRemove: (String) -> Unit,
    onHistoryClear: () -> Unit,
    onTypeaheadLongPress: ((Song) -> Unit)?,
    contentPadding: PaddingValues,
) {
    val density = LocalDensity.current
    val hingeHeightDp = with(density) { hingeBounds.height().toDp() }

    Column(modifier = Modifier.fillMaxSize()) {
        // Upper Angled Pane: Search Results & Previews
        Box(
            modifier = Modifier
                .weight(1.1f)
                .fillMaxWidth()
        ) {
            SearchScreen(
                query = query,
                onQueryChange = onQueryChange,
                filter = filter,
                onFilterChange = onFilterChange,
                results = results,
                loadingMore = loadingMore,
                onLoadMore = onLoadMore,
                listState = listState,
                scrollResetTrigger = scrollResetTrigger,
                focusRequested = focusRequested,
                onFocusHandled = onFocusHandled,
                onSongClick = onSongClick,
                onSongLongPress = onSongLongPress,
                onSongSwipe = onSongSwipe,
                onTopResultPlay = onTopResultPlay,
                onTopResultPlaylist = onTopResultPlaylist,
                onBrowseClick = onBrowseClick,
                onBrowseLongPress = onBrowseLongPress,
                history = history,
                suggestions = suggestions,
                typeaheadResults = typeaheadResults,
                onSubmit = onSubmit,
                onSuggestionClick = onSuggestionClick,
                onHistoryClick = onHistoryClick,
                onHistoryRemove = onHistoryRemove,
                onHistoryClear = onHistoryClear,
                onTypeaheadLongPress = onTypeaheadLongPress,
                contentPadding = PaddingValues(bottom = 8.dp),
            )
        }

        // Crease / Hinge Separator (ensures no interactive elements touch the physical bend)
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .height(hingeHeightDp.coerceAtLeast(12.dp))
                .background(Color.Black.copy(alpha = 0.2f))
        )

        // Lower Flat Pane: Search Controls & Keyboard Anchor
        Surface(
            modifier = Modifier
                .weight(0.9f)
                .fillMaxWidth()
                .imePadding(),
            tonalElevation = 6.dp,
            color = MaterialTheme.colorScheme.surface,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = PAGE_GUTTER, vertical = 12.dp),
                verticalArrangement = Arrangement.SpaceBetween,
            ) {
                Column {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = "Tabletop Search Console",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Rounded.Keyboard,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                text = "Flex Mode",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }

                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = "Device rested in flex posture. Results are showcased above on the angled screen while input and filters stay docked here on the flat base.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }

                // Thumb-friendly corner action buttons for split keyboard ergonomics
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    OutlinedButton(
                        onClick = { onQueryChange("") },
                        shape = CircleShape,
                    ) {
                        Text("Clear Query")
                    }

                    Button(
                        onClick = onSubmit,
                        shape = CircleShape,
                    ) {
                        Icon(Icons.Rounded.Search, contentDescription = null)
                        Spacer(Modifier.width(6.dp))
                        Text("Search")
                    }
                }
            }
        }
    }
}
