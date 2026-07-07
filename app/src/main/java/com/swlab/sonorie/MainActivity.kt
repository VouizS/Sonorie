package com.swlab.sonorie

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.provider.MediaStore
import android.support.v4.media.MediaMetadataCompat
import android.support.v4.media.session.MediaSessionCompat
import android.support.v4.media.session.PlaybackStateCompat
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import java.util.concurrent.TimeUnit
import kotlin.random.Random
import kotlinx.coroutines.delay
import androidx.compose.ui.draw.alpha
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures./* /* /* detectVerticalDragGestures removed */ removed */ removed */
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Search
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.ui.graphics.Color

const val SONORIE_ACTION_PLAY = "com.swlab.sonorie.action.PLAY"
const val SONORIE_ACTION_TOGGLE = "com.swlab.sonorie.action.TOGGLE"
const val SONORIE_ACTION_NEXT = "com.swlab.sonorie.action.NEXT"
const val SONORIE_ACTION_PREVIOUS = "com.swlab.sonorie.action.PREVIOUS"
const val SONORIE_ACTION_STOP = "com.swlab.sonorie.action.STOP"
const val EXTRA_SONG_ID = "extra_song_id"
const val EXTRA_SONG_TITLE = "extra_song_title"
const val EXTRA_SONG_ARTIST = "extra_song_artist"
const val EXTRA_SONG_ALBUM = "extra_song_album"
const val EXTRA_SONG_DURATION = "extra_song_duration"
const val EXTRA_SONG_URI = "extra_song_uri"
const val EXTRA_SONG_ALBUM_ART = "extra_song_album_art"
const val SONORIE_NOTIFICATION_CHANNEL = "sonorie_playback"
const val SONORIE_NOTIFICATION_ID = 2040
private const val SONORIE_PREFS = "sonorie_prefs"
private const val SONORIE_FAVORITES_KEY = "favorite_song_ids"
private const val SONORIE_SHUFFLE_KEY = "shuffle_enabled"
private const val SONORIE_REPEAT_KEY = "repeat_mode"
private const val SONORIE_THEME_KEY = "theme_preference"
private const val SONORIE_TASTE_READY_KEY = "taste_ready"
private const val SONORIE_FAVORITE_ARTISTS_KEY = "favorite_artist_names"
private const val SONORIE_FAVORITE_GENRES_KEY = "favorite_genre_names"

@Immutable
data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri? = null
)

enum class SonorieTab { Home, Library, Player, Settings }
enum class RepeatMode { Off, All, One }
enum class ThemePreference { System, Light, Dark }

object SonoriePlaybackState {
    var currentSong by mutableStateOf<Song?>(null)
    var isPlaying by mutableStateOf(false)
    var positionMs by mutableStateOf(0L)
    var eventVersion by mutableStateOf(0)
    fun update(song: Song?, playing: Boolean, position: Long) {
        currentSong = song
        isPlaying = playing
        positionMs = position.coerceAtLeast(0L)
        eventVersion += 1
    }
}

class SonoriePlaybackService : Service() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var currentSong: Song? = null
    private var currentIndex = -1
    private var cachedSongs: List<Song> = emptyList()
    private var isPlayingState = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        player = ExoPlayer.Builder(this).build().apply {
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_ENDED) handleEnded()
                }
            })
        }
        mediaSession = MediaSessionCompat(this, "SonoriePlaybackSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() { if (!isPlayingState) togglePlayback() }
                override fun onPause() { if (isPlayingState) pausePlayback() }
                override fun onSkipToNext() { playNext() }
                override fun onSkipToPrevious() { playPrevious() }
                override fun onStop() { stopPlayback() }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SONORIE_ACTION_PLAY -> playSong(songFromIntent(intent))
            SONORIE_ACTION_TOGGLE -> togglePlayback()
            SONORIE_ACTION_NEXT -> playNext()
            SONORIE_ACTION_PREVIOUS -> playPrevious()
            SONORIE_ACTION_STOP -> stopPlayback()
            else -> currentSong?.let { showNotification(it) }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        player?.release()
        super.onDestroy()
    }

    private fun songFromIntent(intent: Intent): Song {
        val id = intent.getLongExtra(EXTRA_SONG_ID, -1L)
        val uri = intent.getStringExtra(EXTRA_SONG_URI)?.let { Uri.parse(it) }
            ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        return Song(
            id = id,
            title = intent.getStringExtra(EXTRA_SONG_TITLE) ?: "Sem título",
            artist = intent.getStringExtra(EXTRA_SONG_ARTIST) ?: "Artista desconhecido",
            album = intent.getStringExtra(EXTRA_SONG_ALBUM) ?: "Álbum desconhecido",
            durationMs = intent.getLongExtra(EXTRA_SONG_DURATION, 0L),
            uri = uri,
            albumArtUri = intent.getStringExtra(EXTRA_SONG_ALBUM_ART)?.let { Uri.parse(it) }
        )
    }

    private fun playSong(song: Song) {
        currentSong = song
        refreshSongCache()
        currentIndex = cachedSongs.indexOfFirst { it.id == song.id }
        isPlayingState = true
        player?.setMediaItem(MediaItem.fromUri(song.uri))
        player?.prepare()
        player?.play()
        forceSystemPlaybackRefresh()
        showNotification(song)
    }

    private fun togglePlayback() {
        val song = currentSong ?: return
        if (isPlayingState) pausePlayback() else {
            isPlayingState = true
            player?.play()
            forceSystemPlaybackRefresh()
            showNotification(song)
        }
    }

    private fun pausePlayback() {
        val song = currentSong ?: return
        isPlayingState = false
        player?.pause()
        forceSystemPlaybackRefresh()
        showNotification(song)
    }

    private fun playNext() {
        refreshSongCache()
        if (cachedSongs.isEmpty()) return
        val nextIndex = if (loadShuffleEnabled(this) && cachedSongs.size > 1) {
            var n = Random.nextInt(cachedSongs.size)
            while (n == currentIndex) n = Random.nextInt(cachedSongs.size)
            n
        } else if (currentIndex >= 0) {
            (currentIndex + 1) % cachedSongs.size
        } else 0
        playSong(cachedSongs[nextIndex])
    }

    private fun playPrevious() {
        refreshSongCache()
        if (cachedSongs.isEmpty()) return
        val previousIndex = if (currentIndex > 0) currentIndex - 1 else cachedSongs.lastIndex
        playSong(cachedSongs[previousIndex])
    }

    private fun handleEnded() {
        when (loadRepeatMode(this)) {
            RepeatMode.One -> {
                player?.seekTo(0L)
                player?.play()
                isPlayingState = true
                forceSystemPlaybackRefresh()
                currentSong?.let { showNotification(it) }
            }
            RepeatMode.All -> playNext()
            RepeatMode.Off -> {
                refreshSongCache()
                if (currentIndex in 0 until cachedSongs.lastIndex) playNext() else {
                    isPlayingState = false
                    forceSystemPlaybackRefresh()
                    currentSong?.let { showNotification(it) }
                }
            }
        }
    }

    private fun stopPlayback() {
        isPlayingState = false
        player?.stop()
        syncUiState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshSongCache() {
        if (cachedSongs.isEmpty()) cachedSongs = loadLocalSongs(this)
    }

    private fun syncUiState() {
        SonoriePlaybackState.update(currentSong, isPlayingState, player?.currentPosition ?: 0L)
    }

    private fun updateMetadata(song: Song) {
        val builder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)
        song.albumArtUri?.let {
            builder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString())
            builder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it.toString())
        }
        mediaSession?.setMetadata(builder.build())
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val state = if (isPlaying) PlaybackStateCompat.STATE_PLAYING else PlaybackStateCompat.STATE_PAUSED
        mediaSession?.setPlaybackState(
            PlaybackStateCompat.Builder()
                .setActions(
                    PlaybackStateCompat.ACTION_PLAY or
                        PlaybackStateCompat.ACTION_PAUSE or
                        PlaybackStateCompat.ACTION_PLAY_PAUSE or
                        PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                        PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                        PlaybackStateCompat.ACTION_STOP
                )
                .setState(state, player?.currentPosition ?: 0L, 1f)
                .build()
        )
    }

    private fun forceSystemPlaybackRefresh() {
        currentSong?.let { updateMetadata(it) }
        updatePlaybackState(isPlayingState)
        syncUiState()
    }

    private fun showNotification(song: Song) {
        startForeground(SONORIE_NOTIFICATION_ID, buildNotification(song))
    }

    private fun buildNotification(song: Song): android.app.Notification {
        val openApp = PendingIntent.getActivity(
            this, 200, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val playPauseIcon = if (isPlayingState) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        return NotificationCompat.Builder(this, SONORIE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sonorie)
            .setContentTitle(song.title)
            .setContentText(song.artist.ifBlank { "Sonorie" })
            .setSubText("Sonorie")
            .setContentIntent(openApp)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlayingState)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", servicePendingIntent(SONORIE_ACTION_PREVIOUS, 201))
            .addAction(playPauseIcon, if (isPlayingState) "Pausar" else "Tocar", servicePendingIntent(SONORIE_ACTION_TOGGLE, 202))
            .addAction(android.R.drawable.ic_media_next, "Próxima", servicePendingIntent(SONORIE_ACTION_NEXT, 203))
            .setDeleteIntent(servicePendingIntent(SONORIE_ACTION_STOP, 204))
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        return PendingIntent.getService(
            this, requestCode, Intent(this, SonoriePlaybackService::class.java).apply { this.action = action },
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(
                    SONORIE_NOTIFICATION_CHANNEL,
                    "Reprodução do Sonorie",
                    NotificationManager.IMPORTANCE_LOW
                ).apply {
                    description = "Controles de reprodução de música do Sonorie"
                    setShowBadge(false)
                }
            )
        }
    }
}

