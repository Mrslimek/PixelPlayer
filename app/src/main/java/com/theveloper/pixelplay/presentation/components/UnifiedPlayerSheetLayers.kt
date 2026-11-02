package com.theveloper.pixelplay.presentation.components

import androidx.annotation.OptIn
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.material3.ColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.zIndex
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.util.UnstableApi
import com.theveloper.pixelplay.data.model.Song
import com.theveloper.pixelplay.data.preferences.FullPlayerLoadingTweaks
import com.theveloper.pixelplay.presentation.components.player.FullPlayerContent
import com.theveloper.pixelplay.presentation.components.scoped.FullPlayerVisualState
import com.theveloper.pixelplay.presentation.components.scoped.rememberFullPlayerRuntimePolicy
import com.theveloper.pixelplay.presentation.viewmodel.PlayerSheetState
import com.theveloper.pixelplay.presentation.viewmodel.PlayerViewModel
import com.theveloper.pixelplay.presentation.viewmodel.toMedia3RepeatMode
import com.theveloper.pixelplay.presentation.viewmodel.StablePlayerState

@OptIn(UnstableApi::class)
@Composable
internal fun BoxScope.UnifiedPlayerMiniAndFullLayers(
    currentSong: Song?,
    miniPlayerScheme: ColorScheme?,
    overallSheetTopCornerRadiusProvider: () -> Dp,
    infrequentPlayerState: StablePlayerState,
    isCastConnecting: Boolean,
    isPreparingPlayback: Boolean,
    playerContentExpansionFraction: Animatable<Float, AnimationVector1D>,
    albumColorScheme: ColorScheme,
    bottomSheetOpenFraction: Float,
    fullPlayerVisualState: FullPlayerVisualState,
    containerHeight: Dp,
    currentQueueSourceName: String,
    currentSheetContentState: PlayerSheetState,
    carouselStyle: String,
    fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
    isSheetDragGestureActive: Boolean = false,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isFavorite: Boolean,
    shouldRenderFullPlayer: Boolean = true,
    currentHorizontalPaddingStartPxProvider: () -> Float,
    currentHorizontalPaddingEndPxProvider: () -> Float,
    onShowQueueClicked: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit,
    onShowCastClicked: () -> Unit
) {
    // Shared-session display-source swap: while a peer holds the stint (any reachability)
    // the surface renders the resolved local Song and the stint's play state; local
    // pass-through makes the non-session case identical to before.
    val isSessionActive by playerViewModel.sessionActive.collectAsStateWithLifecycle()
    val displaySong by playerViewModel.displaySong.collectAsStateWithLifecycle()
    val displayIsPlaying by playerViewModel.displayIsPlaying.collectAsStateWithLifecycle()
    val sessionShuffle by playerViewModel.sessionShuffle.collectAsStateWithLifecycle()
    val sessionRepeat by playerViewModel.sessionRepeat.collectAsStateWithLifecycle()
    val surfaceSong = if (isSessionActive) displaySong else currentSong

    surfaceSong?.let { surfaceSongNonNull ->
        miniPlayerScheme?.let { readyScheme ->
            CompositionLocalProvider(
                LocalMaterialTheme provides readyScheme
            ) {
                val miniPlayerZIndex by remember {
                    derivedStateOf {
                        if (playerContentExpansionFraction.value < 0.5f) 1f else 0f
                    }
                }
                Box(
                    modifier = Modifier
                        .align(Alignment.TopCenter)
                        .fillMaxWidth()
                        .height(MiniPlayerHeight)
                        .graphicsLayer {
                            // Compute miniAlpha in the draw phase from the Animatable,
                            // avoiding per-frame recomposition during gestures.
                            alpha = (1f - playerContentExpansionFraction.value * 2f)
                                .coerceIn(0f, 1f)
                        }
                        .layout { measurable, constraints ->
                            val fraction = playerContentExpansionFraction.value
                            val startPaddingPx = currentHorizontalPaddingStartPxProvider().toInt().coerceAtLeast(0)
                            val endPaddingPx = currentHorizontalPaddingEndPxProvider().toInt().coerceAtLeast(0)
                            
                            val targetWidth = if (fraction > 0f) {
                                (constraints.maxWidth - startPaddingPx - endPaddingPx).coerceAtLeast(0)
                            } else {
                                constraints.maxWidth
                            }
                            val placeable = measurable.measure(
                                constraints.copy(
                                    minWidth = targetWidth,
                                    maxWidth = targetWidth
                                )
                            )
                            layout(constraints.maxWidth, constraints.maxHeight) {
                                val xOffset = if (fraction > 0f) startPaddingPx else 0
                                placeable.placeRelative(xOffset, 0)
                            }
                        }
                        .zIndex(miniPlayerZIndex)
                ) {
                    val isMiniPlayerVisible by remember {
                        derivedStateOf { playerContentExpansionFraction.value < 0.01f }
                    }
                    // Display-aware play state: while a session is active the mini player
                    // shows the remote stint's song, so it must also show its play state.
                    val miniIsPlaying = if (isSessionActive) displayIsPlaying else infrequentPlayerState.isPlaying
                    MiniPlayerContentInternal(
                        song = surfaceSongNonNull,
                        isPlaying = miniIsPlaying,
                        isCastConnecting = isCastConnecting,
                        isPreparingPlayback = isPreparingPlayback,
                        onPlayPause = { playerViewModel.playPause() },
                        onPrevious = { playerViewModel.previousSong() },
                        onNext = { playerViewModel.nextSong() },
                        canScroll = isMiniPlayerVisible && miniIsPlaying,
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }
        }

        if (shouldRenderFullPlayer) {
            CompositionLocalProvider(
                LocalMaterialTheme provides albumColorScheme
            ) {
                val fullPlayerScale by remember(bottomSheetOpenFraction) {
                    // Keep the depth effect, but avoid aggressive full-screen rescaling on every frame.
                    derivedStateOf { lerp(1f, 0.972f, bottomSheetOpenFraction) }
                }

                val fullPlayerZIndex by remember {
                    derivedStateOf {
                        if (playerContentExpansionFraction.value >= 0.5f) 1f else 0f
                    }
                }
                val fullPlayerOffset by remember {
                    derivedStateOf {
                        if (playerContentExpansionFraction.value <= 0.01f) IntOffset(0, 10000)
                        else IntOffset.Zero
                    }
                }
                val fullPlayerRuntimePolicy = rememberFullPlayerRuntimePolicy(
                    currentSheetState = currentSheetContentState,
                    expansionFraction = playerContentExpansionFraction,
                    bottomSheetOpenFraction = bottomSheetOpenFraction
                )

                // Scoped queue collection: only the FullPlayer subtree observes
                // the queue. Sibling MiniPlayer composable and the whole
                // UnifiedPlayerSheetV2 caller are insulated from queue churn.
                val currentPlaybackQueue by playerViewModel.queueFlow
                    .collectAsStateWithLifecycle()

                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .requiredHeight(containerHeight)
                        .graphicsLayer {
                            // Read from FullPlayerVisualState lazy getters in the draw phase;
                            // these read Animatable.value internally → re-draw only, no recomposition.
                            alpha = fullPlayerVisualState.contentAlpha
                            translationY = fullPlayerVisualState.translationY
                            scaleX = fullPlayerScale
                            scaleY = fullPlayerScale
                        }
                        .zIndex(fullPlayerZIndex)
                        .offset { fullPlayerOffset }
                ) {
                    val latestInfrequentPlayerState = rememberUpdatedState(infrequentPlayerState)
                    val latestIsFavorite = rememberUpdatedState(isFavorite)
                    val latestDisplayIsPlaying = rememberUpdatedState(displayIsPlaying)
                    val latestSessionActive = rememberUpdatedState(isSessionActive)
                    val latestSurfaceSong = rememberUpdatedState(surfaceSongNonNull)
                    val latestSessionShuffle = rememberUpdatedState(sessionShuffle)
                    val latestSessionRepeat = rememberUpdatedState(sessionRepeat)
                    val expansionFractionProvider = remember(playerContentExpansionFraction) {
                        { playerContentExpansionFraction.value }
                    }
                    val isPlayingProvider = remember {
                        {
                            if (latestSessionActive.value) latestDisplayIsPlaying.value
                            else latestInfrequentPlayerState.value.isPlaying
                        }
                    }
                    val playWhenReadyProvider = remember {
                        { latestInfrequentPlayerState.value.playWhenReady }
                    }
                    val repeatModeProvider = remember {
                        {
                            if (latestSessionActive.value) {
                                latestSessionRepeat.value?.toMedia3RepeatMode()
                                    ?: latestInfrequentPlayerState.value.repeatMode
                            } else {
                                latestInfrequentPlayerState.value.repeatMode
                            }
                        }
                    }
                    val isShuffleEnabledProvider = remember {
                        {
                            if (latestSessionActive.value) {
                                latestSessionShuffle.value
                                    ?: latestInfrequentPlayerState.value.isShuffleEnabled
                            } else {
                                latestInfrequentPlayerState.value.isShuffleEnabled
                            }
                        }
                    }
                    val totalDurationProvider = remember {
                        {
                            if (latestSessionActive.value) latestSurfaceSong.value?.duration ?: 0L
                            else latestInfrequentPlayerState.value.totalDuration
                        }
                    }
                    val lyricsProvider = remember {
                        { latestInfrequentPlayerState.value.lyrics }
                    }
                    val isFavoriteProvider = remember {
                        { latestIsFavorite.value }
                    }
                    val onPlayPause = remember(playerViewModel) { playerViewModel::playPause }
                    val onSeek = remember(playerViewModel) { playerViewModel::seekTo }
                    val onNext = remember(playerViewModel) { playerViewModel::nextSong }
                    val onPrevious = remember(playerViewModel) { playerViewModel::previousSong }
                    val onCollapse = remember(playerViewModel) {
                        { playerViewModel.collapsePlayerSheet() }
                    }
                    val onShuffleToggle = remember(playerViewModel) {
                        { playerViewModel.toggleShuffle() }
                    }
                    val onRepeatToggle = remember(playerViewModel) { playerViewModel::cycleRepeatMode }
                    val onFavoriteToggle = remember(playerViewModel) { playerViewModel::toggleFavorite }

                    FullPlayerContent(
                        currentSong = surfaceSongNonNull,
                        currentPlaybackQueue = currentPlaybackQueue,
                        currentQueueSourceName = currentQueueSourceName,
                        currentMediaItemIndex = infrequentPlayerState.currentMediaItemIndex,
                        isShuffleEnabled = infrequentPlayerState.isShuffleEnabled,
                        shuffleTransitionInProgress = infrequentPlayerState.isShuffleTransitionInProgress,
                        repeatMode = infrequentPlayerState.repeatMode,
                        allowRealtimeUpdates = fullPlayerRuntimePolicy.allowRealtimeUpdates,
                        expansionFractionProvider = expansionFractionProvider,
                        currentSheetState = currentSheetContentState,
                        carouselStyle = carouselStyle,
                        loadingTweaks = fullPlayerLoadingTweaks,
                        isSheetDragGestureActive = isSheetDragGestureActive,
                        playerViewModel = playerViewModel,
                        currentPositionProvider = currentPositionProvider,
                        isPlayingProvider = isPlayingProvider,
                        playWhenReadyProvider = playWhenReadyProvider,
                        repeatModeProvider = repeatModeProvider,
                        isShuffleEnabledProvider = isShuffleEnabledProvider,
                        totalDurationProvider = totalDurationProvider,
                        lyricsProvider = lyricsProvider,
                        isCastConnecting = isCastConnecting,
                        isFavoriteProvider = isFavoriteProvider,
                        onPlayPause = onPlayPause,
                        onSeek = onSeek,
                        onNext = onNext,
                        onPrevious = onPrevious,
                        onCollapse = onCollapse,
                        onShowQueueClicked = onShowQueueClicked,
                        onQueueDragStart = onQueueDragStart,
                        onQueueDrag = onQueueDrag,
                        onQueueRelease = onQueueRelease,
                        onShowCastClicked = onShowCastClicked,
                        onShuffleToggle = onShuffleToggle,
                        onRepeatToggle = onRepeatToggle,
                        onFavoriteToggle = onFavoriteToggle
                    )
                }
            }
        }
    }
}

@OptIn(UnstableApi::class)
@Composable
internal fun UnifiedPlayerPrewarmLayer(
    prewarmFullPlayer: Boolean,
    currentSong: Song?,
    containerHeight: Dp,
    albumColorScheme: ColorScheme,
    currentQueueSourceName: String,
    infrequentPlayerState: StablePlayerState,
    carouselStyle: String,
    fullPlayerLoadingTweaks: FullPlayerLoadingTweaks,
    playerViewModel: PlayerViewModel,
    currentPositionProvider: () -> Long,
    isCastConnecting: Boolean,
    isFavorite: Boolean,
    onShowQueueClicked: () -> Unit,
    onQueueDragStart: () -> Unit,
    onQueueDrag: (Float) -> Unit,
    onQueueRelease: (Float, Float) -> Unit
) {
    // Same display-source swap as the live layer, so the prewarmed surface matches it.
    val isSessionActive by playerViewModel.sessionActive.collectAsStateWithLifecycle()
    val displaySong by playerViewModel.displaySong.collectAsStateWithLifecycle()
    val displayIsPlaying by playerViewModel.displayIsPlaying.collectAsStateWithLifecycle()
    val sessionShuffle by playerViewModel.sessionShuffle.collectAsStateWithLifecycle()
    val sessionRepeat by playerViewModel.sessionRepeat.collectAsStateWithLifecycle()
    val surfaceSong = if (isSessionActive) displaySong else currentSong

    if (prewarmFullPlayer && surfaceSong != null) {
        // Scoped queue collection: the prewarmed FullPlayer owns its own
        // subscription, keeping the queue out of the outer sheet's state.
        val currentPlaybackQueue by playerViewModel.queueFlow
            .collectAsStateWithLifecycle()
        CompositionLocalProvider(
            LocalMaterialTheme provides albumColorScheme
        ) {
            Box(
                modifier = Modifier
                    .height(containerHeight)
                    .fillMaxWidth()
                    .alpha(0f)
                    .clipToBounds()
            ) {
                // Memoize closures the same way the main layer does to avoid creating
                // new lambda instances on every recomposition.
                val latestInfrequentPlayerState = rememberUpdatedState(infrequentPlayerState)
                val latestIsFavorite = rememberUpdatedState(isFavorite)
                val latestDisplayIsPlaying = rememberUpdatedState(displayIsPlaying)
                val latestSessionActive = rememberUpdatedState(isSessionActive)
                val latestSurfaceSong = rememberUpdatedState(surfaceSong)
                val latestSessionShuffle = rememberUpdatedState(sessionShuffle)
                val latestSessionRepeat = rememberUpdatedState(sessionRepeat)
                val isPlayingProvider = remember {
                    {
                        if (latestSessionActive.value) latestDisplayIsPlaying.value
                        else latestInfrequentPlayerState.value.isPlaying
                    }
                }
                val playWhenReadyProvider = remember { { latestInfrequentPlayerState.value.playWhenReady } }
                val repeatModeProvider = remember {
                    {
                        if (latestSessionActive.value) {
                            latestSessionRepeat.value?.toMedia3RepeatMode()
                                ?: latestInfrequentPlayerState.value.repeatMode
                        } else {
                            latestInfrequentPlayerState.value.repeatMode
                        }
                    }
                }
                val isShuffleEnabledProvider = remember {
                    {
                        if (latestSessionActive.value) {
                            latestSessionShuffle.value
                                ?: latestInfrequentPlayerState.value.isShuffleEnabled
                        } else {
                            latestInfrequentPlayerState.value.isShuffleEnabled
                        }
                    }
                }
                val totalDurationProvider = remember {
                    {
                        if (latestSessionActive.value) latestSurfaceSong.value?.duration ?: 0L
                        else latestInfrequentPlayerState.value.totalDuration
                    }
                }
                val lyricsProvider = remember { { latestInfrequentPlayerState.value.lyrics } }
                val isFavoriteProvider = remember { { latestIsFavorite.value } }
                val onPlayPause = remember(playerViewModel) { playerViewModel::playPause }
                val onSeek = remember(playerViewModel) { playerViewModel::seekTo }
                val onNext = remember(playerViewModel) { playerViewModel::nextSong }
                val onPrevious = remember(playerViewModel) { playerViewModel::previousSong }
                val onShuffleToggle = remember(playerViewModel) { { playerViewModel.toggleShuffle() } }
                val onRepeatToggle = remember(playerViewModel) { playerViewModel::cycleRepeatMode }
                val onFavoriteToggle = remember(playerViewModel) { playerViewModel::toggleFavorite }

                FullPlayerContent(
                    currentSong = surfaceSong,
                    currentPlaybackQueue = currentPlaybackQueue,
                    currentQueueSourceName = currentQueueSourceName,
                    currentMediaItemIndex = infrequentPlayerState.currentMediaItemIndex,
                    isShuffleEnabled = infrequentPlayerState.isShuffleEnabled,
                    shuffleTransitionInProgress = infrequentPlayerState.isShuffleTransitionInProgress,
                    repeatMode = infrequentPlayerState.repeatMode,
                    allowRealtimeUpdates = false,
                    expansionFractionProvider = { 1f },
                    currentSheetState = PlayerSheetState.EXPANDED,
                    carouselStyle = carouselStyle,
                    loadingTweaks = fullPlayerLoadingTweaks,
                    playerViewModel = playerViewModel,
                    currentPositionProvider = currentPositionProvider,
                    isPlayingProvider = isPlayingProvider,
                    playWhenReadyProvider = playWhenReadyProvider,
                    repeatModeProvider = repeatModeProvider,
                    isShuffleEnabledProvider = isShuffleEnabledProvider,
                    totalDurationProvider = totalDurationProvider,
                    lyricsProvider = lyricsProvider,
                    isCastConnecting = isCastConnecting,
                    isFavoriteProvider = isFavoriteProvider,
                    onShowQueueClicked = onShowQueueClicked,
                    onQueueDragStart = onQueueDragStart,
                    onQueueDrag = onQueueDrag,
                    onQueueRelease = onQueueRelease,
                    onPlayPause = onPlayPause,
                    onSeek = onSeek,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onCollapse = {},
                    onShowCastClicked = {},
                    onShuffleToggle = onShuffleToggle,
                    onRepeatToggle = onRepeatToggle,
                    onFavoriteToggle = onFavoriteToggle
                )
            }
        }
    }
}
