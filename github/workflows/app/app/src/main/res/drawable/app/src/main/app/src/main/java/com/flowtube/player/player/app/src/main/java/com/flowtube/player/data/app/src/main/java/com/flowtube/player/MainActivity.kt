package com.flowtube.player

import android.Manifest
import android.app.PictureInPictureParams
import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Rational
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.app.PictureInPictureModeChangedInfo
import androidx.core.util.Consumer
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import coil.compose.AsyncImage
import com.flowtube.player.data.FavoritesStorage
import com.flowtube.player.data.LocalMediaRepository
import com.flowtube.player.data.MediaItemModel
import com.flowtube.player.data.OnlineVideoRepository
import com.flowtube.player.player.FloatingPlayerService
import com.flowtube.player.player.MediaPlaybackService
import com.google.common.util.concurrent.MoreExecutors
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF2E63),
                    background = Color(0xFF0A0A0E),
                    surface = Color(0xFF14141C)
                )
            ) {
                FlowTubeScreen(activity = this)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FlowTubeScreen(activity: ComponentActivity) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val localRepo = remember { LocalMediaRepository(context) }
    val onlineRepo = remember { OnlineVideoRepository() }
    val favStorage = remember { FavoritesStorage(context) }

    var mediaController by remember { mutableStateOf<MediaController?>(null) }
    var localItems by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var onlineItems by remember { mutableStateOf<List<MediaItemModel>>(emptyList()) }
    var favorites by remember { mutableStateOf(favStorage.loadFavorites()) }

    var selectedTab by remember { mutableIntStateOf(0) } // 0=Vídeos, 1=MP3, 2=Online, 3=Favoritos
    var localSearch by remember { mutableStateOf("") }
    var onlineSearch by remember { mutableStateOf("") }
    var directUrl by remember { mutableStateOf("") }
    var isSearchingOnline by remember { mutableStateOf(false) }

    var activeTitle by remember { mutableStateOf<String?>(null) }
    var playbackSpeed by remember { mutableFloatStateOf(1.0f) }
    var sleepMinutes by remember { mutableIntStateOf(0) }
    var isInPipMode by remember { mutableStateOf(false) }

    DisposableEffect(activity) {
        val listener = Consumer<PictureInPictureModeChangedInfo> { info ->
            isInPipMode = info.isInPictureInPictureMode
        }
        activity.addOnPictureInPictureModeChangedListener(listener)
        onDispose { activity.removeOnPictureInPictureModeChangedListener(listener) }
    }

    val permLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        scope.launch { localItems = localRepo.loadLocalMedia() }
    }

    LaunchedEffect(Unit) {
        val perms = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            arrayOf(
                Manifest.permission.READ_MEDIA_VIDEO,
                Manifest.permission.READ_MEDIA_AUDIO,
                Manifest.permission.POST_NOTIFICATIONS
            )
        } else {
            arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        permLauncher.launch(perms)

        val token = SessionToken(context, ComponentName(context, MediaPlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        future.addListener({
            mediaController = future.get()
        }, MoreExecutors.directExecutor())

        localItems = localRepo.loadLocalMedia()
    }

    // Sleep Timer automático
    LaunchedEffect(sleepMinutes) {
        if (sleepMinutes > 0) {
            delay(sleepMinutes * 60_000L)
            mediaController?.pause()
            sleepMinutes = 0
            Toast.makeText(context, "Sleep Timer: reprodução pausada", Toast.LENGTH_SHORT).show()
        }
    }

    fun playItem(item: MediaItemModel) {
        scope.launch {
            val resolvedUrl = if (item.isOnline) {
                Toast.makeText(context, "Carregando stream...", Toast.LENGTH_SHORT).show()
                onlineRepo.resolveStreamUrl(item)
            } else {
                item.streamUrl
            }

            if (resolvedUrl.isNullOrEmpty()) {
                Toast.makeText(context, "Não foi possível abrir este vídeo", Toast.LENGTH_SHORT).show()
                return@launch
            }

            val mediaItem = MediaItem.Builder()
                .setUri(Uri.parse(resolvedUrl))
                .setMediaMetadata(
                    MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setArtist(item.subtitle)
                        .build()
                )
                .build()

            mediaController?.apply {
                setMediaItem(mediaItem)
                prepare()
                play()
            }
            activeTitle = item.title
        }
    }

    fun openFloatingPopup() {
        if (!Settings.canDrawOverlays(context)) {
            Toast.makeText(context, "Ative a permissão de sobreposição para usar o pop-up", Toast.LENGTH_LONG).show()
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}"))
            )
        } else {
            context.startService(Intent(context, FloatingPlayerService::class.java))
        }
    }

    fun enterPip() {
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(Rational(16, 9))
            .build()
        activity.enterPictureInPictureMode(params)
    }

    // Em modo Picture-in-Picture exibe apenas o vídeo em tela cheia da janela
    if (isInPipMode && mediaController != null) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
            AndroidView(
                factory = { ctx -> PlayerView(ctx).apply { player = mediaController; useController = false } },
                update = { view -> view.player = mediaController },
                modifier = Modifier.fillMaxSize()
            )
        }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("FlowTube", fontWeight = FontWeight.Bold) },
                actions = {
                    if (activeTitle != null) {
                        IconButton(onClick = { enterPip() }) {
                            Icon(Icons.Rounded.PictureInPicture, contentDescription = "PiP")
                        }
                        IconButton(onClick = { openFloatingPopup() }) {
                            Icon(Icons.Rounded.OpenInNew, contentDescription = "Janela Flutuante")
                        }
                    }
                }
            )
        },
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    icon = { Icon(Icons.Rounded.Movie, null) },
                    label = { Text("Vídeos") }
                )
                NavigationBarItem(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    icon = { Icon(Icons.Rounded.MusicNote, null) },
                    label = { Text("MP3") }
                )
                NavigationBarItem(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    icon = { Icon(Icons.Rounded.Public, null) },
                    label = { Text("Online") }
                )
                NavigationBarItem(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    icon = { Icon(Icons.Rounded.Favorite, null) },
                    label = { Text("Favoritos") }
                )
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(MaterialTheme.colorScheme.background)
        ) {
            // Player Principal
            if (activeTitle != null && mediaController != null) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(215.dp)
                        .background(Color.Black)
                ) {
                    AndroidView(
                        factory = { ctx -> PlayerView(ctx).apply { player = mediaController } },
                        update = { view -> view.player = mediaController },
                        modifier = Modifier.fillMaxSize()
                    )
                }

                // Controles extras: Velocidade + Sleep Timer + Pop-up
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = activeTitle ?: "",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.labelLarge
                    )
                    TextButton(onClick = {
                        playbackSpeed = when (playbackSpeed) {
                            1.0f -> 1.25f
                            1.25f -> 1.5f
                            1.5f -> 2.0f
                            2.0f -> 0.5f
                            else -> 1.0f
                        }
                        mediaController?.setPlaybackSpeed(playbackSpeed)
                    }) {
                        Text("${playbackSpeed}x")
                    }
                    TextButton(onClick = {
                        sleepMinutes = when (sleepMinutes) {
                            0 -> 15
                            15 -> 30
                            30 -> 60
                            else -> 0
                        }
                    }) {
                        Text(if (sleepMinutes == 0) "Timer" else "${sleepMinutes}m")
                    }
                }
            }

            when (selectedTab) {
                0, 1 -> {
                    OutlinedTextField(
                        value = localSearch,
                        onValueChange = { localSearch = it },
                        placeholder = { Text("Buscar nos ficheiros locais...") },
                        leadingIcon = { Icon(Icons.Rounded.Search, null) },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    )

                    val filtered = remember(localItems, selectedTab, localSearch) {
                        localItems.filter {
                            val tabOk = if (selectedTab == 0) it.isVideo else !it.isVideo
                            val searchOk = localSearch.isBlank() ||
                                it.title.contains(localSearch, ignoreCase = true) ||
                                it.subtitle.contains(localSearch, ignoreCase = true)
                            tabOk && searchOk
                        }
                    }

                    MediaListView(
                        items = filtered,
                        favorites = favorites,
                        onPlay = { playItem(it) },
                        onToggleFav = { favorites = favStorage.toggleFavorite(it) }
                    )
                }

                2 -> {
                    // Busca Online Sem Anúncios (PeerTube) + URL Direta
                    Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = onlineSearch,
                                onValueChange = { onlineSearch = it },
                                placeholder = { Text("Pesquisar vídeos sem anúncios...") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            Button(onClick = {
                                if (onlineSearch.isNotBlank()) {
                                    scope.launch {
                                        isSearchingOnline = true
                                        onlineItems = onlineRepo.searchVideos(onlineSearch)
                                        isSearchingOnline = false
                                    }
                                }
                            }) {
                                Icon(Icons.Rounded.Search, null)
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                value = directUrl,
                                onValueChange = { directUrl = it },
                                placeholder = { Text("Ou cole link direto (.mp4 / .m3u8 / .mp3)") },
                                singleLine = true,
                                modifier = Modifier.weight(1f)
                            )
                            Spacer(Modifier.width(8.dp))
                            FilledTonalButton(onClick = {
                                if (directUrl.isNotBlank()) {
                                    playItem(
                                        MediaItemModel(
                                            id = "url_${directUrl.hashCode()}",
                                            title = "Stream Direto",
                                            subtitle = directUrl.trim(),
                                            streamUrl = directUrl.trim()
                                        )
                                    )
                                }
                            }) {
                                Icon(Icons.Rounded.PlayArrow, null)
                            }
                        }
                    }

                    if (isSearchingOnline) {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }

                    MediaListView(
                        items = onlineItems,
                        favorites = favorites,
                        onPlay = { playItem(it) },
                        onToggleFav = { favorites = favStorage.toggleFavorite(it) }
                    )
                }

                3 -> {
                    if (favorites.isEmpty()) {
                        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text("Ainda não há favoritos salvos.", color = Color.Gray)
                        }
                    } else {
                        MediaListView(
                            items = favorites,
                            favorites = favorites,
                            onPlay = { playItem(it) },
                            onToggleFav = { favorites = favStorage.toggleFavorite(it) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun MediaListView(
    items: List<MediaItemModel>,
    favorites: List<MediaItemModel>,
    onPlay: (MediaItemModel) -> Unit,
    onToggleFav: (MediaItemModel) -> Unit
) {
    LazyColumn(modifier = Modifier.fillMaxSize()) {
        items(items, key = { it.id }) { item ->
            val isFav = favorites.any { it.id == item.id }
            ListItem(
                headlineContent = {
                    Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                supportingContent = {
                    Text(item.subtitle, maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = {
                    if (item.thumbnailUrl.isNotEmpty()) {
                        AsyncImage(
                            model = item.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp, 46.dp)
                                .clip(RoundedCornerShape(8.dp))
                        )
                    } else {
                        Box(
                            modifier = Modifier
                                .size(48.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                if (item.isVideo) Icons.Rounded.Movie else Icons.Rounded.Audiotrack,
                                contentDescription = null
                            )
                        }
                    }
                },
                trailingContent = {
                    IconButton(onClick = { onToggleFav(item) }) {
                        Icon(
                            if (isFav) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            tint = if (isFav) Color(0xFFFF2E63) else Color.Gray,
                            contentDescription = "Favorito"
                        )
                    }
                },
                modifier = Modifier.clickable { onPlay(item) }
            )
        }
    }
}