fun sendPlaybackAction(context: Context, action: String, song: Song? = null) {
    val intent = Intent(context, SonoriePlaybackService::class.java).apply {
        this.action = action
        song?.let {
            putExtra(EXTRA_SONG_ID, it.id)
            putExtra(EXTRA_SONG_TITLE, it.title)
            putExtra(EXTRA_SONG_ARTIST, it.artist)
            putExtra(EXTRA_SONG_ALBUM, it.album)
            putExtra(EXTRA_SONG_DURATION, it.durationMs)
            putExtra(EXTRA_SONG_URI, it.uri.toString())
            putExtra(EXTRA_SONG_ALBUM_ART, it.albumArtUri?.toString())
        }
    }
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

fun loadTasteReady(context: Context): Boolean {
    return context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).getBoolean(SONORIE_TASTE_READY_KEY, false)
}

fun saveTasteReady(context: Context, ready: Boolean) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(SONORIE_TASTE_READY_KEY, ready).apply()
}

fun loadFavoriteArtists(context: Context): Set<String> {
    return context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).getStringSet(SONORIE_FAVORITE_ARTISTS_KEY, emptySet())?.toSet() ?: emptySet()
}

fun saveFavoriteArtists(context: Context, artists: Set<String>) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).edit().putStringSet(SONORIE_FAVORITE_ARTISTS_KEY, artists.toSet()).apply()
}

fun loadFavoriteGenres(context: Context): Set<String> {
    return context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).getStringSet(SONORIE_FAVORITE_GENRES_KEY, emptySet())?.toSet() ?: emptySet()
}

fun saveFavoriteGenres(context: Context, genres: Set<String>) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).edit().putStringSet(SONORIE_FAVORITE_GENRES_KEY, genres.toSet()).apply()
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            val context = LocalContext.current
            var themePreference by remember { mutableStateOf(loadThemePreference(context)) }

            SonorieTheme(themePreference) {
                SonorieApp(
                    themePreference = themePreference,
                    onThemePreferenceChange = {
                        themePreference = it
                        saveThemePreference(context, it)
                    }
                )
            }
        }
    }
}

