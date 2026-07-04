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
import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.PlayCircle
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Shapes
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
import androidx.media3.exoplayer.ExoPlayer
import coil.compose.AsyncImage
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.delay

const val SONORIE_ACTION_PLAY = "com.swlab.sonorie.action.PLAY"
const val SONORIE_ACTION_TOGGLE = "com.swlab.sonorie.action.TOGGLE"
const val SONORIE_ACTION_PAUSE = "com.swlab.sonorie.action.PAUSE"
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

data class Song(
    val id: Long,
    val title: String,
    val artist: String,
    val album: String,
    val durationMs: Long,
    val uri: Uri,
    val albumArtUri: Uri? = null
)

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

enum class SonorieTab {
    Home,
    Library,
    Player,
    Settings
}

class SonoriePlaybackService : Service() {
    private var player: ExoPlayer? = null
    private var mediaSession: MediaSessionCompat? = null
    private var currentSong: Song? = null
    private var currentIndex: Int = -1
    private var cachedSongs: List<Song> = emptyList()
    private var isPlayingState: Boolean = false

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()

        player = ExoPlayer.Builder(this).build()
        mediaSession = MediaSessionCompat(this, "SonoriePlaybackSession").apply {
            setCallback(object : MediaSessionCompat.Callback() {
                override fun onPlay() {
                    if (!isPlayingState) {
                        togglePlayback()
                    }
                }

                override fun onPause() {
                    if (isPlayingState) {
                        pausePlayback()
                    }
                }

                override fun onSkipToNext() {
                    playNext()
                }

                override fun onSkipToPrevious() {
                    playPrevious()
                }

                override fun onStop() {
                    stopPlayback()
                }
            })
            isActive = true
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            SONORIE_ACTION_PLAY -> {
                val song = songFromIntent(intent)
                playSong(song)
            }

            SONORIE_ACTION_TOGGLE -> {
                togglePlayback()
            }

            SONORIE_ACTION_PAUSE -> {
                pausePlayback()
            }

            SONORIE_ACTION_NEXT -> {
                playNext()
            }

            SONORIE_ACTION_PREVIOUS -> {
                playPrevious()
            }

            SONORIE_ACTION_STOP -> {
                stopPlayback()
            }

            else -> {
                currentSong?.let { showNotification(it) }
            }
        }

