/**
 * vivimusic Project (C) 2026
 * Licensed under GPL-3.0 | See git history for contributors
 */
package com.music.vivi.ui.player

import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.core.*
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.request.allowHardware
import androidx.media3.common.C
import androidx.media3.common.Player
import com.music.vivi.LocalPlayerConnection
import androidx.compose.ui.graphics.graphicsLayer
import com.music.vivi.constants.CropAlbumArtKey
import com.music.vivi.constants.PlayerBackgroundStyle
import com.music.vivi.constants.PlayerBackgroundStyleKey
import com.music.vivi.constants.ShowPlayerThumbnailShadowKey
import com.music.vivi.constants.PlayerThumbnailShadowElevationKey
import com.music.vivi.constants.EnableGoogleCastKey
import com.music.vivi.constants.HidePlayerThumbnailKey
import com.music.vivi.constants.KeepScreenOn
import com.music.vivi.constants.CrossfadeEnabledKey
import com.music.vivi.constants.CrossfadeDurationKey
import kotlin.math.roundToInt
import com.music.vivi.models.MediaMetadata
import com.music.vivi.ui.component.BottomSheetState
import com.music.vivi.ui.component.PlayerSliderTrack
import com.music.vivi.ui.theme.PlayerSliderColors
import com.music.vivi.utils.makeTimeString
import com.music.vivi.utils.rememberEnumPreference
import com.music.vivi.extensions.togglePlayPause
import com.music.vivi.ui.component.LyricsV2
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.delay
import com.music.vivi.ui.component.LocalBottomSheetPageState
import com.music.vivi.ui.component.LocalMenuState
import com.music.vivi.ui.menu.LyricsMenu
import com.music.vivi.ui.menu.OldPlayerMenu
import com.music.vivi.ui.utils.ShowMediaInfo
import com.music.vivi.ui.utils.ShowOffsetDialog
import com.music.vivi.ui.utils.resize
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.music.vivi.extensions.SwipeGesture
import com.music.vivi.vivimusic.AudioDeviceBottomSheet
import com.music.vivi.vivimusic.getConnectedBluetoothDeviceName
import com.music.vivi.ui.component.CastButton
import com.music.vivi.BuildConfig
import com.music.vivi.R
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.changedToDown
import com.music.vivi.ui.component.Icon as MIcon
import com.music.vivi.utils.rememberPreference
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import android.util.Log
import coil3.request.crossfade
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.luminance
import androidx.datastore.preferences.core.edit
import com.music.vivi.utils.dataStore
import com.music.vivi.utils.get
import com.music.vivi.constants.LastPlayerExpandedKey
import com.music.vivi.constants.LastPlayerInternalStateKey
import com.music.vivi.constants.LyricsKeepControlsKey

enum class PlayerInternalState { COVER, LYRICS, QUEUE }

/** Restauración del estado interno: una sola vez por proceso. */
object PlayerRestore {
    @Volatile
    var internalStateConsumed = false
}

/** Avisa a Player.kt de que las letras están abiertas (para el fondo). */
internal object PlayerV2Backdrop {
    var lyricsOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
}