@Composable
fun SonorieTheme(themePreference: ThemePreference, content: @Composable () -> Unit) {
    val context = LocalContext.current
    val isDark = when (themePreference) {
        ThemePreference.System -> androidx.compose.foundation.isSystemInDarkTheme()
        ThemePreference.Light -> false
        ThemePreference.Dark -> true
    }
    val colorScheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (isDark) darkColorScheme() else lightColorScheme()
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        shapes = Shapes(
            small = RoundedCornerShape(14.dp),
            medium = RoundedCornerShape(22.dp),
            large = RoundedCornerShape(32.dp)
        ),
        content = content
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SonorieApp(themePreference: ThemePreference, onThemePreferenceChange: (ThemePreference) -> Unit) {
    val context = LocalContext.current
    val songs = remember { mutableStateListOf<Song>() }
    var selectedTab by remember { mutableStateOf(SonorieTab.Home) }
    var bottomDockVisible by rememberSaveable { mutableStateOf(true) }
 LaunchedEffect(bottomDockVisible) {
  if (bottomDockVisible) {
   delay(6000)
   bottomDockVisible = false
  }
 }

 LaunchedEffect(bottomDockVisible) {
  bottomDockVisible = true
 }
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    var favoriteIds by remember { mutableStateOf(loadFavoriteSongIds(context)) }
    var shuffleEnabled by remember { mutableStateOf(loadShuffleEnabled(context)) }
    var repeatMode by remember { mutableStateOf(loadRepeatMode(context)) }

 var tasteReady by remember { mutableStateOf(loadTasteReady(context)) }
 var favoriteArtists by remember { mutableStateOf(loadFavoriteArtists(context)) }
 var favoriteGenres by remember { mutableStateOf(loadFavoriteGenres(context)) }

    val currentSong = SonoriePlaybackState.currentSong
    val isPlaying = SonoriePlaybackState.isPlaying
    val playbackEventVersion = SonoriePlaybackState.eventVersion
    var progressMs by remember { mutableLongStateOf(SonoriePlaybackState.positionMs) }

 fun toggleFavoriteArtist(artist: String) {
  favoriteArtists = if (artist in favoriteArtists) favoriteArtists - artist else favoriteArtists + artist
 }

 fun toggleFavoriteGenre(genre: String) {
  favoriteGenres = if (genre in favoriteGenres) favoriteGenres - genre else favoriteGenres + genre
 }

 fun finishTasteSetup() {
  saveFavoriteArtists(context, favoriteArtists)
  saveFavoriteGenres(context, favoriteGenres)
  saveTasteReady(context, true)
  tasteReady = true
 }

    fun askNotification(launcher: () -> Unit) {
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) launcher()
    }

    fun currentIndex() = songs.indexOfFirst { it.id == currentSong?.id }

    fun playAt(index: Int, ask: () -> Unit) {
        if (index in songs.indices) {
            askNotification(ask)
            val song = songs[index]
            progressMs = 0L
            SonoriePlaybackState.update(song, true, 0L)
            sendPlaybackAction(context, SONORIE_ACTION_PLAY, song)
        }
    }

    fun playSong(song: Song, ask: () -> Unit) {
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) playAt(index, ask)
    }

    fun nextIndex(): Int {
        if (songs.isEmpty()) return -1
        val base = currentIndex()
        return if (shuffleEnabled && songs.size > 1) {
            var n = Random.nextInt(songs.size)
            while (n == base) n = Random.nextInt(songs.size)
            n
        } else if (base >= 0) (base + 1) % songs.size else 0
    }

    fun previousIndex(): Int {
        if (songs.isEmpty()) return -1
        val base = currentIndex()
        return if (base > 0) base - 1 else songs.lastIndex
    }

    fun playNext(ask: () -> Unit) { nextIndex().takeIf { it >= 0 }?.let { playAt(it, ask) } }
    fun playPrevious(ask: () -> Unit) { previousIndex().takeIf { it >= 0 }?.let { playAt(it, ask) } }

    fun togglePlayPause(ask: () -> Unit) {
        askNotification(ask)
        if (currentSong == null && songs.isNotEmpty()) {
            playAt(0, ask)
            return
        }
        if (currentSong != null) {
            SonoriePlaybackState.update(currentSong, !isPlaying, progressMs)
            sendPlaybackAction(context, SONORIE_ACTION_TOGGLE)
        }
    }

    fun toggleFavorite(song: Song) {
        val next = if (song.id in favoriteIds) favoriteIds - song.id else favoriteIds + song.id
        favoriteIds = next
        saveFavoriteSongIds(context, next)
    }

    fun toggleShuffle() {
        shuffleEnabled = !shuffleEnabled
        saveShuffleEnabled(context, shuffleEnabled)
    }

    fun cycleRepeat() {
        repeatMode = when (repeatMode) {
            RepeatMode.Off -> RepeatMode.All
            RepeatMode.All -> RepeatMode.One
            RepeatMode.One -> RepeatMode.Off
        }
        saveRepeatMode(context, repeatMode)
    }

    fun queuePreview(): List<Song> {
        if (songs.isEmpty()) return emptyList()
        val base = currentIndex()
        val ordered = if (base >= 0) songs.drop(base + 1) + songs.take(base) else songs
        return ordered.take(5)
    }

 val queuePreviewSongs by remember(currentSong?.id, songs.size, shuffleEnabled, repeatMode) {
  derivedStateOf { queuePreview() }
 }


    LaunchedEffect(playbackEventVersion) { progressMs = SonoriePlaybackState.positionMs }
    LaunchedEffect(isPlaying, currentSong?.id) {
        while (isPlaying && currentSong != null) {
            progressMs = (progressMs + 500L).coerceAtMost(currentSong.durationMs)
            delay(500)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionGranted = granted
        if (granted) {
            songs.clear()
            songs.addAll(loadLocalSongs(context))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        notificationGranted = granted
    }

    val askNotificationRequest: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(permissionGranted) {
        if (permissionGranted) {
            songs.clear()
            songs.addAll(loadLocalSongs(context))
        }
    }

    if (!tasteReady) {
  TasteOnboardingScreen(
   selectedArtists = favoriteArtists,
   selectedGenres = favoriteGenres,
   onToggleArtist = { toggleFavoriteArtist(it) },
   onToggleGenre = { toggleFavoriteGenre(it) },
   onFinish = { finishTasteSetup() },
   onSkip = {
    favoriteArtists = emptySet()
    favoriteGenres = emptySet()
    finishTasteSetup()
   }
  )
  return
 }

 Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Sonorie", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                        Text(
                            if (selectedTab == SonorieTab.Player) "Tocando agora" else "Música offline com alma",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            SonorieBottomDock(
                bottomDockVisible = bottomDockVisible,
                onBottomDockVisibleChange = { bottomDockVisible = it },
                selectedTab = selectedTab,
                onTabChange = { selectedTab = it },
                currentSong = currentSong,
                isPlaying = isPlaying,
                onPlayPause = { togglePlayPause(askNotificationRequest) },
                onNext = { playNext(askNotificationRequest) },
                onPrevious = { playPrevious(askNotificationRequest) },
                onOpenPlayer = { selectedTab = SonorieTab.Player }
            )
        }
    ) { padding ->
        Surface(Modifier.fillMaxSize().padding(padding)) {
            when (selectedTab) {
                SonorieTab.Home -> HomeScreen(
                    songs, permissionGranted, currentSong,
 favoriteArtists, favoriteGenres,
                    onRequestPermission = { audioPermissionLauncher.launch(requiredAudioPermission()) },
                    onOpenLibrary = { selectedTab = SonorieTab.Library },
                    onOpenPlayer = { selectedTab = SonorieTab.Player }
                )
                SonorieTab.Library -> LibraryScreen(
                    songs, permissionGranted, currentSong, favoriteIds,
                    onRequestPermission = { audioPermissionLauncher.launch(requiredAudioPermission()) },
                    onPlaySong = { playSong(it, askNotificationRequest) },
                    onToggleFavorite = { toggleFavorite(it) }
                )
                SonorieTab.Player -> PlayerScreen(
                    currentSong = currentSong,
                    progressMs = progressMs,
                    isPlaying = isPlaying,
                    isFavorite = currentSong?.id in favoriteIds,
                    shuffleEnabled = shuffleEnabled,
                    repeatMode = repeatMode,
                    queuePreview = queuePreviewSongs,
                    onToggleFavorite = { currentSong?.let { toggleFavorite(it) } },
                    onToggleShuffle = { toggleShuffle() },
                    onCycleRepeat = { cycleRepeat() },
                    onPlayPause = { togglePlayPause(askNotificationRequest) },
                    onNext = { playNext(askNotificationRequest) },
                    onPrevious = { playPrevious(askNotificationRequest) },
                    onOpenLibrary = { selectedTab = SonorieTab.Library }
                )
                SonorieTab.Settings -> SettingsScreen(
                    permissionGranted, notificationGranted, songs.size, currentSong,
                    shuffleEnabled, repeatMode, favoriteIds.size,
                    themePreference, onThemePreferenceChange,
                    onRequestPermission = { audioPermissionLauncher.launch(requiredAudioPermission()) },
                    onRequestNotification = askNotificationRequest
                )
            }
        }
    }
}