        return START_STICKY
    }

    override fun onDestroy() {
        mediaSession?.isActive = false
        mediaSession?.release()
        mediaSession = null

        player?.release()
        player = null

        super.onDestroy()
    }

    private fun syncUiState() {
        SonoriePlaybackState.update(
            song = currentSong,
            playing = isPlayingState,
            position = player?.currentPosition ?: 0L
        )
    }

    private fun songFromIntent(intent: Intent): Song {
        val id = intent.getLongExtra(EXTRA_SONG_ID, -1L)
        val title = intent.getStringExtra(EXTRA_SONG_TITLE) ?: "Sem título"
        val artist = intent.getStringExtra(EXTRA_SONG_ARTIST) ?: "Artista desconhecido"
        val album = intent.getStringExtra(EXTRA_SONG_ALBUM) ?: "Álbum desconhecido"
        val duration = intent.getLongExtra(EXTRA_SONG_DURATION, 0L)
        val uri = intent.getStringExtra(EXTRA_SONG_URI)?.let { Uri.parse(it) }
            ?: ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
        val albumArtUri = intent.getStringExtra(EXTRA_SONG_ALBUM_ART)?.let { Uri.parse(it) }

        return Song(
            id = id,
            title = title,
            artist = artist,
            album = album,
            durationMs = duration,
            uri = uri,
            albumArtUri = albumArtUri
        )
    }

    private fun playSong(song: Song) {
        currentSong = song
        refreshSongCache()
        currentIndex = cachedSongs.indexOfFirst { it.id == song.id }

        isPlayingState = true
        updateMetadata(song)
        updatePlaybackState(true)

        player?.setMediaItem(MediaItem.fromUri(song.uri))
        player?.prepare()
        player?.play()

        forceSystemPlaybackRefresh()
        showNotification(song)
    }

    private fun togglePlayback() {
        val song = currentSong ?: return

        if (isPlayingState) {
            pausePlayback()
        } else {
            isPlayingState = true
            player?.play()
            updatePlaybackState(true)
            forceSystemPlaybackRefresh()
            showNotification(song)
        }
    }

    private fun pausePlayback() {
        val song = currentSong ?: return

        isPlayingState = false
        player?.pause()
        updatePlaybackState(false)
        forceSystemPlaybackRefresh()
        showNotification(song)
    }

    private fun playNext() {
        refreshSongCache()

        if (cachedSongs.isEmpty()) return

        val nextIndex = if (currentIndex >= 0) {
            (currentIndex + 1) % cachedSongs.size
        } else {
            0
        }

        playSong(cachedSongs[nextIndex])
    }

    private fun playPrevious() {
        refreshSongCache()

        if (cachedSongs.isEmpty()) return

        val previousIndex = if (currentIndex > 0) {
            currentIndex - 1
        } else {
            cachedSongs.lastIndex
        }

        playSong(cachedSongs[previousIndex])
    }

    private fun stopPlayback() {
        isPlayingState = false
        player?.stop()
        syncUiState()
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshSongCache() {
        if (cachedSongs.isEmpty()) {
            cachedSongs = loadLocalSongs(this)
        }
    }

    private fun updateMetadata(song: Song) {
        val metadataBuilder = MediaMetadataCompat.Builder()
            .putString(MediaMetadataCompat.METADATA_KEY_TITLE, song.title)
            .putString(MediaMetadataCompat.METADATA_KEY_ARTIST, song.artist)
            .putString(MediaMetadataCompat.METADATA_KEY_ALBUM, song.album)
            .putLong(MediaMetadataCompat.METADATA_KEY_DURATION, song.durationMs)

        song.albumArtUri?.let {
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ALBUM_ART_URI, it.toString())
            metadataBuilder.putString(MediaMetadataCompat.METADATA_KEY_ART_URI, it.toString())
        }

        val metadata = metadataBuilder.build()

        mediaSession?.setMetadata(metadata)
    }

    private fun updatePlaybackState(isPlaying: Boolean) {
        val position = player?.currentPosition ?: 0L
        val state = if (isPlaying) {
            PlaybackStateCompat.STATE_PLAYING
        } else {
            PlaybackStateCompat.STATE_PAUSED
        }

        val playbackState = PlaybackStateCompat.Builder()
            .setActions(
                PlaybackStateCompat.ACTION_PLAY or
                    PlaybackStateCompat.ACTION_PAUSE or
                    PlaybackStateCompat.ACTION_PLAY_PAUSE or
                    PlaybackStateCompat.ACTION_SKIP_TO_NEXT or
                    PlaybackStateCompat.ACTION_SKIP_TO_PREVIOUS or
                    PlaybackStateCompat.ACTION_STOP
            )
            .setState(state, position, 1f)
            .build()

        mediaSession?.setPlaybackState(playbackState)
    }

    private fun forceSystemPlaybackRefresh() {
        currentSong?.let { updateMetadata(it) }
        updatePlaybackState(isPlayingState)
        syncUiState()
    }

    private fun showNotification(song: Song) {
        val notification = buildNotification(song)
        startForeground(SONORIE_NOTIFICATION_ID, notification)
    }

    private fun buildNotification(song: Song): android.app.Notification {
        val openAppIntent = Intent(this, MainActivity::class.java)
        val openAppPendingIntent = PendingIntent.getActivity(
            this,
            200,
            openAppIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        val previousIntent = servicePendingIntent(SONORIE_ACTION_PREVIOUS, 201)
        val toggleIntent = servicePendingIntent(SONORIE_ACTION_TOGGLE, 202)
        val nextIntent = servicePendingIntent(SONORIE_ACTION_NEXT, 203)
        val stopIntent = servicePendingIntent(SONORIE_ACTION_STOP, 204)

        val playPauseIcon = if (isPlayingState) {
            android.R.drawable.ic_media_pause
        } else {
            android.R.drawable.ic_media_play
        }

        val playPauseText = if (isPlayingState) "Pausar" else "Tocar"

        return NotificationCompat.Builder(this, SONORIE_NOTIFICATION_CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_sonorie)
            .setContentTitle(song.title)
            .setContentText(song.artist.ifBlank { "Sonorie" })
            .setSubText("Sonorie")
            .setContentIntent(openAppPendingIntent)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setOnlyAlertOnce(true)
            .setOngoing(isPlayingState)
            .addAction(android.R.drawable.ic_media_previous, "Anterior", previousIntent)
            .addAction(playPauseIcon, playPauseText, toggleIntent)
            .addAction(android.R.drawable.ic_media_next, "Próxima", nextIntent)
            .setDeleteIntent(stopIntent)
            .setStyle(
                androidx.media.app.NotificationCompat.MediaStyle()
                    .setMediaSession(mediaSession?.sessionToken)
                    .setShowActionsInCompactView(0, 1, 2)
            )
            .build()
    }

    private fun servicePendingIntent(action: String, requestCode: Int): PendingIntent {
        val intent = Intent(this, SonoriePlaybackService::class.java).apply {
            this.action = action
        }

        return PendingIntent.getService(
            this,
            requestCode,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                SONORIE_NOTIFICATION_CHANNEL,
                "Reprodução do Sonorie",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Controles de reprodução de música do Sonorie"
                setShowBadge(false)
            }

            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }
}