@OptIn(ExperimentalSharedTransitionApi::class)
@Composable
fun PlayerV2(
    state: BottomSheetState,
    navController: NavController,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
    val playerConnection = LocalPlayerConnection.current ?: return
    val playbackState by playerConnection.playbackState.collectAsState()
    val mediaMetadata by playerConnection.mediaMetadata.collectAsState()
    val isPlaying by playerConnection.isEffectivelyPlaying.collectAsState()
    val currentSong by playerConnection.currentSong.collectAsState(initial = null)
    val currentLyrics by playerConnection.currentLyrics.collectAsState(initial = null)
    val canSkipPrevious by playerConnection.canSkipPrevious.collectAsState()
    val canSkipNext by playerConnection.canSkipNext.collectAsState()
    val menuState = LocalMenuState.current
    val bottomSheetPageState = LocalBottomSheetPageState.current

    // ── 1. Estado interno y restauración ──
    var playerState by rememberSaveable {
        mutableStateOf(
            if (!PlayerRestore.internalStateConsumed) {
                PlayerRestore.internalStateConsumed = true
                if (context.dataStore.get(LastPlayerExpandedKey, false)) {
                    runCatching {
                        PlayerInternalState.valueOf(
                            context.dataStore.get(LastPlayerInternalStateKey, PlayerInternalState.COVER.name)
                        )
                    }.getOrDefault(PlayerInternalState.COVER)
                } else {
                    PlayerInternalState.COVER
                }
            } else {
                PlayerInternalState.COVER
            }
        )
    }
    LaunchedEffect(playerState) {
        context.dataStore.edit { it[LastPlayerInternalStateKey] = playerState.name }
    }
    LaunchedEffect(playerState) {
        PlayerV2Backdrop.lyricsOpen = playerState == PlayerInternalState.LYRICS
        PlayerV2Backdrop.queueOpen = playerState == PlayerInternalState.QUEUE
    }
    DisposableEffect(Unit) {
        onDispose {
            PlayerV2Backdrop.lyricsOpen = false
            PlayerV2Backdrop.queueOpen = false
        }
    }

    // ── 2. Controles que se ocultan (solo LYRICS) ──
    val lyricsKeepControls by rememberPreference(LyricsKeepControlsKey, defaultValue = false)
    var controlsVisible by remember { mutableStateOf(true) }
    var playerCoordinates by remember { mutableStateOf<androidx.compose.ui.layout.LayoutCoordinates?>(null) }
    var lastInteractionTime by remember { mutableLongStateOf(0L) }
    var hideDelayMs by remember { mutableLongStateOf(1500L) }
    var lyricsActiveLine by remember { mutableIntStateOf(0) }
    LaunchedEffect(playerState) { hideDelayMs = 1500L }
    LaunchedEffect(playerState, lyricsKeepControls, lastInteractionTime) {
        if (playerState == PlayerInternalState.LYRICS) {
            controlsVisible = true
            if (!lyricsKeepControls) {
                delay(hideDelayMs)
                // Si hay una línea sonando, esperar a que termine antes de ocultar (máx. 12 s).
                val lineAtStart = lyricsActiveLine
                if (lineAtStart != 0) {
                    kotlinx.coroutines.withTimeoutOrNull(12_000L) {
                        snapshotFlow { lyricsActiveLine }.first { it != lineAtStart }
                    }
                }
                controlsVisible = false
            }
        } else {
            delay(200L)
            controlsVisible = true
        }
    }
    BackHandler(enabled = playerState != PlayerInternalState.COVER) {
        controlsVisible = true
        playerState = PlayerInternalState.COVER
    }

    // ── 8. Cast ──
    val castHandler = remember(playerConnection) {
        try { playerConnection.service.castConnectionHandler } catch (e: Exception) { null }
    }
    val isCasting by castHandler?.isCasting?.collectAsState() ?: remember { mutableStateOf(false) }
    val castPosition by castHandler?.castPosition?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val castDuration by castHandler?.castDuration?.collectAsState() ?: remember { mutableLongStateOf(0L) }
    val castIsPlaying by castHandler?.castIsPlaying?.collectAsState() ?: remember { mutableStateOf(false) }
    val castIsBuffering by castHandler?.castIsBuffering?.collectAsState() ?: remember { mutableStateOf(false) }
    val castVolume by castHandler?.castVolume?.collectAsState() ?: remember { mutableFloatStateOf(1f) }
    val effectiveIsPlaying = if (isCasting) castIsPlaying else isPlaying
    var lastManualSeekTime by remember { mutableLongStateOf(0L) }

    // ── 5. Posición y duración ──
    var position by remember {
        mutableLongStateOf(
            try { playerConnection.player.currentPosition.coerceAtLeast(0L) } catch (_: Exception) { 0L }
        )
    }
    var duration by remember {
        mutableLongStateOf(
            try {
                val raw = playerConnection.player.duration
                if (raw == C.TIME_UNSET || raw < 0) 0L else raw
            } catch (_: Exception) { 0L }
        )
    }
    var sliderPosition by remember { mutableStateOf<Long?>(null) }

    LaunchedEffect(isPlaying, isCasting) {
        if (!isCasting && isPlaying) {
            while (isActive) {
                delay(100)
                if (sliderPosition == null) {
                    val raw = playerConnection.player.duration
                    position = playerConnection.player.currentPosition.coerceAtLeast(0L)
                    duration = if (raw == C.TIME_UNSET || raw < 0) 0L else raw
                }
            }
        }
    }
    LaunchedEffect(playbackState, mediaMetadata?.id) {
        if (!isCasting) {
            val raw = playerConnection.player.duration
            position = playerConnection.player.currentPosition.coerceAtLeast(0L)
            duration = if (raw == C.TIME_UNSET || raw < 0) {
                // Cola restaurándose: la duración aún no es conocida
                (mediaMetadata?.duration ?: 0).toLong() * 1000L
            } else {
                raw
            }
        }
    }
    LaunchedEffect(isCasting, castPosition, castDuration) {
        if (isCasting && sliderPosition == null) {
            if (System.currentTimeMillis() - lastManualSeekTime > 1500) {
                position = castPosition
                if (castDuration > 0) duration = castDuration
            }
        }
    }

    // ── Volumen del sistema ──
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    val maxSystemVolume = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
    var systemVolume by remember {
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxSystemVolume)
    }
    val animatedVolume by animateFloatAsState(
        targetValue = systemVolume,
        animationSpec = tween(durationMillis = 150, easing = FastOutLinearInEasing),
        label = "volumeAnimation"
    )
    DisposableEffect(context) {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context?, intent: Intent?) {
                if (intent?.action == "android.media.VOLUME_CHANGED_ACTION") {
                    systemVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat() / maxSystemVolume
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter("android.media.VOLUME_CHANGED_ACTION"))
        onDispose { context.unregisterReceiver(receiver) }
    }

    // ── 6. Colores, fondo y preferencias ──
    val playerBackground by rememberEnumPreference(
        key = PlayerBackgroundStyleKey,
        defaultValue = PlayerBackgroundStyle.GRADIENT
    )
    val useDarkTheme = MaterialTheme.colorScheme.surface.luminance() < 0.5f
    val isDefaultBackground = playerBackground == PlayerBackgroundStyle.DEFAULT

    val adaptivePrimary by animateColorAsState(
        targetValue = if (isDefaultBackground) MaterialTheme.colorScheme.onSurface else Color.White,
        label = "adaptivePrimary"
    )
    val adaptiveSecondary by animateColorAsState(
        targetValue = if (isDefaultBackground) MaterialTheme.colorScheme.onSurfaceVariant
        else Color.White.copy(alpha = 0.7f),
        label = "adaptiveSecondary"
    )
    val adaptiveSurface by animateColorAsState(
        targetValue = if (isDefaultBackground) MaterialTheme.colorScheme.surfaceVariant
        else Color.White.copy(alpha = 0.2f),
        label = "adaptiveSurface"
    )
    val coverFadeStrength by animateFloatAsState(
        targetValue = if (playerState == PlayerInternalState.COVER) 1f else 0f,
        animationSpec = tween(500, easing = FastOutSlowInEasing),
        label = "coverFadeStrength"
    )

    val cropAlbumArt by rememberPreference(CropAlbumArtKey, false)
    val showPlayerThumbnailShadow by rememberPreference(ShowPlayerThumbnailShadowKey, defaultValue = false)
    val playerThumbnailShadowElevation by rememberPreference(PlayerThumbnailShadowElevationKey, defaultValue = 8f)
    val enableGoogleCast by rememberPreference(EnableGoogleCastKey, defaultValue = true)
    val hidePlayerThumbnail by rememberPreference(HidePlayerThumbnailKey, false)
    val isKeepScreenOn by rememberPreference(KeepScreenOn, false)

    DisposableEffect(playerState, isPlaying, isKeepScreenOn) {
        val window = (context as? Activity)?.window
        if (playerState == PlayerInternalState.LYRICS || (isPlaying && isKeepScreenOn)) {
            window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
        onDispose { window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
    }

    // ── 7. AutoMix / crossfade de portada ──
    val crossfadeEnabled by rememberPreference(CrossfadeEnabledKey, defaultValue = false)
    val crossfadeDuration by rememberPreference(CrossfadeDurationKey, defaultValue = 5f)
    val isCrossfading by playerConnection.isCrossfading.collectAsState()
    val crossfadeOutgoingItem by playerConnection.crossfadeOutgoingItem.collectAsState()
    val crossfadeIncomingItem by playerConnection.crossfadeIncomingItem.collectAsState()
    val crossfadeProgress by playerConnection.crossfadeProgress.collectAsState()
    val nextMediaMetadata by playerConnection.nextMediaMetadata.collectAsState()
    val coverProgress by animateFloatAsState(
        targetValue = if (isCrossfading) crossfadeProgress else 0f,
        animationSpec = tween(durationMillis = 150, easing = LinearEasing),
        label = "coverProgress"
    )
    val displayedMetadata =
        if (isCrossfading && crossfadeOutgoingItem != null) crossfadeOutgoingItem else mediaMetadata
    val fromCoverUrl =
        if (isCrossfading && crossfadeOutgoingItem != null) crossfadeOutgoingItem?.thumbnailUrl
        else mediaMetadata?.thumbnailUrl
    val toCoverUrl =
        if (isCrossfading) crossfadeIncomingItem?.thumbnailUrl ?: nextMediaMetadata?.thumbnailUrl
        else null

    // ── 8b. Dispositivo de audio (Bluetooth) ──
    var showAudioDeviceBottomSheet by remember { mutableStateOf(false) }
    val bluetoothDeviceName by produceState<String?>(initialValue = getConnectedBluetoothDeviceName(context)) {
        val am = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val scope = this
        val updateDeviceState: () -> Unit = {
            scope.value = getConnectedBluetoothDeviceName(context)
            scope.launch {
                delay(500)
                scope.value = getConnectedBluetoothDeviceName(context)
            }
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, intent: Intent) { updateDeviceState() }
        }
        val callback = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            object : AudioDeviceCallback() {
                override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>?) { updateDeviceState() }
                override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>?) { updateDeviceState() }
            }
        } else null
        val filter = IntentFilter().apply {
            addAction(AudioManager.ACTION_HEADSET_PLUG)
            addAction("android.bluetooth.adapter.action.STATE_CHANGED")
            addAction("android.bluetooth.device.action.ACL_CONNECTED")
            addAction("android.bluetooth.device.action.ACL_DISCONNECTED")
            addAction("android.media.AUDIO_BECOMING_NOISY")
        }
        context.registerReceiver(receiver, filter)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && callback != null) {
            am.registerAudioDeviceCallback(callback, Handler(Looper.getMainLooper()))
        }
        awaitDispose {
            context.unregisterReceiver(receiver)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M && callback != null) {
                am.unregisterAudioDeviceCallback(callback)
            }
        }
    }

    // ── 3b. Botones ⭐ (Me gusta) y ⋮ (Opciones) ──
    @Composable
    fun ActionButtons(inCover: Boolean, modifier: Modifier = Modifier) {
        val isLiked = currentSong?.song?.liked == true
        val circle = if (inCover) {
            Modifier.size(40.dp).clip(CircleShape).background(adaptivePrimary.copy(alpha = 0.1f))
        } else {
            Modifier.size(40.dp)
        }
        Row(modifier = modifier, verticalAlignment = Alignment.CenterVertically) {
            Box(modifier = circle, contentAlignment = Alignment.Center) {
                IconButton(onClick = playerConnection::toggleLike, modifier = Modifier.size(40.dp)) {
                    Icon(
                        painter = painterResource(if (isLiked) R.drawable.star_filled_liked else R.drawable.star_like),
                        contentDescription = "Like",
                        tint = adaptivePrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
            if (inCover) Spacer(modifier = Modifier.width(8.dp))
            Box(modifier = circle, contentAlignment = Alignment.Center) {
                IconButton(
                    onClick = {
                        if (playerState == PlayerInternalState.LYRICS) {
                            menuState.show {
                                LyricsMenu(
                                    lyricsProvider = { currentLyrics },
                                    songProvider = { currentSong?.song },
                                    mediaMetadataProvider = { mediaMetadata!! },
                                    onDismiss = menuState::dismiss,
                                    onShowOffsetDialog = {
                                        bottomSheetPageState.show {
                                            ShowOffsetDialog(songProvider = { currentSong?.song })
                                        }
                                    }
                                )
                            }
                        } else {
                            menuState.show {
                                OldPlayerMenu(
                                    mediaMetadata = mediaMetadata,
                                    navController = navController,
                                    playerBottomSheetState = state,
                                    onShowDetailsDialog = {
                                        mediaMetadata?.id?.let { bottomSheetPageState.show { ShowMediaInfo(it) } }
                                    },
                                    onDismiss = menuState::dismiss
                                )
                            }
                        }
                    },
                    modifier = Modifier.size(40.dp)
                ) {
                    Icon(
                        painter = painterResource(if (inCover) R.drawable.more_horiz else R.drawable.more_vert),
                        contentDescription = "Options",
                        tint = adaptivePrimary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }

    // ── Carátula con degradado inferior estilo Natune ──
    @Composable
    fun CoverArt(corner: Dp, modifier: Modifier = Modifier, showWithAppleMusic: Boolean = false) {
        Box(
            modifier = modifier
                .clip(RoundedCornerShape(corner))
                .then(if (isDefaultBackground) Modifier.background(adaptiveSurface) else Modifier)
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(
                        brush = Brush.verticalGradient(
                            0.62f to Color.Black,
                            1f to Color.Black.copy(alpha = 1f - 0.55f * coverFadeStrength)
                        ),
                        blendMode = BlendMode.DstIn
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (hidePlayerThumbnail) {
                Icon(
                    painter = painterResource(R.drawable.vivi_music_small_icon),
                    contentDescription = null,
                    tint = adaptivePrimary.copy(alpha = 0.5f),
                    modifier = Modifier.size(72.dp)
                )
            } else if (showWithAppleMusic || playerBackground != PlayerBackgroundStyle.APPLE_MUSIC) {
                // Con el fondo Apple Music la carátula grande la dibuja el fondo (Player.kt);
                // la pequeña de la cabecera de letras sí se dibuja (showWithAppleMusic).
                Blendingimage(
                    fromCoverUrl = fromCoverUrl,
                    toCoverUrl = toCoverUrl,
                    nextCoverUrl = nextMediaMetadata?.thumbnailUrl,
                    progress = coverProgress,
                    crossfadeDuration = crossfadeDuration,
                    crossfadeEnabled = crossfadeEnabled,
                    cropAlbumArt = cropAlbumArt,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    val boundsTransform = BoundsTransform { _, _ -> tween(500, easing = FastOutSlowInEasing) }

    // ── 3. Layout ──
    Box(
        modifier = modifier
            .fillMaxSize()
            .onGloballyPositioned { playerCoordinates = it }
            .pointerInput(Unit) {
                awaitPointerEventScope {
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        if (event.changes.any { it.changedToDown() }) {
                            if (playerState == PlayerInternalState.LYRICS) {
                                hideDelayMs = 2000L
                                lastInteractionTime = System.currentTimeMillis()
                            }
                            controlsVisible = true
                        }
                    }
                }
            }
    ) {
        if (isLandscape) {
            PlayerV2Landscape(state = state, navController = navController)
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.systemBars.only(WindowInsetsSides.Vertical)),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Asa
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { state.collapseSoft() }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(width = 48.dp, height = 6.dp)
                            .clip(RoundedCornerShape(3.dp))
                            .background(adaptivePrimary.copy(alpha = 0.3f))
                    )
                }

                Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    SharedTransitionLayout {
                        AnimatedContent(
                            targetState = playerState,
                            transitionSpec = {
                                (fadeIn(tween(350, easing = FastOutSlowInEasing)) togetherWith
                                    fadeOut(tween(250, easing = FastOutSlowInEasing)))
                                    .apply { targetContentZIndex = 1f }
                            },
                            label = "PlayerV2State"
                        ) { target ->
                            if (target == PlayerInternalState.COVER) {
                                Column(
                                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp)
                                ) {
                                    Spacer(modifier = Modifier.weight(1f))
                                    CoverArt(
                                        corner = 12.dp,
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .aspectRatio(1f)
                                            .customSoftShadow(
                                                elevation = playerThumbnailShadowElevation.dp,
                                                cornerRadius = 12.dp,
                                                enabled = showPlayerThumbnailShadow
                                            )
                                            .sharedBounds(
                                                sharedContentState = rememberSharedContentState("coverArt"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = boundsTransform
                                            )
                                            .SwipeGesture(
                                                enabled = true,
                                                onSwipeLeft = { if (canSkipNext) playerConnection.seekToNext() },
                                                onSwipeRight = { if (canSkipPrevious) playerConnection.seekToPrevious() }
                                            )
                                    )
                                    Spacer(modifier = Modifier.height(20.dp))
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Column(modifier = Modifier.weight(1f)) {
                                            Text(
                                                text = displayedMetadata?.title.orEmpty(),
                                                style = MaterialTheme.typography.headlineSmall,
                                                fontWeight = FontWeight.SemiBold,
                                                color = adaptivePrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .sharedBounds(
                                                        sharedContentState = rememberSharedContentState("title"),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = boundsTransform
                                                    )
                                                    .clickable {
                                                        displayedMetadata?.album?.id?.let {
                                                            state.collapseSoft()
                                                            navController.navigate("album/$it")
                                                        }
                                                    }
                                            )
                                            Text(
                                                text = displayedMetadata?.artists?.joinToString { it.name }.orEmpty(),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = adaptiveSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier
                                                    .sharedBounds(
                                                        sharedContentState = rememberSharedContentState("artist"),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = boundsTransform
                                                    )
                                                    .clickable {
                                                        displayedMetadata?.artists?.firstOrNull()?.id?.let {
                                                            state.collapseSoft()
                                                            navController.navigate("artist/$it")
                                                        }
                                                    }
                                            )
                                        }
                                        ActionButtons(
                                            inCover = true,
                                            modifier = Modifier.sharedBounds(
                                                sharedContentState = rememberSharedContentState("actionButtons"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                boundsTransform = boundsTransform
                                            )
                                        )
                                    }
                                    Spacer(modifier = Modifier.height(16.dp))
                                }
                            } else {
                                Column(modifier = Modifier.fillMaxSize()) {
                                    // Mini‑cabecera
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(horizontal = 24.dp, vertical = 8.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        CoverArt(
                                            corner = 8.dp,
                                            showWithAppleMusic = true,
                                            modifier = Modifier
                                                .size(64.dp)
                                                // Con fondo Apple Music la carátula pequeña aparece sin viajar desde el centro
                                                .then(
                                                    if (playerBackground == PlayerBackgroundStyle.APPLE_MUSIC) Modifier
                                                    else Modifier.sharedBounds(
                                                        sharedContentState = rememberSharedContentState("coverArt"),
                                                        animatedVisibilityScope = this@AnimatedContent,
                                                        boundsTransform = boundsTransform
                                                    )
                                                )
                                                .clickable { playerState = PlayerInternalState.COVER }
                                        )
                                        Column(modifier = Modifier.weight(1f).padding(horizontal = 12.dp)) {
                                            Text(
                                                text = displayedMetadata?.title.orEmpty(),
                                                style = MaterialTheme.typography.titleMedium,
                                                color = adaptivePrimary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.sharedBounds(
                                                    sharedContentState = rememberSharedContentState("title"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    enter = fadeIn(tween(400)),
                                                    exit = fadeOut(tween(300)),
                                                    boundsTransform = boundsTransform
                                                )
                                            )
                                            Text(
                                                text = displayedMetadata?.artists?.joinToString { it.name }.orEmpty(),
                                                style = MaterialTheme.typography.titleSmall,
                                                color = adaptiveSecondary,
                                                maxLines = 1,
                                                overflow = TextOverflow.Ellipsis,
                                                modifier = Modifier.sharedBounds(
                                                    sharedContentState = rememberSharedContentState("artist"),
                                                    animatedVisibilityScope = this@AnimatedContent,
                                                    enter = fadeIn(tween(400)),
                                                    exit = fadeOut(tween(300)),
                                                    boundsTransform = boundsTransform
                                                )
                                            )
                                        }
                                        ActionButtons(
                                            inCover = false,
                                            modifier = Modifier.sharedBounds(
                                                sharedContentState = rememberSharedContentState("actionButtons"),
                                                animatedVisibilityScope = this@AnimatedContent,
                                                enter = fadeIn(tween(400)),
                                                exit = fadeOut(tween(300)),
                                                boundsTransform = boundsTransform
                                            )
                                        )
                                    }
                                    // Contenido: Letras o Cola
                                    Box(
                                        modifier = Modifier
                                            .weight(1f)
                                            .fillMaxWidth()
                                            .animateEnterExit(
                                                enter = fadeIn(tween(350)),
                                                exit = fadeOut(tween(250))
                                            )
                                    ) {
                                        if (target == PlayerInternalState.LYRICS) {
                                            LyricsV2(
                                                mediaMetadata = mediaMetadata,
                                                showLyrics = true,
                                                positionProvider = { sliderPosition ?: position },
                                                sliderPositionProvider = { sliderPosition },
                                                modifier = Modifier.fillMaxSize(),
                                                playerCoordinates = playerCoordinates,
                                                onControlsVisibilityChange = { controlsVisible = it },
                                                controlsVisible = controlsVisible,
                                                onActiveLineChange = { lyricsActiveLine = it }
                                            )
                                        } else {
                                            QueueV2(
                                                navController = navController,
                                                playerBottomSheetState = state,
                                                modifier = Modifier.fillMaxSize(),
                                                onControlsVisibilityChange = { controlsVisible = it }
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                // ── 4. Barra de controles (parte inferior) ──
                AnimatedVisibility(
                    visible = controlsVisible,
                    enter = fadeIn(tween(400)) + slideInVertically(tween(400)) { it / 3 },
                    exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { it / 3 }
                ) {
                    val sliderColors = PlayerSliderColors.getSliderColors(
                        activeColor = adaptivePrimary.copy(alpha = 0.8f),
                        playerBackground = playerBackground,
                        useDarkTheme = useDarkTheme
                    )
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // Slider de tiempo
                        val currentPos = sliderPosition ?: position
                        val timeInteraction = remember { MutableInteractionSource() }
                        val isTimeDragged by timeInteraction.collectIsDraggedAsState()
                        val isTimePressed by timeInteraction.collectIsPressedAsState()
                        val timeTrackHeight by animateDpAsState(
                            targetValue = if (isTimeDragged || isTimePressed) 14.dp else 8.dp,
                            animationSpec = spring(
                                dampingRatio = Spring.DampingRatioNoBouncy,
                                stiffness = Spring.StiffnessMedium
                            ),
                            label = "timeTrackHeight"
                        )
                        Slider(
                            value = currentPos.toFloat(),
                            valueRange = 0f..(if (duration <= 0L) 0f else duration.toFloat()),
                            onValueChange = { sliderPosition = it.toLong() },
                            onValueChangeFinished = {
                                sliderPosition?.let { pos ->
                                    if (duration > 0L) {
                                        if (isCasting) {
                                            castHandler?.seekTo(pos)
                                            lastManualSeekTime = System.currentTimeMillis()
                                        } else {
                                            playerConnection.player.seekTo(pos)
                                        }
                                        position = pos
                                    }
                                }
                                sliderPosition = null
                            },
                            interactionSource = timeInteraction,
                            thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                            track = { sliderState ->
                                PlayerSliderTrack(
                                    sliderState = sliderState,
                                    trackHeight = timeTrackHeight,
                                    colors = sliderColors
                                )
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(28.dp)
                        )
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = makeTimeString(currentPos),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = adaptiveSecondary
                            )
                            Text(
                                text = "-" + makeTimeString((duration - currentPos).coerceAtLeast(0L)),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = adaptiveSecondary
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Anterior · Play/Pausa · Siguiente
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            IconButton(
                                onClick = {
                                    if (isCasting) castHandler?.skipToPrevious()
                                    else playerConnection.seekToPrevious()
                                },
                                enabled = canSkipPrevious,
                                modifier = Modifier
                                    .size(64.dp)
                                    .alpha(if (canSkipPrevious) 1f else 0.4f)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.apple_skip_previous),
                                    contentDescription = "Previous",
                                    tint = adaptivePrimary,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (isCasting) {
                                        if (castIsPlaying) castHandler?.pause() else castHandler?.play()
                                    } else {
                                        playerConnection.player.togglePlayPause()
                                    }
                                },
                                modifier = Modifier.size(88.dp)
                            ) {
                                Icon(
                                    painter = painterResource(
                                        if (effectiveIsPlaying) R.drawable.pause_applemusic else R.drawable.play_applemusic
                                    ),
                                    contentDescription = if (effectiveIsPlaying) "Pause" else "Play",
                                    tint = adaptivePrimary,
                                    modifier = Modifier.size(80.dp)
                                )
                            }
                            IconButton(
                                onClick = {
                                    if (isCasting) castHandler?.skipToNext()
                                    else playerConnection.seekToNext()
                                },
                                enabled = canSkipNext,
                                modifier = Modifier
                                    .size(64.dp)
                                    .alpha(if (canSkipNext) 1f else 0.4f)
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.apple_skip_next),
                                    contentDescription = "Next",
                                    tint = adaptivePrimary,
                                    modifier = Modifier.size(44.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        // Volumen
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(36.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_speaker_24dp),
                                contentDescription = "Volume Down",
                                tint = adaptiveSecondary,
                                modifier = Modifier.size(18.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            val volumeInteraction = remember { MutableInteractionSource() }
                            val isVolDragged by volumeInteraction.collectIsDraggedAsState()
                            val isVolPressed by volumeInteraction.collectIsPressedAsState()
                            val isVolActive = isVolDragged || isVolPressed
                            val volumeTrackHeight by animateDpAsState(
                                targetValue = if (isVolActive) 12.dp else 6.dp,
                                animationSpec = spring(
                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                    stiffness = Spring.StiffnessLow
                                ),
                                label = "volumeTrackHeight"
                            )
                            Slider(
                                value = if (isCasting) castVolume
                                else if (isVolActive) systemVolume
                                else animatedVolume,
                                onValueChange = { newValue ->
                                    if (isCasting) {
                                        castHandler?.setVolume(newValue)
                                    } else {
                                        systemVolume = newValue
                                        audioManager.setStreamVolume(
                                            AudioManager.STREAM_MUSIC,
                                            (newValue * maxSystemVolume).toInt(),
                                            0
                                        )
                                    }
                                },
                                interactionSource = volumeInteraction,
                                thumb = { Spacer(modifier = Modifier.size(0.dp)) },
                                track = { sliderState ->
                                    PlayerSliderTrack(
                                        sliderState = sliderState,
                                        trackHeight = volumeTrackHeight,
                                        colors = sliderColors
                                    )
                                },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(24.dp)
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Icon(
                                painter = painterResource(R.drawable.ic_speaker_wave_3_24dp),
                                contentDescription = "Volume Up",
                                tint = adaptiveSecondary,
                                modifier = Modifier.size(20.dp)
                            )
                        }

                        // Utilidades: Letras · Dispositivo/Cast · Cola
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 24.dp),
                            horizontalArrangement = Arrangement.SpaceEvenly,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val isLyricsActive = playerState == PlayerInternalState.LYRICS
                            IconButton(
                                onClick = {
                                    playerState = if (isLyricsActive) PlayerInternalState.COVER
                                    else PlayerInternalState.LYRICS
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isLyricsActive) adaptivePrimary.copy(alpha = 0.2f) else Color.Transparent
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.lyrics_apple),
                                    contentDescription = "Lyrics",
                                    tint = if (isLyricsActive) adaptivePrimary else adaptiveSecondary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }

                            // Centro: Bluetooth / Cast / Dispositivo de audio
                            Box(
                                modifier = Modifier.size(48.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                if (bluetoothDeviceName != null) {
                                    IconButton(
                                        onClick = { showAudioDeviceBottomSheet = true },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.headphones_apple),
                                            contentDescription = "Audio Device",
                                            tint = adaptivePrimary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                    Text(
                                        text = bluetoothDeviceName.orEmpty(),
                                        style = MaterialTheme.typography.labelSmall.copy(fontSize = 10.sp),
                                        color = adaptiveSecondary,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis,
                                        textAlign = TextAlign.Center,
                                        modifier = Modifier
                                            .wrapContentWidth(unbounded = true)
                                            .widthIn(max = 84.dp)
                                            .offset(y = 30.dp)
                                    )
                                } else if (BuildConfig.CAST_AVAILABLE && enableGoogleCast) {
                                    CastButton(
                                        modifier = Modifier.size(48.dp),
                                        tintColor = adaptiveSecondary,
                                        activeTintColor = adaptivePrimary,
                                        showBackground = false,
                                        customIconRes = R.drawable.airplay_audio
                                    )
                                } else {
                                    IconButton(
                                        onClick = { showAudioDeviceBottomSheet = true },
                                        modifier = Modifier.size(48.dp)
                                    ) {
                                        Icon(
                                            painter = painterResource(R.drawable.airplay_audio),
                                            contentDescription = "Audio Routing",
                                            tint = adaptiveSecondary,
                                            modifier = Modifier.size(28.dp)
                                        )
                                    }
                                }
                            }

                            val isQueueActive = playerState == PlayerInternalState.QUEUE
                            IconButton(
                                onClick = {
                                    playerState = if (isQueueActive) PlayerInternalState.COVER
                                    else PlayerInternalState.QUEUE
                                },
                                modifier = Modifier
                                    .size(48.dp)
                                    .clip(CircleShape)
                                    .background(
                                        if (isQueueActive) adaptivePrimary.copy(alpha = 0.2f) else Color.Transparent
                                    )
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.apple_queue),
                                    contentDescription = "Queue",
                                    tint = if (isQueueActive) adaptivePrimary else adaptiveSecondary,
                                    modifier = Modifier.size(28.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    if (showAudioDeviceBottomSheet) {
        AudioDeviceBottomSheet(onDismiss = { showAudioDeviceBottomSheet = false })
    }
}