@Composable
fun HomeScreen(
    songs: List<Song>,
    permissionGranted: Boolean,
    currentSong: Song?,
    favoriteArtists: Set<String>,
    favoriteGenres: Set<String>,
    onRequestPermission: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val homeListState = rememberLazyListState()
    val recommendedSongs = remember(songs, favoriteArtists) {
        val matched = if (favoriteArtists.isEmpty()) emptyList() else songs.filter { song ->
            favoriteArtists.any { artist ->
                song.artist.contains(artist, ignoreCase = true) ||
                    song.title.contains(artist, ignoreCase = true) ||
                    song.album.contains(artist, ignoreCase = true)
            }
        }
        if (matched.isNotEmpty()) matched.take(6) else songs.take(6)
    }
    val primaryArtist = favoriteArtists.firstOrNull() ?: "seu gosto"
    val tasteSummary = when {
        favoriteArtists.isNotEmpty() && favoriteGenres.isNotEmpty() -> favoriteArtists.take(3).joinToString(", ") + " • " + favoriteGenres.take(2).joinToString(", ")
        favoriteArtists.isNotEmpty() -> favoriteArtists.take(4).joinToString(", ")
        favoriteGenres.isNotEmpty() -> favoriteGenres.take(4).joinToString(", ")
        else -> "Toque em Biblioteca para começar"
    }

    LazyColumn(Modifier.fillMaxSize(), state = homeListState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { HeroCard(permissionGranted, songs.size, onRequestPermission, onOpenLibrary) }

        item {
            ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Favorite, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Seu gosto musical", fontWeight = FontWeight.Bold)
                    }
                    Text(tasteSummary, color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.78f), maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }

        if (currentSong != null) {
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenPlayer), shape = RoundedCornerShape(28.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)) {
                    Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.MusicNote, null)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Continuar ouvindo", style = MaterialTheme.typography.labelMedium)
                            Text(currentSong.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }

        item { SectionTitle("Para você") }

        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.AutoAwesome, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(10.dp))
                        Text("Descobertas para $primaryArtist", fontWeight = FontWeight.Bold)
                    }
                    Text("O Sonorie organiza recomendações usando seus artistas, gêneros e músicas locais.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (favoriteGenres.isNotEmpty()) {
                        Row(modifier = Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            favoriteGenres.take(8).forEach { genre ->
                                AssistChip(onClick = {}, label = { Text(genre) }, leadingIcon = { Icon(Icons.Rounded.MusicNote, null) })
                            }
                        }
                    }
                }
            }
        }

        items(recommendedSongs.take(4), key = { it.id }, contentType = { "recommendedSong" }) { SongCompactItem(it) }

        item { SectionTitle("Resumo local") }
        item { Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) { StatCard(Modifier.weight(1f), "Músicas", songs.size.toString()); StatCard(Modifier.weight(1f), "Modo", "Offline") } }
        item { SectionTitle("Cápsulas") }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(10.dp)); Text("Cápsulas musicais inteligentes", fontWeight = FontWeight.Bold) }
                    Text(if (favoriteArtists.isNotEmpty()) "Base inicial criada com ${favoriteArtists.take(3).joinToString(", ")}. Próximas versões vão separar por momentos, energia e artistas parecidos." else "Em breve: treino, estudo, viagem, madrugada e favoritas organizadas por momento.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item { SectionTitle("Últimas músicas encontradas") }
        items(songs.take(5), key = { it.id }, contentType = { "homePreviewSong" }) { SongCompactItem(it) }
    }
}



@Composable
fun HeroCard(permissionGranted: Boolean, songsCount: Int, onRequestPermission: () -> Unit, onOpenLibrary: () -> Unit) {
    val gradient = Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer))
    ElevatedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(34.dp)) {
        Box(Modifier.fillMaxWidth().background(gradient).padding(24.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.size(58.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(32.dp), tint = MaterialTheme.colorScheme.primary)
                }
                Text("Sua biblioteca local, com visual moderno.", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
                Text(
                    if (permissionGranted) "Encontramos $songsCount músicas no aparelho. Toque na biblioteca para começar."
                    else "Permita acesso às músicas para o Sonorie funcionar offline de verdade.",
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f)
                )
                FilledTonalButton(onClick = if (permissionGranted) onOpenLibrary else onRequestPermission, shape = RoundedCornerShape(22.dp)) {
                    Icon(if (permissionGranted) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow, null)
                    Spacer(Modifier.width(8.dp))
                    Text(if (permissionGranted) "Abrir biblioteca" else "Permitir músicas")
                }
            }
        }
    }
}