fun sendPlaybackAction(context: Context, action: String, song: Song? = null) {
    val intent = Intent(context, SonoriePlaybackService::class.java).apply {
        this.action = action

        if (song != null) {
            putExtra(EXTRA_SONG_ID, song.id)
            putExtra(EXTRA_SONG_TITLE, song.title)
            putExtra(EXTRA_SONG_ARTIST, song.artist)
            putExtra(EXTRA_SONG_ALBUM, song.album)
            putExtra(EXTRA_SONG_DURATION, song.durationMs)
            putExtra(EXTRA_SONG_URI, song.uri.toString())
            putExtra(EXTRA_SONG_ALBUM_ART, song.albumArtUri?.toString())
        }
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        ContextCompat.startForegroundService(context, intent)
    } else {
        context.startService(intent)
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SonorieTheme {
                SonorieApp()
            }
        }
    }
}

@Composable
fun SonorieTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val useDark = false

    val colorScheme =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (useDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        } else {
            if (useDark) darkColorScheme() else lightColorScheme()
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
fun SonorieApp() {
    val context = LocalContext.current
    val songs = remember { mutableStateListOf<Song>() }
    var selectedTab by remember { mutableStateOf(SonorieTab.Home) }
    var permissionGranted by remember { mutableStateOf(hasAudioPermission(context)) }
    var notificationGranted by remember { mutableStateOf(hasNotificationPermission(context)) }
    val currentSong = SonoriePlaybackState.currentSong
    val isPlaying = SonoriePlaybackState.isPlaying
    val playbackEventVersion = SonoriePlaybackState.eventVersion
    var progressMs by remember { mutableStateOf(SonoriePlaybackState.positionMs) }

    fun requestNotificationIfNeeded(launcher: () -> Unit) {
        if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            launcher()
        }
    }

    fun playAt(index: Int, askNotification: () -> Unit) {
        if (index in songs.indices) {
            requestNotificationIfNeeded(askNotification)

            val song = songs[index]
            progressMs = 0L
            SonoriePlaybackState.update(song, true, 0L)
            sendPlaybackAction(context, SONORIE_ACTION_PLAY, song)
        }
    }

    fun playSong(song: Song, askNotification: () -> Unit) {
        val index = songs.indexOfFirst { it.id == song.id }
        if (index >= 0) {
            playAt(index, askNotification)
        }
    }

    fun currentIndexFromState(): Int {
        return songs.indexOfFirst { it.id == currentSong?.id }
    }

    fun playNext(askNotification: () -> Unit) {
        if (songs.isNotEmpty()) {
            val baseIndex = currentIndexFromState()
            val nextIndex = if (baseIndex >= 0) {
                (baseIndex + 1) % songs.size
            } else {
                0
            }
            playAt(nextIndex, askNotification)
        }
    }

    fun playPrevious(askNotification: () -> Unit) {
        if (songs.isNotEmpty()) {
            val baseIndex = currentIndexFromState()
            val previousIndex = if (baseIndex > 0) {
                baseIndex - 1
            } else {
                songs.lastIndex
            }
            playAt(previousIndex, askNotification)
        }
    }

    fun togglePlayPause(askNotification: () -> Unit) {
        requestNotificationIfNeeded(askNotification)

        if (currentSong == null && songs.isNotEmpty()) {
            playAt(0, askNotification)
            return
        }

        if (currentSong != null) {
            SonoriePlaybackState.update(
                song = currentSong,
                playing = !isPlaying,
                position = progressMs
            )
            sendPlaybackAction(context, SONORIE_ACTION_TOGGLE)
        }
    }

    LaunchedEffect(playbackEventVersion) {
        progressMs = SonoriePlaybackState.positionMs
    }

    LaunchedEffect(isPlaying, currentSong?.id) {
        while (isPlaying && currentSong != null) {
            progressMs += 500L
            if (progressMs > currentSong.durationMs) {
                progressMs = currentSong.durationMs
            }
            delay(500)
        }
    }

    val audioPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        permissionGranted = granted
        if (granted) {
            songs.clear()
            songs.addAll(loadLocalSongs(context))
        }
    }

    val notificationPermissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        notificationGranted = granted
    }

    val askNotification: () -> Unit = {
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

    Scaffold(
        topBar = {
            CenterAlignedTopAppBar(
                title = {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "Sonorie",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = when (selectedTab) {
                                SonorieTab.Player -> "Tocando agora"
                                else -> "Música offline com alma"
                            },
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            )
        },
        bottomBar = {
            Column {
                MiniPlayer(
                    currentSong = currentSong,
                    isPlaying = isPlaying,
                    onPlayPause = { togglePlayPause(askNotification) },
                    onNext = { playNext(askNotification) },
                    onPrevious = { playPrevious(askNotification) },
                    onOpenPlayer = { selectedTab = SonorieTab.Player }
                )

                NavigationBar {
                    NavigationBarItem(
                        selected = selectedTab == SonorieTab.Home,
                        onClick = { selectedTab = SonorieTab.Home },
                        icon = { Icon(Icons.Rounded.Home, contentDescription = null) },
                        label = { Text("Início") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == SonorieTab.Library,
                        onClick = { selectedTab = SonorieTab.Library },
                        icon = { Icon(Icons.Rounded.LibraryMusic, contentDescription = null) },
                        label = { Text("Biblioteca") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == SonorieTab.Player,
                        onClick = { selectedTab = SonorieTab.Player },
                        icon = { Icon(Icons.Rounded.PlayCircle, contentDescription = null) },
                        label = { Text("Player") }
                    )
                    NavigationBarItem(
                        selected = selectedTab == SonorieTab.Settings,
                        onClick = { selectedTab = SonorieTab.Settings },
                        icon = { Icon(Icons.Rounded.Settings, contentDescription = null) },
                        label = { Text("Ajustes") }
                    )
                }
            }
        }
    ) { padding ->
        Surface(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            AnimatedContent(
                targetState = selectedTab,
                label = "SonorieTabAnimation"
            ) { tab ->
                when (tab) {
                    SonorieTab.Home -> HomeScreen(
                        songs = songs,
                        permissionGranted = permissionGranted,
                        currentSong = currentSong,
                        onRequestPermission = {
                            audioPermissionLauncher.launch(requiredAudioPermission())
                        },
                        onOpenLibrary = { selectedTab = SonorieTab.Library },
                        onOpenPlayer = { selectedTab = SonorieTab.Player }
                    )

                    SonorieTab.Library -> LibraryScreen(
                        songs = songs,
                        permissionGranted = permissionGranted,
                        currentSong = currentSong,
                        onRequestPermission = {
                            audioPermissionLauncher.launch(requiredAudioPermission())
                        },
                        onPlaySong = { song ->
                            playSong(song, askNotification)
                        }
                    )

                    SonorieTab.Player -> PlayerScreen(
                        currentSong = currentSong,
                        progressMs = progressMs,
                        isPlaying = isPlaying,
                        onPlayPause = { togglePlayPause(askNotification) },
                        onNext = { playNext(askNotification) },
                        onPrevious = { playPrevious(askNotification) },
                        onOpenLibrary = { selectedTab = SonorieTab.Library }
                    )

                    SonorieTab.Settings -> SettingsScreen(
                        permissionGranted = permissionGranted,
                        notificationGranted = notificationGranted,
                        songsCount = songs.size,
                        currentSong = currentSong,
                        onRequestPermission = {
                            audioPermissionLauncher.launch(requiredAudioPermission())
                        },
                        onRequestNotification = askNotification
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen(
    songs: List<Song>,
    permissionGranted: Boolean,
    currentSong: Song?,
    onRequestPermission: () -> Unit,
    onOpenLibrary: () -> Unit,
    onOpenPlayer: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            HeroCard(
                permissionGranted = permissionGranted,
                songsCount = songs.size,
                onRequestPermission = onRequestPermission,
                onOpenLibrary = onOpenLibrary
            )
        }

        if (currentSong != null) {
            item {
                ElevatedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClick = onOpenPlayer),
                    shape = RoundedCornerShape(28.dp),
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(18.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                        Spacer(Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                "Continuar ouvindo",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f)
                            )
                            Text(
                                currentSong.title,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        }

        item {
            SectionTitle("Resumo local")
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Músicas",
                    value = songs.size.toString()
                )
                StatCard(
                    modifier = Modifier.weight(1f),
                    title = "Modo",
                    value = "Offline"
                )
            }
        }

        item {
            SectionTitle("Cápsulas")
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            Icons.Rounded.GraphicEq,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "Cápsulas musicais offline",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    Text(
                        "Em breve: treino, estudo, viagem, madrugada e favoritas organizadas por momento.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            SectionTitle("Últimas músicas encontradas")
        }

        items(songs.take(5)) { song ->
            SongCompactItem(song = song)
        }
    }
}

@Composable
fun HeroCard(
    permissionGranted: Boolean,
    songsCount: Int,
    onRequestPermission: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    val gradient = Brush.linearGradient(
        listOf(
            MaterialTheme.colorScheme.primaryContainer,
            MaterialTheme.colorScheme.secondaryContainer,
            MaterialTheme.colorScheme.tertiaryContainer
        )
    )

    ElevatedCard(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(34.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(gradient)
                .padding(24.dp)
        ) {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(
                    modifier = Modifier
                        .size(58.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.surface.copy(alpha = 0.70f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(32.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }

                Text(
                    text = "Sua biblioteca local, com visual moderno.",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )

                Text(
                    text = if (permissionGranted) {
                        "Encontramos $songsCount músicas no aparelho. Toque na biblioteca para começar."
                    } else {
                        "Permita acesso às músicas para o Sonorie funcionar offline de verdade."
                    },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.82f)
                )

                FilledTonalButton(
                    onClick = if (permissionGranted) onOpenLibrary else onRequestPermission,
                    shape = RoundedCornerShape(22.dp),
                    colors = ButtonDefaults.filledTonalButtonColors()
                ) {
                    Icon(
                        if (permissionGranted) Icons.Rounded.LibraryMusic else Icons.Rounded.PlayCircle,
                        contentDescription = null
                    )
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
    onRequestPermission: () -> Unit,
    onPlaySong: (Song) -> Unit
) {
    var searchQuery by remember { mutableStateOf("") }

    if (!permissionGranted) {
        PermissionEmptyState(onRequestPermission)
        return
    }

    if (songs.isEmpty()) {
        EmptyMusicState()
        return
    }

    val filteredSongs = if (searchQuery.isBlank()) {
        songs
    } else {
        songs.filter {
            it.title.contains(searchQuery, ignoreCase = true) ||
                it.artist.contains(searchQuery, ignoreCase = true) ||
                it.album.contains(searchQuery, ignoreCase = true)
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item {
            Text(
                text = "Biblioteca local",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "${songs.size} músicas encontradas no aparelho",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(12.dp))

            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                shape = RoundedCornerShape(22.dp),
                leadingIcon = {
                    Icon(Icons.Rounded.Search, contentDescription = null)
                },
                placeholder = {
                    Text("Buscar música, artista ou álbum")
                }
            )

            if (searchQuery.isNotBlank()) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = "${filteredSongs.size} resultado(s)",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(Modifier.height(8.dp))
        }

        items(filteredSongs, key = { it.id }) { song ->
            SongItem(
                song = song,
                isCurrent = currentSong?.id == song.id,
                onClick = { onPlaySong(song) }
            )
        }
    }
}

@Composable
fun PlayerScreen(
    currentSong: Song?,
    progressMs: Long,
    isPlaying: Boolean,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onOpenLibrary: () -> Unit
) {
    if (currentSong == null) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Icon(
                Icons.Rounded.PlayCircle,
                contentDescription = null,
                modifier = Modifier.size(96.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Spacer(Modifier.height(18.dp))
            Text(
                "Nada tocando agora",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                "Abra a biblioteca e escolha uma música para iniciar o player.",
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(20.dp))
            FilledTonalButton(
                onClick = onOpenLibrary,
                shape = RoundedCornerShape(22.dp)
            ) {
                Text("Abrir biblioteca")
            }
        }
        return
    }

    val duration = currentSong.durationMs.coerceAtLeast(1L)
    val safeProgress = (progressMs.toFloat() / duration.toFloat()).coerceIn(0f, 1f)

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(24.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(310.dp)
                    .clip(RoundedCornerShape(42.dp))
                    .background(
                        Brush.linearGradient(
                            listOf(
                                MaterialTheme.colorScheme.primaryContainer,
                                MaterialTheme.colorScheme.secondaryContainer,
                                MaterialTheme.colorScheme.tertiaryContainer
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (currentSong.albumArtUri != null) {
                    AsyncImage(
                        model = currentSong.albumArtUri,
                        contentDescription = "Capa do álbum",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        Icons.Rounded.MusicNote,
                        contentDescription = null,
                        modifier = Modifier.size(110.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                }
            }
        }

        item {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    currentSong.title,
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentSong.artist.ifBlank { "Artista desconhecido" },
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    currentSong.album.ifBlank { "Álbum desconhecido" },
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }

        item {
            Column(modifier = Modifier.fillMaxWidth()) {
                LinearProgressIndicator(
                    progress = safeProgress,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(8.dp)
                        .clip(RoundedCornerShape(50.dp))
                )
                Spacer(Modifier.height(8.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        formatDuration(progressMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        formatDuration(currentSong.durationMs),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onPrevious, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Rounded.SkipPrevious,
                        contentDescription = "Anterior",
                        modifier = Modifier.size(38.dp)
                    )
                }

                Spacer(Modifier.width(18.dp))

                FilledTonalButton(
                    onClick = onPlayPause,
                    shape = RoundedCornerShape(34.dp),
                    contentPadding = PaddingValues(horizontal = 28.dp, vertical = 18.dp)
                ) {
                    Icon(
                        if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Play/Pause",
                        modifier = Modifier.size(32.dp)
                    )
                }

                Spacer(Modifier.width(18.dp))

                IconButton(onClick = onNext, modifier = Modifier.size(64.dp)) {
                    Icon(
                        Icons.Rounded.SkipNext,
                        contentDescription = "Próxima",
                        modifier = Modifier.size(38.dp)
                    )
                }
            }
        }

        item {
            OutlinedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier.padding(18.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    Text(
                        "Reprodução do sistema",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        "Esta versão adiciona capa de álbum real quando disponível e refina o progresso do player.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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
    onRequestPermission: () -> Unit,
    onRequestNotification: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(20.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Text(
                text = "Ajustes",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = "Sonorie v0.2.3",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text(
                        "Status do app",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )

                    SettingLine("Material", "Material 3 / Material You")
                    SettingLine("Modo principal", "Offline")
                    SettingLine("Permissão de áudio", if (permissionGranted) "Permitida" else "Pendente")
                    SettingLine("Notificação", if (notificationGranted) "Permitida" else "Pendente ou não exigida")
                    SettingLine("Músicas locais", songsCount.toString())
                    SettingLine("Tocando agora", currentSong?.title ?: "Nenhuma")

                    if (!permissionGranted) {
                        FilledTonalButton(
                            onClick = onRequestPermission,
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Text("Permitir acesso às músicas")
                        }
                    }

                    if (!notificationGranted && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        FilledTonalButton(
                            onClick = onRequestNotification,
                            shape = RoundedCornerShape(22.dp)
                        ) {
                            Text("Permitir notificações")
                        }
                    }
                }
            }
        }

        item {
            ElevatedCard(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(28.dp)
            ) {
                Column(
                    modifier = Modifier.padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        "Próxima evolução",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "v0.2.4: refinamento visual do player, favoritos e fila de reprodução.",
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

@Composable
fun PermissionEmptyState(onRequestPermission: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.LibraryMusic,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Permissão necessária",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Para funcionar offline, o Sonorie precisa ler as músicas locais do aparelho.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(20.dp))
        FilledTonalButton(
            onClick = onRequestPermission,
            shape = RoundedCornerShape(22.dp)
        ) {
            Text("Permitir músicas")
        }
    }
}

@Composable
fun EmptyMusicState() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            Icons.Rounded.Album,
            contentDescription = null,
            modifier = Modifier.size(80.dp),
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.height(18.dp))
        Text(
            "Nenhuma música encontrada",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            "Coloque arquivos de áudio no aparelho e abra o Sonorie novamente.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
fun SongItem(song: Song, isCurrent: Boolean, onClick: () -> Unit) {
    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = if (isCurrent) {
                MaterialTheme.colorScheme.secondaryContainer
            } else {
                MaterialTheme.colorScheme.surfaceVariant
            }
        )
    ) {
        Row(
            modifier = Modifier.padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (isCurrent) Icons.Rounded.GraphicEq else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(Modifier.width(14.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = song.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = song.artist.ifBlank { "Artista desconhecido" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = formatDuration(song.durationMs),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            IconButton(onClick = onClick) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Tocar")
            }
        }
    }
}

@Composable
fun SongCompactItem(song: Song) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            Icons.Rounded.MusicNote,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary
        )
        Spacer(Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                song.title,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.SemiBold
            )
            Text(
                song.artist.ifBlank { "Artista desconhecido" },
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.bodySmall
            )
        }
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

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .clickable(onClick = onOpenPlayer),
        shape = RoundedCornerShape(26.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Rounded.GraphicEq,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    currentSong.title,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
                Text(
                    currentSong.artist.ifBlank { "Artista desconhecido" },
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.75f),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            IconButton(onClick = onPrevious) {
                Icon(
                    Icons.Rounded.SkipPrevious,
                    contentDescription = "Anterior",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            IconButton(onClick = onPlayPause) {
                Icon(
                    if (isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = "Play/Pause",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }

            IconButton(onClick = onNext) {
                Icon(
                    Icons.Rounded.SkipNext,
                    contentDescription = "Próxima",
                    tint = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }
}

@Composable
fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 4.dp)
    )
}

@Composable
fun StatCard(
    modifier: Modifier = Modifier,
    title: String,
    value: String
) {
    ElevatedCard(
        modifier = modifier,
        shape = RoundedCornerShape(26.dp)
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                value,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.ExtraBold
            )
        }
    }
}

@Composable
fun SettingLine(label: String, value: String) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(label, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(value, fontWeight = FontWeight.SemiBold)
        }
        Spacer(Modifier.height(8.dp))
        HorizontalDivider()
    }
}

fun requiredAudioPermission(): String {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        Manifest.permission.READ_MEDIA_AUDIO
    } else {
        Manifest.permission.READ_EXTERNAL_STORAGE
    }
}

fun hasAudioPermission(context: Context): Boolean {
    return ContextCompat.checkSelfPermission(
        context,
        requiredAudioPermission()
    ) == PackageManager.PERMISSION_GRANTED
}

fun hasNotificationPermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS
        ) == PackageManager.PERMISSION_GRANTED
    } else {
        true
    }
}

fun loadLocalSongs(context: Context): List<Song> {
    val songs = mutableListOf<Song>()
    val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI

    val projection = arrayOf(
        MediaStore.Audio.Media._ID,
        MediaStore.Audio.Media.TITLE,
        MediaStore.Audio.Media.ARTIST,
        MediaStore.Audio.Media.ALBUM,
        MediaStore.Audio.Media.ALBUM_ID,
        MediaStore.Audio.Media.DURATION
    )

    val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0"
    val sortOrder = "${MediaStore.Audio.Media.DATE_ADDED} DESC"

    context.contentResolver.query(
        collection,
        projection,
        selection,
        null,
        sortOrder
    )?.use { cursor ->
        val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
        val titleColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
        val artistColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
        val albumColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
        val albumIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
        val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)

        while (cursor.moveToNext()) {
            val id = cursor.getLong(idColumn)
            val title = cursor.getString(titleColumn) ?: "Sem título"
            val artist = cursor.getString(artistColumn) ?: "Artista desconhecido"
            val album = cursor.getString(albumColumn) ?: "Álbum desconhecido"
            val albumId = cursor.getLong(albumIdColumn)
            val duration = cursor.getLong(durationColumn)
            val uri = ContentUris.withAppendedId(collection, id)
            val albumArtUri = if (albumId > 0L) {
                ContentUris.withAppendedId(Uri.parse("content://media/external/audio/albumart"), albumId)
            } else {
                null
            }

            if (duration > 0) {
                songs.add(
                    Song(
                        id = id,
                        title = title,
                        artist = artist,
                        album = album,
                        durationMs = duration,
                        uri = uri,
                        albumArtUri = albumArtUri
                    )
                )
            }
        }
    }

    return songs
}

fun formatDuration(durationMs: Long): String {
    val minutes = TimeUnit.MILLISECONDS.toMinutes(durationMs.coerceAtLeast(0L))
    val seconds = TimeUnit.MILLISECONDS.toSeconds(durationMs.coerceAtLeast(0L)) % 60
    return "%d:%02d".format(minutes, seconds)
}