@Composable
fun LibraryScreen(
    songs: List<Song>,
    permissionGranted: Boolean,
    currentSong: Song?,
    favoriteSongIds: Set<Long>,
    onRequestPermission: () -> Unit,
    onPlaySong: (Song) -> Unit,
    onToggleFavorite: (Song) -> Unit
) {
    var query by rememberSaveable { mutableStateOf("") }
    var favoritesOnly by rememberSaveable { mutableStateOf(false) }
    var libraryMode by rememberSaveable { mutableStateOf("musicas") }
    var selectedArtist by rememberSaveable { mutableStateOf<String?>(null) }
    var selectedAlbum by rememberSaveable { mutableStateOf<String?>(null) }

    fun cleanMeta(value: String, fallback: String): String {
        val cleaned = value.trim()
        return if (cleaned.isBlank() || cleaned.equals("<unknown>", true) || cleaned.equals("unknown", true)) fallback else cleaned
    }

    val normalizedQuery = remember(query) { query.trim() }

    val base = remember(songs, favoritesOnly, favoriteSongIds) {
        if (favoritesOnly) songs.filter { it.id in favoriteSongIds } else songs
    }

    val searched = remember(base, normalizedQuery) {
        if (normalizedQuery.isBlank()) {
            base
        } else {
            base.filter {
                it.title.contains(normalizedQuery, true) ||
                    it.artist.contains(normalizedQuery, true) ||
                    it.album.contains(normalizedQuery, true)
            }
        }
    }

    val artistGroups = remember(searched) {
        searched
            .groupBy { cleanMeta(it.artist, "Artista desconhecido") }
            .map { it.key to it.value }
            .sortedBy { it.first.lowercase() }
    }

    val albumGroups = remember(searched) {
        searched
            .groupBy { cleanMeta(it.album, "Álbum desconhecido") }
            .map { it.key to it.value }
            .sortedBy { it.first.lowercase() }
    }

    val selectedArtistSongs = remember(searched, selectedArtist) {
        selectedArtist?.let { artist ->
            searched.filter { cleanMeta(it.artist, "Artista desconhecido") == artist }
        } ?: emptyList()
    }

    val selectedAlbumSongs = remember(searched, selectedAlbum) {
        selectedAlbum?.let { album ->
            searched.filter { cleanMeta(it.album, "Álbum desconhecido") == album }
        } ?: emptyList()
    }

    val libraryListState = rememberLazyListState()

    LazyColumn(
        Modifier.fillMaxSize(),
        state = libraryListState,
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text("Biblioteca local", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("${songs.size} músicas encontradas no aparelho", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        if (!permissionGranted) {
            item { FilledTonalButton(onClick = onRequestPermission) { Text("Permitir músicas") } }
        }

        item {
            OutlinedTextField(
                value = query,
                onValueChange = {
                    query = it
                    selectedArtist = null
                    selectedAlbum = null
                },
                modifier = Modifier.fillMaxWidth(),
                leadingIcon = { Icon(Icons.Rounded.Search, null) },
                placeholder = { Text("Buscar música, artista ou álbum") },
                singleLine = true,
                shape = RoundedCornerShape(24.dp)
            )
        }

        item {
            Row(
                modifier = Modifier.horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                LibraryModeButton(
                    selected = libraryMode == "musicas" && !favoritesOnly,
                    text = if (libraryMode == "musicas" && !favoritesOnly) "Todas" else "Todas",
                    onClick = {
                        libraryMode = "musicas"
                        favoritesOnly = false
                        selectedArtist = null
                        selectedAlbum = null
                    }
                )

                LibraryModeButton(
                    selected = libraryMode == "artistas",
                    text = if (libraryMode == "artistas") "Artistas" else "Artistas",
                    onClick = {
                        libraryMode = "artistas"
                        selectedArtist = null
                        selectedAlbum = null
                    }
                )

                LibraryModeButton(
                    selected = libraryMode == "albuns",
                    text = if (libraryMode == "albuns") "Álbuns" else "Álbuns",
                    onClick = {
                        libraryMode = "albuns"
                        selectedArtist = null
                        selectedAlbum = null
                    }
                )

                FilledTonalButton(
                    onClick = {
                        favoritesOnly = !favoritesOnly
                        if (favoritesOnly && libraryMode == "musicas") {
                            selectedArtist = null
                            selectedAlbum = null
                        }
                    },
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Icon(
                        if (favoritesOnly) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        null,
                        Modifier.size(18.dp)
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(if (favoritesOnly) "Favoritas" else "Favoritas (${favoriteSongIds.size})")
                }
            }
        }

        if (query.isNotBlank() || favoritesOnly || libraryMode != "musicas") {
            val countText = when {
                libraryMode == "artistas" && selectedArtist == null -> "${artistGroups.size} artista(s)"
                libraryMode == "albuns" && selectedAlbum == null -> "${albumGroups.size} álbum(ns)"
                libraryMode == "artistas" && selectedArtist != null -> "${selectedArtistSongs.size} música(s)"
                libraryMode == "albuns" && selectedAlbum != null -> "${selectedAlbumSongs.size} música(s)"
                else -> "${searched.size} resultado(s)"
            }
            item { Text(countText, style = MaterialTheme.typography.labelMedium) }
        }

        if (favoritesOnly && searched.isEmpty()) {
            item {
                OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) {
                    Column(Modifier.padding(18.dp)) {
                        Text("Nenhuma favorita ainda", fontWeight = FontWeight.Bold)
                        Text("Toque no coração de uma música para guardar aqui.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        when (libraryMode) {
            "artistas" -> {
                if (selectedArtist == null) {
                    items(artistGroups, key = { it.first }, contentType = { "artistGroup" }) { group ->
                        LibraryGroupItem(
                            title = group.first,
                            subtitle = "${group.second.size} música(s)",
                            detail = group.second.firstOrNull()?.album?.let { cleanMeta(it, "Sem álbum principal") } ?: "Sem álbum principal",
                            kind = "artist",
                            onClick = { selectedArtist = group.first }
                        )
                    }
                } else {
                    item {
                        FilledTonalButton(
                            onClick = { selectedArtist = null },
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("← Artistas")
                        }
                    }
                    items(selectedArtistSongs, key = { it.id }, contentType = { "artistSong" }) { song ->
                        SongItem(song, currentSong?.id == song.id, song.id in favoriteSongIds, { onToggleFavorite(song) }, { onPlaySong(song) })
                    }
                }
            }

            "albuns" -> {
                if (selectedAlbum == null) {
                    items(albumGroups, key = { it.first }, contentType = { "albumGroup" }) { group ->
                        LibraryGroupItem(
                            title = group.first,
                            subtitle = "${group.second.size} música(s)",
                            detail = group.second.firstOrNull()?.artist?.let { cleanMeta(it, "Artista desconhecido") } ?: "Artista desconhecido",
                            kind = "album",
                            onClick = { selectedAlbum = group.first }
                        )
                    }
                } else {
                    item {
                        FilledTonalButton(
                            onClick = { selectedAlbum = null },
                            shape = RoundedCornerShape(18.dp)
                        ) {
                            Text("← Álbuns")
                        }
                    }
                    items(selectedAlbumSongs, key = { it.id }, contentType = { "albumSong" }) { song ->
                        SongItem(song, currentSong?.id == song.id, song.id in favoriteSongIds, { onToggleFavorite(song) }, { onPlaySong(song) })
                    }
                }
            }

            else -> {
                items(searched, key = { it.id }, contentType = { "librarySong" }) { song ->
                    SongItem(song, currentSong?.id == song.id, song.id in favoriteSongIds, { onToggleFavorite(song) }, { onPlaySong(song) })
                }
            }
        }
    }
}

@Composable
fun LibraryModeButton(selected: Boolean, text: String, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        shape = RoundedCornerShape(18.dp)
    ) {
        Text(text)
    }
}

@Composable
fun LibraryGroupItem(title: String, subtitle: String, detail: String, kind: String, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 3.dp, pressedElevation = 8.dp),
        colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.10f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (kind == "album") Icons.Rounded.MusicNote else Icons.Rounded.MusicNote,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(detail, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Icon(Icons.Rounded.PlayArrow, "Abrir")
        }
    }
}

@Composable
fun SongItem(song: Song, isCurrent: Boolean, isFavorite: Boolean, onToggleFavorite: () -> Unit, onClick: () -> Unit) {
    val shape = RoundedCornerShape(28.dp)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clip(shape)
            .clickable(onClick = onClick),
        shape = shape,
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = if (isCurrent) 6.dp else 2.dp, pressedElevation = 10.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isCurrent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(54.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primary.copy(alpha = if (isCurrent) 0.18f else 0.08f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isCurrent) Icons.Rounded.MusicNote else Icons.Rounded.MusicNote,
                    null,
                    tint = MaterialTheme.colorScheme.primary
                )
            }
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(formatDuration(song.durationMs), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            IconButton(onClick = onToggleFavorite) {
                Icon(
                    if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    "Favoritar",
                    tint = if (isFavorite) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            IconButton(onClick = onClick) { Icon(Icons.Rounded.PlayArrow, "Tocar") }
        }
    }
}

@Composable
fun PlayerScreen(
    currentSong: Song?,
    progressMs: Long,
    isPlaying: Boolean,
    isFavorite: Boolean,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    queuePreview: List<Song>,
    onToggleFavorite: () -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    if (currentSong == null) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Icon(Icons.Rounded.MusicNote, null, Modifier.size(82.dp), tint = MaterialTheme.colorScheme.primary)
                Text("Nada tocando agora", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                FilledTonalButton(onClick = onOpenLibrary) { Text("Abrir biblioteca") }
            }
        }
        return
    }

    val playerListState = rememberLazyListState()
 LazyColumn(Modifier.fillMaxSize(), state = playerListState, contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(18.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        item {
            Box(
                Modifier.fillMaxWidth().height(290.dp).clip(RoundedCornerShape(34.dp)).background(
                    Brush.linearGradient(listOf(MaterialTheme.colorScheme.primaryContainer, MaterialTheme.colorScheme.secondaryContainer, MaterialTheme.colorScheme.tertiaryContainer))
                ),
                contentAlignment = Alignment.Center
            ) {
                if (currentSong.albumArtUri != null) {
                    AsyncImage(currentSong.albumArtUri, "Capa do álbum", Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                } else {
                    Icon(Icons.Rounded.MusicNote, null, Modifier.size(110.dp), tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(currentSong.title, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(currentSong.artist, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(currentSong.album.ifBlank { "Álbum desconhecido" }, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                FilledTonalButton(onClick = onToggleFavorite, shape = RoundedCornerShape(22.dp)) {
                    Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null, Modifier.size(20.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(if (isFavorite) "Favorita" else "Favoritar")
                }
            }
        }
        item {
            Column(Modifier.fillMaxWidth()) {
                LinearProgressIndicator(progress = { if (currentSong.durationMs > 0) (progressMs.toFloat() / currentSong.durationMs.toFloat()).coerceIn(0f, 1f) else 0f }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(formatDuration(progressMs), style = MaterialTheme.typography.labelMedium)
                    Text(formatDuration(currentSong.durationMs), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                IconButton(onClick = onPrevious) { Icon(Icons.Rounded.SkipPrevious, "Anterior", Modifier.size(38.dp)) }
                FilledTonalButton(onClick = onPlayPause, shape = CircleShape, contentPadding = PaddingValues(26.dp)) {
                    Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, Modifier.size(36.dp))
                }
                IconButton(onClick = onNext) { Icon(Icons.Rounded.SkipNext, "Próxima", Modifier.size(38.dp)) }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Modo de reprodução", fontWeight = FontWeight.Bold)
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        FilledTonalButton(onClick = onToggleShuffle, shape = RoundedCornerShape(20.dp)) { Text(if (shuffleEnabled) "Aleatório" else "Aleatório") }
                        FilledTonalButton(onClick = onCycleRepeat, shape = RoundedCornerShape(20.dp)) {
                            Text(when (repeatMode) {
                                RepeatMode.Off -> "Repetir"
                                RepeatMode.All -> "Repetir tudo"
                                RepeatMode.One -> "Repetir 1"
                            })
                        }
                    }
                }
            }
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(26.dp)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Próximas da fila", fontWeight = FontWeight.Bold)
                    if (queuePreview.isEmpty()) Text("A fila aparece quando houver músicas disponíveis.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    queuePreview.forEachIndexed { index, song ->
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Text("${index + 1}.", color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(26.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SonorieBottomDock(
    bottomDockVisible: Boolean,
    onBottomDockVisibleChange: (Boolean) -> Unit,
    selectedTab: SonorieTab,
    onTabChange: (SonorieTab) -> Unit,
    currentSong: Song?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    val bottomPadding by animateDpAsState(
        targetValue = if (bottomDockVisible) 10.dp else 8.dp,
        animationSpec = tween(durationMillis = 160),
        label = "sonorieBottomDockPadding"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.Transparent)
            .padding(bottom = bottomPadding),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        BottomDockHandle(
            bottomDockVisible = bottomDockVisible,
            onBottomDockVisibleChange = onBottomDockVisibleChange
        )

        MiniPlayer(
            currentSong = currentSong,
            isPlaying = isPlaying,
            onPlayPause = onPlayPause,
            onNext = onNext,
            onPrevious = onPrevious,
            onOpenPlayer = onOpenPlayer
        )

        AnimatedVisibility(
            visible = bottomDockVisible,
            enter = fadeIn(animationSpec = tween(durationMillis = 130)),
            exit = fadeOut(animationSpec = tween(durationMillis = 120))
        ) {
            NavigationBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, top = 6.dp, end = 16.dp)
                    .clip(RoundedCornerShape(30.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.74f)),
                containerColor = Color.Transparent,
                tonalElevation = 0.dp
            ) {
                NavigationBarItem(
                    selected = selectedTab == SonorieTab.Home,
                    onClick = { onTabChange(SonorieTab.Home) },
                    icon = { Icon(Icons.Rounded.Home, null) },
                    label = { Text("Início") }
                )
                NavigationBarItem(
                    selected = selectedTab == SonorieTab.Library,
                    onClick = { onTabChange(SonorieTab.Library) },
                    icon = { Icon(Icons.Rounded.MusicNote, null) },
                    label = { Text("Biblioteca") }
                )
                NavigationBarItem(
                    selected = selectedTab == SonorieTab.Player,
                    onClick = { onTabChange(SonorieTab.Player) },
                    icon = { Icon(Icons.Rounded.PlayCircle, null) },
                    label = { Text("Player") }
                )
                NavigationBarItem(
                    selected = selectedTab == SonorieTab.Settings,
                    onClick = { onTabChange(SonorieTab.Settings) },
                    icon = { Icon(Icons.Rounded.Settings, null) },
                    label = { Text("Ajustes") }
                )
            }
        }
    }
}















@Composable
fun BottomDockHandle(
    bottomDockVisible: Boolean,
    onBottomDockVisibleChange: (Boolean) -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(24.dp)
            .background(Color.Transparent)
            .clickable { onBottomDockVisibleChange(!bottomDockVisible) },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .width(46.dp)
                .height(5.dp)
                .clip(RoundedCornerShape(50.dp))
                .background(MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.44f))
        )
    }
}















@Composable
fun MiniPlayer(
    currentSong: Song?,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    if (currentSong == null) return

    val shape = RoundedCornerShape(28.dp)
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 14.dp, vertical = 6.dp)
            .clip(shape)
            .clickable(onClick = onOpenPlayer),
        shape = shape,
        elevation = CardDefaults.elevatedCardElevation(
            defaultElevation = 7.dp,
            pressedElevation = 12.dp
        ),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(Icons.Rounded.MusicNote, null)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    currentSong.title,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentSong.artist,
                    style = MaterialTheme.typography.labelMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
            IconButton(onClick = onPrevious) {
                Icon(Icons.Rounded.SkipPrevious, "Anterior")
            }
            IconButton(onClick = onPlayPause) {
                Icon(if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null)
            }
            IconButton(onClick = onNext) {
                Icon(Icons.Rounded.SkipNext, "Próxima")
            }
        }
    }
}





@Composable
fun SettingsScreen(
    permissionGranted: Boolean,
    notificationGranted: Boolean,
    songsCount: Int,
    currentSong: Song?,
    shuffleEnabled: Boolean,
    repeatMode: RepeatMode,
    favoritesCount: Int,
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit,
    onRequestPermission: () -> Unit,
    onRequestNotification: () -> Unit
) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item {
            AppearanceCard(
                themePreference = themePreference,
                onThemePreferenceChange = onThemePreferenceChange
            )
        }


        item {
            Text("Ajustes", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
            Text("Sonorie v0.3.6-r2b", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
                Column(Modifier.padding(20.dp)) {
                    Text("Status do app", fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(12.dp))
                    SettingsRow("Material", "Material 3 / Material You")
                    SettingsRow("Tema", themeLabel(themePreference))
                    SettingsRow("Modo principal", "Offline")
                    SettingsRow("Permissão de áudio", if (permissionGranted) "Permitida" else "Pendente")
                    SettingsRow("Notificação", if (notificationGranted || Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) "Permitida" else "Pendente")
                    SettingsRow("Músicas locais", songsCount.toString())
                    SettingsRow("Favoritas", favoritesCount.toString())
                    SettingsRow("Aleatório", if (shuffleEnabled) "Ativado" else "Desativado")
                    SettingsRow("Repetir", when (repeatMode) { RepeatMode.Off -> "Desativado"; RepeatMode.All -> "Todas"; RepeatMode.One -> "Uma" })
                    SettingsRow("Tocando agora", currentSong?.title ?: "Nada tocando")
                }
            }
        }
        if (!permissionGranted) item { FilledTonalButton(onClick = onRequestPermission) { Text("Permitir músicas") } }
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) item { FilledTonalButton(onClick = onRequestNotification) { Text("Permitir notificação") } }
        item {
            OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
                Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Próxima evolução", fontWeight = FontWeight.Bold)
                    Text("v0.3.7: imagens reais de artistas, cápsulas avançadas e sincronização fina da notificação.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}


@Composable
fun AppearanceCard(
    themePreference: ThemePreference,
    onThemePreferenceChange: (ThemePreference) -> Unit
) {
    OutlinedCard(Modifier.fillMaxWidth(), shape = RoundedCornerShape(28.dp), colors = CardDefaults.outlinedCardColors(containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.72f))) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("Aparência", fontWeight = FontWeight.Bold)
            Text(
                "Escolha como o Sonorie deve usar o tema do aparelho.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            ThemeOptionButton(
                label = "Usar tema do sistema",
                selected = themePreference == ThemePreference.System,
                onClick = { onThemePreferenceChange(ThemePreference.System) }
            )
            ThemeOptionButton(
                label = "Modo claro",
                selected = themePreference == ThemePreference.Light,
                onClick = { onThemePreferenceChange(ThemePreference.Light) }
            )
            ThemeOptionButton(
                label = "Modo escuro",
                selected = themePreference == ThemePreference.Dark,
                onClick = { onThemePreferenceChange(ThemePreference.Dark) }
            )
        }
    }
}

@Composable
fun ThemeOptionButton(label: String, selected: Boolean, onClick: () -> Unit) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(20.dp)
    ) {
        Text(if (selected) "$label" else label)
    }
}

fun themeLabel(mode: ThemePreference): String {
    return when (mode) {
        ThemePreference.System -> "Sistema"
        ThemePreference.Light -> "Claro"
        ThemePreference.Dark -> "Escuro"
    }
}

@Composable
fun SettingsRow(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    HorizontalDivider()
}

@Composable
fun StatCard(modifier: Modifier = Modifier, title: String, value: String) {
    ElevatedCard(modifier = modifier, shape = RoundedCornerShape(26.dp)) {
        Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.ExtraBold)
        }
    }
}

@Composable
fun SectionTitle(title: String) {
    Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
}

@Composable
fun SongCompactItem(song: Song) {
    Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(Icons.Rounded.MusicNote, null, tint = MaterialTheme.colorScheme.primary)
        Spacer(Modifier.width(18.dp))
        Column {
            Text(song.title, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(song.artist, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun hasAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(context, requiredAudioPermission()) == PackageManager.PERMISSION_GRANTED
}

fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
    } else true
}

fun requiredAudioPermission(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) Manifest.permission.READ_MEDIA_AUDIO else Manifest.permission.READ_EXTERNAL_STORAGE
}

fun loadLocalSongs(context: Context): List<Song> {
    val collection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL) else MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION
    )
    val songs = mutableListOf<Song>()
    context.contentResolver.query(collection, projection, "${MediaStore.Audio.Media.IS_MUSIC} != 0", null, "${MediaStore.Audio.Media.DATE_ADDED} DESC")?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val albumId = cursor.getLong(albumIdColumn)
            val duration = cursor.getLong(durationColumn)
            if (duration > 0L) {
                songs.add(
                    Song(
                        id = id,
                        title = cursor.getString(titleColumn) ?: "Sem título",
                        artist = cursor.getString(artistColumn) ?: "Artista desconhecido",
                        album = cursor.getString(albumColumn) ?: "Álbum desconhecido",
                        durationMs = duration,
                        uri = ContentUris.withAppendedId(collection, id),
                        albumArtUri = if (albumId > 0L) ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId) else null
                    )
                )
            }
        }
    }
    return songs
}

fun formatDuration(durationMs: Long): String {
    val safe = durationMs.coerceAtLeast(0L)
    val minutes = TimeUnit.MILLISECONDS.toMinutes(safe)
    val seconds = TimeUnit.MILLISECONDS.toSeconds(safe) % 60
    return "%d:%02d".format(minutes, seconds)
}


fun loadThemePreference(context: Context): ThemePreference {
    val raw = context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE)
        .getString(SONORIE_THEME_KEY, ThemePreference.System.name)
    return runCatching { ThemePreference.valueOf(raw ?: ThemePreference.System.name) }
        .getOrDefault(ThemePreference.System)
}

fun saveThemePreference(context: Context, mode: ThemePreference) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE)
        .edit()
        .putString(SONORIE_THEME_KEY, mode.name)
        .apply()
}

fun loadFavoriteSongIds(context: Context): Set<Long> {
    val prefs = context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE)
    return prefs.getStringSet(SONORIE_FAVORITES_KEY, emptySet())?.mapNotNull { it.toLongOrNull() }?.toSet() ?: emptySet()
}

fun saveFavoriteSongIds(context: Context, ids: Set<Long>) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).edit().putStringSet(SONORIE_FAVORITES_KEY, ids.map { it.toString() }.toSet()).apply()
}

fun loadShuffleEnabled(context: Context): Boolean {
    return context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).getBoolean(SONORIE_SHUFFLE_KEY, false)
}

fun saveShuffleEnabled(context: Context, enabled: Boolean) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).edit().putBoolean(SONORIE_SHUFFLE_KEY, enabled).apply()
}

fun loadRepeatMode(context: Context): RepeatMode {
    val raw = context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).getString(SONORIE_REPEAT_KEY, RepeatMode.Off.name)
    return runCatching { RepeatMode.valueOf(raw ?: RepeatMode.Off.name) }.getOrDefault(RepeatMode.Off)
}

fun saveRepeatMode(context: Context, mode: RepeatMode) {
    context.getSharedPreferences(SONORIE_PREFS, Context.MODE_PRIVATE).edit().putString(SONORIE_REPEAT_KEY, mode.name).apply()
}


@Composable
fun FavoriteArtistHomeShelf(
    songs: List<Song>,
    favoriteArtists: Set<String>,
    onArtistSelected: (String) -> Unit = {}
) {
    val artists = remember(songs, favoriteArtists) {
        val fromSongs = songs
            .map { it.artist.trim() }
            .filter { it.isNotBlank() && it != "<unknown>" && it.lowercase() != "unknown" }
            .distinct()

        val merged = favoriteArtists.toList() + fromSongs.filter { artist ->
            favoriteArtists.none { fav -> fav.equals(artist, ignoreCase = true) }
        }
        merged.take(12)
    }

    if (artists.isNotEmpty()) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 10.dp)
        ) {
            Text(
                text = "Artistas que você curte",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
                modifier = Modifier.padding(horizontal = 4.dp, vertical = 8.dp)
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                artists.forEach { artist ->
                    Surface(
                        modifier = Modifier
                            .width(152.dp)
                            .clickable { onArtistSelected(artist) },
                        shape = RoundedCornerShape(28.dp),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.72f),
                        tonalElevation = 4.dp,
                        shadowElevation = 6.dp
                    ) {
                        Column(
                            modifier = Modifier.padding(14.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(82.dp)
                                    .clip(CircleShape)
                                    .background(
                                        MaterialTheme.colorScheme.primary.copy(
                                            alpha = if (favoriteArtists.any { it.equals(artist, ignoreCase = true) }) 0.38f else 0.22f
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = artist.take(1).uppercase(),
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.Black,
                                    color = MaterialTheme.colorScheme.onPrimary
                                )
                            }
                            Spacer(Modifier.height(10.dp))
                            Text(
                                text = artist,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Text(
                                text = if (favoriteArtists.any { it.equals(artist, ignoreCase = true) }) "Favorito" else "Relacionado",
                                style = MaterialTheme.typography.labelSmall,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.72f)
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun TasteOnboardingScreen(
    selectedArtists: Set<String>,
    selectedGenres: Set<String>,
    onToggleArtist: (String) -> Unit,
    onToggleGenre: (String) -> Unit,
    onFinish: () -> Unit,
    onSkip: () -> Unit
) {
    val featuredArtists = remember { listOf("Yeat", "Ken Carson", "Playboi Carti", "Nine Vicious", "Destroy Lonely", "Homixide Gang", "Travis Scott", "Future", "Lil Uzi Vert", "21 Savage", "Drake", "The Weeknd", "Kendrick Lamar", "Tyler, The Creator", "A\$AP Rocky", "Metro Boomin", "SZA", "Doja Cat", "Billie Eilish", "Ariana Grande", "Dua Lipa", "Bruno Mars", "Post Malone", "Bad Bunny", "Anitta", "Matuê", "Teto", "Wiu", "Veigh", "MC Cabelinho", "Luan Santana", "Gusttavo Lima") }
    val genres = remember { listOf("Rage", "Trap", "Hip-hop", "Pluggnb", "Rap", "R&B", "Pop", "Funk", "Phonk", "Eletrônica", "Lo-fi", "Rock", "Indie", "Metal", "Sertanejo", "Gospel", "Jazz", "MPB", "Reggaeton", "Afrobeat", "K-pop", "Hyperpop", "Drill", "House") }

    Surface(Modifier.fillMaxSize()) {
        LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Sonorie", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.ExtraBold)
                    Text("Monte seu gosto musical", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    Text("Escolha artistas e gêneros para o Sonorie organizar cápsulas, recomendações locais e a home do seu jeito.", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            item {
                ElevatedCard(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(32.dp), colors = CardDefaults.elevatedCardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(22.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) { Icon(Icons.Rounded.AutoAwesome, null); Spacer(Modifier.width(10.dp)); Text("Descoberta personalizada", fontWeight = FontWeight.Bold) }
                        Text("Essa é a base local. Depois podemos conectar descoberta online com segurança e APIs oficiais.", color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.78f))
                    }
                }
            }
            item { Text("Artistas favoritos", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { TasteChoiceGrid(featuredArtists, selectedArtists, onToggleArtist) }
            item { Text("Gêneros musicais", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold) }
            item { TasteChoiceGrid(genres, selectedGenres, onToggleGenre) }
            item {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button(onClick = onFinish, enabled = selectedArtists.isNotEmpty() || selectedGenres.isNotEmpty(), modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(24.dp)) { Icon(Icons.Rounded.Check, null); Spacer(Modifier.width(8.dp)); Text("Entrar no Sonorie") }
                    TextButton(onClick = onSkip, modifier = Modifier.fillMaxWidth()) { Text("Pular por enquanto") }
                }
            }
        }
    }
}

@Composable
fun TasteChoiceGrid(items: List<String>, selected: Set<String>, onToggle: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items.chunked(2).forEach { rowItems ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                rowItems.forEach { item ->
                    FilterChip(
                        selected = item in selected,
                        onClick = { onToggle(item) },
                        label = { Text(item, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        leadingIcon = { Icon(if (item in selected) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, null) },
                        modifier = Modifier.weight(1f)
                    )
                }
                if (rowItems.size == 1) Spacer(Modifier.weight(1f))
            }
        }
    }
}
