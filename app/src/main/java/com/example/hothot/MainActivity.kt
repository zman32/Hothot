//based off last palette version

package com.example.hothot

import android.Manifest
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.os.IBinder
import android.provider.DocumentsContract
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.style.TextOverflow
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.core.content.edit
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.awaitAll
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.withTimeoutOrNull
import com.example.hothot.data.SongDatabase
import com.example.hothot.data.SongEntity

//import androidx.media3.session.MediaSession
//import androidx.media3.ui.PlayerNotificationManager
import androidx.compose.material.icons.filled.Settings
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.res.painterResource

import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.ui.input.pointer.pointerInput

val buttonsColor = Color(0xFF85E813)
val searchTextColor = Color(0xFF000000)
val backgroundColor = Color(0xFF0F110E)
val textColor = Color(0xE6FFFFFF)

data class Song(
    val fileName: String,
    val uri: Uri,
    val title: String,
    val artist: String
)

lateinit var sharedPreferences: SharedPreferences
var currentSongIndex = -1
val recentSongs = mutableStateListOf<Pair<String, Uri>>()
val recentAlbumArts = mutableStateListOf<Bitmap?>()
val displayText = mutableStateOf("Initial Text")
val isShuffleEnabled = mutableStateOf(true)

class MainActivity : ComponentActivity() {
    private var mediaService by mutableStateOf<MediaService?>(null)
    private var serviceBound by mutableStateOf(false)
    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as? MediaService.LocalBinder
            this@MainActivity.mediaService = binder?.getService()
            this@MainActivity.serviceBound = true
            Log.d("MainActivity", "Service connected, serviceBound: $serviceBound, mediaService: $mediaService")
        }
        override fun onServiceDisconnected(name: ComponentName?) {
            this@MainActivity.serviceBound = false
            this@MainActivity.mediaService = null
            Log.d("MainActivity", "Service disconnected, serviceBound: $serviceBound, mediaService: $mediaService")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                1
            )
        }

        sharedPreferences = getSharedPreferences("MusicPlayerPrefs", MODE_PRIVATE)
        isShuffleEnabled.value = sharedPreferences.getBoolean("shuffle_enabled", false)

        loadRecentSongsFromPreferences()

        window.statusBarColor = android.graphics.Color.TRANSPARENT
        window.navigationBarColor = android.graphics.Color.parseColor("#0F110E")
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or View.SYSTEM_UI_FLAG_LAYOUT_STABLE

        // Bind to MediaService
        val intent = Intent(this, MediaService::class.java)
        startService(intent)
        bindService(intent, serviceConnection, Context.BIND_AUTO_CREATE)

        setContent {
            // Pass the state variables from MainActivity
            BootScreen(mediaService = this.mediaService, serviceBound = this.serviceBound)
        }

        // Warm up cache in background
        CoroutineScope(Dispatchers.IO).launch {
            sharedPreferences.getString("lastSelectedFolder", null)?.let { uriString ->
                val uri = Uri.parse(uriString)
                val sortAlpha = sharedPreferences.getString("isAlpha", "no") == "yesSong"
                loadSongsWithRoom(this@MainActivity, uri, sharedPreferences, sortAlpha)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        mediaService?.let { service ->
            sharedPreferences.edit {
                putBoolean("isPlaying", false) // Clear isPlaying to prevent auto-play
                putInt("lastPosition", service.getCurrentPosition())
                apply()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Stop the service when activity is destroyed
        stopService(Intent(this, MediaService::class.java))
        if (serviceBound) {
            unbindService(serviceConnection)
            serviceBound = false
        }
    }

    private fun loadRecentSongsFromPreferences() {
        val savedSongs = sharedPreferences.getString("recentSongs", null)
        if (savedSongs != null) {
            val songList = savedSongs.split(";").mapNotNull { song ->
                val parts = song.split("|")
                if (parts.size == 2) parts[0] to parts[1].toUri() else null
            }
            recentSongs.clear()
            recentSongs.addAll(songList)
        }
    }

    private fun saveRecentSongsToPreferences() {
        val songs = recentSongs.joinToString(";") { "${it.first}|${it.second}" }
        sharedPreferences.edit {
            putString("recentSongs", songs)
            apply()
        }
    }

    suspend fun loadSongsWithRoom(
        context: Context,
        folderUri: Uri,
        sharedPreferences: SharedPreferences,
        sortAlpha: Boolean
    ): List<Song> = withContext(Dispatchers.IO) {
        val db = SongDatabase.getDatabase(context)
        val songDao = db.songDao()
        val folderLastModified = try {
            val docId = DocumentsContract.getTreeDocumentId(folderUri)
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(folderUri, docId)
            context.contentResolver.query(childrenUri, null, null, null, null)?.use { cursor ->
                var lastModified: Long = 0L
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                val modIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
                while (cursor.moveToNext()) {
                    if (modIndex >= 0) {
                        val mod = cursor.getLong(modIndex)
                        if (mod > lastModified) lastModified = mod
                    }
                }
                lastModified
            } ?: 0L
        } catch (_: Exception) { 0L }
        val lastKnownModified = sharedPreferences.getLong("folder_last_modified", 0L)
        if (folderLastModified != lastKnownModified) {
            // Rescan folder and update DB
            val newSongs = mutableListOf<Song>()
            scanFolderForMp3(
                context = context,
                uri = folderUri,
                mp3Files = newSongs,
                errorMessage = mutableStateOf(null),
                sharedPreferences = sharedPreferences,
                onFolderError = {}
            )
            songDao.clearAll()
            songDao.insertAll(newSongs.map {
                SongEntity(
                    uri = it.uri.toString(),
                    fileName = it.fileName,
                    title = it.title,
                    artist = it.artist
                )
            })
            sharedPreferences.edit().putLong("folder_last_modified", folderLastModified).apply()
        }
        val entities = if (sortAlpha) {
            songDao.getAllAlphabetical() // Make sure this query is properly defined in your DAO
        } else {
            songDao.getAll() // Add this query to sort by original order
        }
        entities.map {
            Song(
                fileName = it.fileName,
                uri = Uri.parse(it.uri),
                title = it.title,
                artist = it.artist
            )
        }
    }

    private fun parseSongMetadata(context: Context, fileName: String, fileUri: Uri): Song? {
        return try {
            val retriever = MediaMetadataRetriever()
            retriever.setDataSource(context, fileUri)
            val title = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE) ?: fileName
            val artist = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
            retriever.release()
            Song(
                fileName = fileName,
                uri = fileUri,
                title = title,
                artist = artist
            )
        } catch (e: Exception) {
            null
        }
    }

    private suspend fun scanFolderForMp3(
        context: Context,
        uri: Uri,
        mp3Files: MutableList<Song>,
        errorMessage: MutableState<String?>,
        sharedPreferences: SharedPreferences,
        onFolderError: () -> Unit
    ) = withContext(Dispatchers.IO) {
        try {
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri)
            )
            val startTime = System.currentTimeMillis()
            val newSongs = mutableListOf<Song>()
            contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID
                ),
                null, null, null
            )?.use { cursor ->
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                val idIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                while (cursor.moveToNext()) {
                    val name = cursor.getString(nameIndex)
                    if (name.endsWith(".mp3", ignoreCase = true) || name.endsWith(".m4a", ignoreCase = true)) {
                        val documentId = cursor.getString(idIndex)
                        val fileUri = DocumentsContract.buildDocumentUriUsingTree(uri, documentId)
                        val song: Song? = withTimeoutOrNull(500) {
                            parseSongMetadata(context, name, fileUri)
                        }
                        song?.let { newSongs.add(it) }
                    }
                }
            }
            if (newSongs.isNotEmpty()) {
                //val isAlpha = sharedPreferences.getString("isAlpha", "no") == "yesSong"
                mp3Files.clear()
                mp3Files.addAll(newSongs)
                errorMessage.value = null
                Log.d("MusicPlayer", "Scanned ${newSongs.size} songs (in scanFolderForMp3) in ${System.currentTimeMillis() - startTime}ms")
            } else {
                errorMessage.value = "No MP3 files found in the selected folder. Please try another folder."
                onFolderError()
            }
        } catch (e: Exception) {
            errorMessage.value = "Error scanning folder: ${e.message}. Please try another folder."
            Log.e("MusicPlayer", "Scan error", e)
            onFolderError()
        }
    }

    fun playSongWithService(
        song: Song,
        mp3Files: SnapshotStateList<Song>,
        isShuffle: Boolean,
        indexOf: Int,
        showCurrentView: MutableState<Boolean>? = null,
        currentPlaying: MutableState<Song?>,
        albumArt: MutableState<Bitmap?>? = null,
        context: Context? = null
    ) {
        mediaService?.setPlaylist(mp3Files.map { it.title to it.uri }, isShuffle)
        mediaService?.play(indexOf)
        currentPlaying.value = song
        val editor = sharedPreferences.edit()
        editor.putString("LastSongUri", song.uri.toString())
        editor.putBoolean("isPlaying", true)
        editor.putInt("lastPosition", 0) // Reset position
        editor.apply()
        val pair = song.title to song.uri
        if (recentSongs.none { it.second == song.uri }) {
            recentSongs.add(0, pair)
            if (recentSongs.size > 10) recentSongs.removeAt(recentSongs.size - 1)
            saveRecentSongsToPreferences()
        }
        if (albumArt != null && context != null) {
            CoroutineScope(Dispatchers.IO).launch {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, song.uri)
                    val art = retriever.embeddedPicture
                    val bitmap = art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    withContext(Dispatchers.Main) {
                        albumArt.value = bitmap
                        // Update widget with current song info and album art
                        AlbumArtWidget.updateWidgets(context, bitmap, song.title, song.artist)
                        //update clock widget colors
                        ClockColorWidget.updateWidgets(context, bitmap)
                    }
                } catch (e: Exception) {
                    android.util.Log.e("MusicPlayer", "Error loading album art for ${song.title}", e)
                    withContext(Dispatchers.Main) {
                        albumArt.value = null
                        // Update widget with no album art
                        AlbumArtWidget.updateWidgets(context, null, song.title, song.artist)
                    }
                } finally {
                    retriever.release()
                }
            }
        }
        android.util.Log.d("MusicPlayer2", "playSongWithService: Title=${song.title}, Index=$indexOf, Shuffle=$isShuffle")
    }

    private fun pauseSongWithService() {
        mediaService?.pause()
    }

    private fun togglePlayPauseWithService() {
        mediaService?.playPause()
    }

    private fun nextSongWithService(
        song: Song,
        mp3Files: SnapshotStateList<Song>,
        currentPlaying: MutableState<Song?>,
        showCurrentView: MutableState<Boolean>? = null,
        albumArt: MutableState<Bitmap?>? = null,
        context: Context? = null
    ) {
        val currentIndex = mp3Files.indexOf(song)
        val nextIndex = if (isShuffleEnabled.value) {
            (0 until mp3Files.size).random()
        } else {
            (currentIndex + 1) % mp3Files.size
        }
        val nextSong = mp3Files.getOrNull(nextIndex) ?: return
        playSongWithService(
            song = nextSong,
            mp3Files = mp3Files,
            isShuffle = isShuffleEnabled.value,
            indexOf = nextIndex,
            showCurrentView = showCurrentView,
            currentPlaying = currentPlaying,
            albumArt = albumArt,
            context = context
        )
        Log.d("MusicPlayer2", "nextSongWithService: Playing ${nextSong.title}, Index=$nextIndex")
    }

    private fun previousSongWithService(
        song: Song,
        mp3Files: SnapshotStateList<Song>,
        currentPlaying: MutableState<Song?>,
        showCurrentView: MutableState<Boolean>? = null,
        albumArt: MutableState<Bitmap?>? = null,
        context: Context? = null
    ) {
        val currentIndex = mp3Files.indexOf(song)
        val prevIndex = if (isShuffleEnabled.value) {
            (0 until mp3Files.size).random()
        } else {
            (currentIndex - 1 + mp3Files.size) % mp3Files.size
        }
        val prevSong = mp3Files.getOrNull(prevIndex) ?: return
        playSongWithService(
            song = prevSong,
            mp3Files = mp3Files,
            isShuffle = isShuffleEnabled.value,
            indexOf = prevIndex,
            showCurrentView = showCurrentView,
            currentPlaying = currentPlaying,
            albumArt = albumArt,
            context = context
        )
        Log.d("MusicPlayer2", "previousSongWithService: Playing ${prevSong.title}, Index=$prevIndex")
    }

    private fun setShuffleWithService(enabled: Boolean) {
        mediaService?.setShuffle(enabled)
    }

    @Composable
    fun BootScreen(mediaService: MediaService?, serviceBound: Boolean) {
        val context = LocalContext.current
        val sharedPreferences = remember { context.getSharedPreferences("MusicPlayerPrefs", MODE_PRIVATE) }
        val hasSelectedFolder = remember { mutableStateOf(sharedPreferences.getString("lastSelectedFolder", null) != null) }
        val currentPlaying = remember { mutableStateOf<Song?>(null) }
        val albumArt = remember { mutableStateOf<Bitmap?>(null) }
        val albumArtColors = remember { mutableStateOf(PaletteUtil.extractColors(albumArt.value)) }
        val albumArtTrigger = remember { mutableStateOf(0) } // Trigger for carousel reload
        // Update albumArtColors whenever albumArt changes
        LaunchedEffect(albumArt.value) {
            albumArtColors.value = PaletteUtil.extractColors(albumArt.value)
        }
        val errorMessage = remember { mutableStateOf<String?>(null) }

        if (hasSelectedFolder.value) {
            MusicListScreen(
                errorMessage = errorMessage,
                onSettingsClick = {
                    sharedPreferences.edit {
                        remove("lastSelectedFolder")
                        remove("cachedSongs")
                        remove("cachedFolderUri")
                        remove("cacheTimestamp")
                        apply()
                    }
                    hasSelectedFolder.value = false
                    errorMessage.value = null
                },
                onFolderError = {
                    sharedPreferences.edit {
                        remove("lastSelectedFolder")
                        remove("cachedSongs")
                        remove("cachedFolderUri")
                        remove("cacheTimestamp")
                        apply()
                    }
                    hasSelectedFolder.value = false
                },
                mediaService = mediaService,
                serviceBound = serviceBound
            )
        } else {
            SelectMusicFolder(
                errorMessage = errorMessage,
                onFolderSelected = { hasSelectedFolder.value = true }
            )
        }
    }

    @Composable
    fun SelectMusicFolder(
        errorMessage: MutableState<String?>, // Receive error state
        onFolderSelected: () -> Unit
    ) {
        val context = LocalContext.current
        val sharedPreferences = remember { context.getSharedPreferences("MusicPlayerPrefs", MODE_PRIVATE) }

        val folderPickerLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.OpenDocumentTree()
        ) { uri ->
            if (uri != null) {
                try {
                    // Persist permission
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                    val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                        uri,
                        DocumentsContract.getTreeDocumentId(uri)
                    )
                    context.contentResolver.query(
                        childrenUri,
                        arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                        null, null, null
                    )?.use {
                        sharedPreferences.edit {
                            putString("lastSelectedFolder", uri.toString())
                            apply()
                        }
                        errorMessage.value = null // Clear any previous error
                        onFolderSelected()
                    } ?: run {
                        errorMessage.value = "Couldn't access folder. Please try another one."
                    }
                } catch (e: Exception) {
                    errorMessage.value = "Error accessing folder: ${e.message}"
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "First Time Huh?",
                    color = textColor,
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.padding(bottom = 16.dp)
                )
                Button(
                    onClick = { folderPickerLauncher.launch(null) },
                    colors = ButtonDefaults.buttonColors(containerColor = buttonsColor),
                    shape = RectangleShape,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 32.dp)
                        .clip(RoundedCornerShape(1.dp))
                ) {
                    Text("Select Music Folder", color = backgroundColor, fontWeight = FontWeight.Bold)
                }
                errorMessage.value?.let {
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = it,
                        color = Color.Red,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp)
                    )
                }
            }
        }
    }

    @Composable
    fun MusicListScreen(
        errorMessage: MutableState<String?>, // Receive error state
        onSettingsClick: () -> Unit,
        onFolderError: () -> Unit, // Callback to handle scan errors
        mediaService: MediaService?,
        serviceBound: Boolean
    ) {
        // Add this state for dialog visibility
        val showSettingsDialog = remember { mutableStateOf(false) }
        val showCurrentView = remember { mutableStateOf(false) }
        val showSearchDialog = remember { mutableStateOf(false) }

        val context = LocalContext.current
        val mp3Files = remember { mutableStateListOf<Song>() }
        val currentPlaying = remember { mutableStateOf<Song?>(null) }
        val albumArt = remember { mutableStateOf<Bitmap?>(null) }
        val albumArtColors = remember { mutableStateOf(PaletteUtil.extractColors(albumArt.value)) }
        val albumArtTrigger = remember { mutableStateOf(0) } // Trigger for carousel reload
        // Update albumArtColors whenever albumArt changes
        LaunchedEffect(albumArt.value) {
            albumArtColors.value = PaletteUtil.extractColors(albumArt.value)
        }
        val selectedFolderUri = remember { mutableStateOf<Uri?>(null) }

        val isLoading = remember { mutableStateOf(true) }
        val lazyListState = rememberLazyListState()
        val coroutineScope = rememberCoroutineScope()

        val shouldShowNowPlaying = remember {
            mutableStateOf(sharedPreferences.getBoolean("isPlaying", false))
        }
        val isPlayingState = remember { mutableStateOf(false) }
        // Removed polling LaunchedEffect

        val backgroundColor = Color(0xFF0F110E)
        val rowBackgroundColor = Color(0xFF1D1F1B)

        // Extract colors from album art
        val gradientBrush = Brush.verticalGradient(
            colors = listOf(
                albumArtColors.value.vibrant ?: Color(0xFF416077), // Use Vibrant color or default
                backgroundColor
            )
        )

        val textColor = Color(0xE6FFFFFF)

        // Function to handle song change broadcasts
        fun handleSongChange(uriString: String, mp3Files: SnapshotStateList<Song>, currentPlaying: MutableState<Song?>, albumArt: MutableState<Bitmap?>, context: Context?) {
            val song = mp3Files.find { it.uri.toString() == uriString } ?: return
            if (song == currentPlaying.value) return // Prevent redundant updates

            currentPlaying.value = song
            sharedPreferences.edit {
                putString("LastSongUri", song.uri.toString())
                putBoolean("isPlaying", true)
                apply()
            }

            CoroutineScope(Dispatchers.IO).launch {
                val retriever = MediaMetadataRetriever()
                try {
                    retriever.setDataSource(context, song.uri)
                    val art = retriever.embeddedPicture
                    val bitmap = art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                    withContext(Dispatchers.Main) {
                        albumArt.value = bitmap
                    }
                } catch (e: Exception) {
                    withContext(Dispatchers.Main) {
                        albumArt.value = null
                    }
                } finally {
                    retriever.release()
                }
            }

            val pair = song.title to song.uri
            if (recentSongs.none { it.second == song.uri }) {
                recentSongs.add(0, pair)
                if (recentSongs.size > 10) recentSongs.removeAt(recentSongs.size - 1)
                saveRecentSongsToPreferences()
            }

            Log.d("MusicPlayer2", "BroadcastReceiver updated song: Title=${song.title}, Uri=${song.uri}")
        }

        // Register BroadcastReceiver for song changes
        DisposableEffect(Unit) { // Simplified key for song change receiver
            val songChangedReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    val uriString = intent?.getStringExtra("songUri") ?: return
                    handleSongChange(uriString, mp3Files, currentPlaying, albumArt, context)
                }
            }
            ContextCompat.registerReceiver(
                context,
                songChangedReceiver,
                IntentFilter("com.example.hothot.ACTION_SONG_CHANGED"),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            onDispose {
                context.unregisterReceiver(songChangedReceiver)
            }
        }

        // Register BroadcastReceiver for playback state changes
        DisposableEffect(context) { // Key on context
            val playbackStateReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == MediaService.ACTION_PLAYBACK_STATE_CHANGED) {
                        val isPlaying = intent.getBooleanExtra(MediaService.EXTRA_IS_PLAYING, false)
                        isPlayingState.value = isPlaying
                        Log.d("MainActivity", "BroadcastReceiver: Received ACTION_PLAYBACK_STATE_CHANGED, new isPlayingState: $isPlaying. Current song: ${currentPlaying.value?.title}")
                    }
                }
            }
            val intentFilter = IntentFilter(MediaService.ACTION_PLAYBACK_STATE_CHANGED)
            ContextCompat.registerReceiver(context, playbackStateReceiver, intentFilter, ContextCompat.RECEIVER_NOT_EXPORTED)

            onDispose {
                context.unregisterReceiver(playbackStateReceiver)
            }
        }

        // Restore playback state immediately
        LaunchedEffect(Unit) { // This runs once when MusicListScreen is first composed
            val lastSongUri = sharedPreferences.getString("LastSongUri", null)
            if (lastSongUri != null) {
                val song = mp3Files.find { it.uri.toString() == lastSongUri }
                if (song != null) {
                    currentPlaying.value = song
                    // Do not set showCurrentView.value = true
                }
            }
            restorePlaybackState(context, currentPlaying, albumArt, sharedPreferences)
            withContext(Dispatchers.Main) {
                isLoading.value = false
            }
        }

        // Initialize isPlayingState when mediaService is available
        LaunchedEffect(mediaService, serviceBound) { // Keyed by the passed parameters
            if (serviceBound && mediaService != null) {
                // Service is connected, get the initial state
                isPlayingState.value = mediaService.isPlaying()
                Log.d("MainActivity", "InitialStateSync: MediaService available. Initial isPlayingState set to: ${isPlayingState.value}. Current song: ${currentPlaying.value?.title}")
            } else {
                // Optionally, handle the case where service is not bound or null, e.g., set isPlayingState to false
                // isPlayingState.value = false
                Log.d("MainActivity", "InitialStateSync: MediaService not available or not bound. serviceBound: $serviceBound, mediaService: $mediaService")
            }
        }

        // Load songs in background
        LaunchedEffect(Unit) { // This also runs once when MusicListScreen is first composed
            val startTime = System.currentTimeMillis()

            withContext(Dispatchers.IO) {
                sharedPreferences.getString("lastSelectedFolder", null)?.let { uriString ->
                    val uri = Uri.parse(uriString)
                    selectedFolderUri.value = uri

                    val sortAlpha = sharedPreferences.getString("isAlpha", "no") == "yesSong"
                    val songs = loadSongsWithRoom(context, uri, sharedPreferences, sortAlpha)

                    mp3Files.clear()
                    if (songs.isEmpty()) {
                        errorMessage.value = "No MP3 files found in the selected folder. Please try another folder."
                        onFolderError()
                        return@withContext
                    }
                    mp3Files.addAll(songs)
                }
            }
        }

        // Handle back button press
        BackHandler(enabled = showCurrentView.value) {
            showCurrentView.value = false
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(backgroundColor)
        ) {
            if (isLoading.value) {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    CircularProgressIndicator(color = buttonsColor)
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Loading music files...",
                        color = textColor,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            } else {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(
                            bottom = if (!showCurrentView.value && currentPlaying.value != null) 70.dp else 0.dp
                        ) // Reserve space for NowPlayingBottomBar
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(brush = gradientBrush)
                            .padding(
                                top = WindowInsets.systemBars.asPaddingValues().calculateTopPadding() + 18.dp,
                                bottom = 5.dp,
                                start = 8.dp,
                                end = 8.dp
                            )
                    ) {
                        if (mp3Files.isNotEmpty()) {
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "Recently Played",
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    color = textColor,
                                    modifier = Modifier
                                        .clickable { onSettingsClick() } // Add this click handler
                                        .padding(vertical = 8.dp)
                                )
                                Row(
                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    IconButton(
                                        //onClick = { onSettingsClick() },
                                        onClick = {  showSettingsDialog.value = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Settings,
                                            contentDescription = "Settings",
                                            tint = textColor
                                        )
                                    }
                                    IconButton(
                                        onClick = { showSearchDialog.value = true },
                                        modifier = Modifier.size(32.dp)
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Search,
                                            contentDescription = "Search",
                                            tint = textColor
                                        )
                                    }
                                }
                            }

                            val carouselItems = remember {
                                derivedStateOf {
                                    val needed = 10 - recentSongs.size
                                    val extra = if (needed > 0) mp3Files.shuffled().take(needed) else emptyList()
                                    (recentSongs + extra.map { it.title to it.uri }).take(10)
                                }
                            }

                            LazyRow(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(160.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(carouselItems.value.size) { index ->
                                    val (name, uri) = carouselItems.value[index]
                                    val song = mp3Files.find { it.uri == uri } ?: return@items

                                    Box(
                                        modifier = Modifier
                                            .width(160.dp)
                                            .clickable
                                                (
                                                interactionSource = remember { MutableInteractionSource() },
                                                indication = null  // Disables ripple
                                            )
                                            {
                                                playSongWithService(
                                                    song = song,
                                                    mp3Files = mp3Files,
                                                    isShuffle = isShuffleEnabled.value,
                                                    indexOf = mp3Files.indexOf(song),
                                                    currentPlaying = currentPlaying,
                                                    albumArt = albumArt,
                                                    context = context
                                                )
                                            }
                                    ) {
                                        val bitmap = remember(uri) { mutableStateOf<Bitmap?>(null) }
                                        val isLoading = remember(uri) { mutableStateOf(true) }

                                        LaunchedEffect(uri) {
                                            isLoading.value = true
                                            withContext(Dispatchers.IO) {
                                                val retriever = MediaMetadataRetriever()
                                                try {
                                                    retriever.setDataSource(context, uri)
                                                    val art = retriever.embeddedPicture
                                                    withContext(Dispatchers.Main) {
                                                        bitmap.value = art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                                    }
                                                } catch (e: Exception) {
                                                    Log.e("MusicPlayer", "Error loading album art for $name", e)
                                                } finally {
                                                    retriever.release()
                                                    isLoading.value = false
                                                }
                                            }
                                        }

                                        when {
                                            isLoading.value -> {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .background(Color.DarkGray.copy(alpha = 0.5f)),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    CircularProgressIndicator(
                                                        modifier = Modifier.size(24.dp),
                                                        color = buttonsColor,
                                                        strokeWidth = 2.dp
                                                    )
                                                }
                                            }
                                            bitmap.value != null -> {
                                                Image(
                                                    bitmap = bitmap.value!!.asImageBitmap(),
                                                    contentDescription = song.title,
                                                    modifier = Modifier
                                                        .fillMaxSize()
                                                        .clip(RoundedCornerShape(26.dp))
                                                )
                                            }
                                            else -> {
                                                Box(
                                                    modifier = Modifier
                                                        .fillMaxSize(),
                                                    contentAlignment = Alignment.Center
                                                ) {
                                                    Image(
                                                        painter = painterResource(id = R.drawable.blank_album_art), // Replace with your image resource
                                                        contentDescription = "Default album art",
                                                        modifier = Modifier
                                                            .fillMaxSize()
                                                            .clip(RoundedCornerShape(26.dp))
                                                    )
                                                }
                                            }
                                        }
                                    }
                                }
                            }

                            Spacer(modifier = Modifier.height(16.dp))
                        }
                    }

                    errorMessage.value?.let {
                        Text(
                            text = it,
                            color = textColor,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(8.dp)
                        )
                    }

                    if (showCurrentView.value && currentPlaying.value != null) {
                        val song = currentPlaying.value!!
                        val isPlaying = mediaService?.isPlaying() ?: false

                        CurrentSongView(
                            songName = song.title,
                            artistName = song.artist,
                            albumArt = albumArt.value,
                            isPlaying = isPlaying,
                            onPlay = { togglePlayPauseWithService() },
                            onPause = { togglePlayPauseWithService() },
                            onPrevious = {
                                previousSongWithService(
                                    song = song,
                                    mp3Files = mp3Files,
                                    currentPlaying = currentPlaying,
                                    albumArt = albumArt,
                                    context = context
                                )
                            },
                            onNext = {
                                nextSongWithService(
                                    song = song,
                                    mp3Files = mp3Files,
                                    currentPlaying = currentPlaying,
                                    albumArt = albumArt,
                                    context = context
                                )
                                Log.d("MusicPlayer2", "onNext called current song: Title=${song.title}, Uri=${song.uri}")
                            },
                            onStop = {
                                pauseSongWithService()
                                showCurrentView.value = false
                            },
                            onBack = { showCurrentView.value = false },
                            backgroundColor = backgroundColor,
                            buttonColor = buttonsColor,
                            textColor = textColor
                        )
                    } else {
                        LazyColumn(
                            state = lazyListState,
                            modifier = Modifier
                                .weight(1f)
                                .padding(horizontal = 16.dp)
                        ) {
                            items(
                                count = mp3Files.size,
                                key = { index -> mp3Files[index].uri.toString() }
                            ) { index ->
                                val song = mp3Files[index]
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 5.dp)
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(rowBackgroundColor)
                                        .height(60.dp)
                                        .clickable(
                                            interactionSource = remember { MutableInteractionSource() },
                                            indication = null
                                        ) {
                                            playSongWithService(
                                                song,
                                                mp3Files,
                                                isShuffleEnabled.value,
                                                indexOf = index,
                                                showCurrentView = showCurrentView,
                                                currentPlaying = currentPlaying,
                                                albumArt = albumArt,
                                                context = context
                                            )
                                        }
                                ) {
                                    val bitmap = remember(song.uri) {
                                        mutableStateOf<Bitmap?>(null)
                                    }
                                    LaunchedEffect(song.uri) {
                                        withContext(Dispatchers.IO) {
                                            val retriever = MediaMetadataRetriever()
                                            try {
                                                retriever.setDataSource(context, song.uri)
                                                val art = retriever.embeddedPicture
                                                bitmap.value = art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                                            } catch (e: Exception) {
                                                Log.e("MusicPlayer", "Error loading album art for ${song.title}", e)
                                            } finally {
                                                retriever.release()
                                            }
                                        }
                                    }

                                    if (bitmap.value != null) {
                                        Image(
                                            bitmap = bitmap.value!!.asImageBitmap(),
                                            contentDescription = "Album Art",
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .aspectRatio(1f)
                                        )
                                    } else {
                                        Image(
                                            painter = painterResource(id = R.drawable.blank_album_art),
                                            contentDescription = "Default album art",
                                            modifier = Modifier
                                                .fillMaxHeight()
                                                .aspectRatio(1f)
                                        )
                                    }

                                    Spacer(modifier = Modifier.width(12.dp))

                                    Column {
                                        Text(
                                            text = song.title,
                                            fontSize = 16.sp,
                                            fontWeight = FontWeight.Bold,
                                            color = textColor
                                        )
                                        Text(
                                            text = song.artist,
                                            fontSize = 14.sp,
                                            color = textColor.copy(alpha = 0.7f)
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }

            // Register BroadcastReceiver for song changes
            DisposableEffect(mp3Files, currentPlaying, albumArt, recentSongs) {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        val uriString = intent?.getStringExtra("songUri") ?: return
                        val song = mp3Files.find { it.uri.toString() == uriString }
                        if (song != null) {
                            currentPlaying.value = song

                            sharedPreferences.edit {
                                putString("LastSongUri", song.uri.toString())
                                putBoolean("isPlaying", true)
                                apply()
                            }

                            kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                                val retriever = android.media.MediaMetadataRetriever()
                                try {
                                    retriever.setDataSource(context, song.uri)
                                    val art = retriever.embeddedPicture
                                    val bitmap = art?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        albumArt.value = bitmap
                                    }
                                } catch (_: Exception) {
                                    withContext(kotlinx.coroutines.Dispatchers.Main) {
                                        albumArt.value = null
                                    }
                                } finally {
                                    retriever.release()
                                }
                            }

                            val pair = song.title to song.uri
                            if (recentSongs.none { it.second == song.uri }) {
                                recentSongs.add(0, pair)
                                if (recentSongs.size > 10) recentSongs.removeAt(recentSongs.size - 1)
                                saveRecentSongsToPreferences()
                            }

                            Log.d("MusicPlayer2", "BroadcastReceiver updated song: Title=${song.title}, Uri=${song.uri}")
                        }
                    }
                }
                ContextCompat.registerReceiver(
                    context,
                    receiver,
                    IntentFilter("com.example.hothot.ACTION_SONG_CHANGED"),
                    ContextCompat.RECEIVER_NOT_EXPORTED
                )
                onDispose {
                    context.unregisterReceiver(receiver)
                }
            }

            if (!showCurrentView.value && currentPlaying.value != null) {
                val song = currentPlaying.value!!
                NowPlayingBottomBar(
                    fileName = song.fileName,
                    songName = song.title,
                    artist = song.artist,
                    isPlaying = isPlayingState.value,
                    albumArt = albumArt,
                    currentPlaying = currentPlaying,
                    mp3Files = mp3Files,
                    onPlayPause = { playing -> if (playing) togglePlayPauseWithService() else pauseSongWithService() },
                    onClick = { showCurrentView.value = true },
                    albumArtColors = albumArtColors.value,
                    modifier = Modifier.align(Alignment.BottomCenter)
                )
            }


            if (showSettingsDialog.value) {
                SettingsDialog(
                    onDismiss = { showSettingsDialog.value = false },
                    onSettingsChanged = { isAlpha ->

                        sharedPreferences.edit {
                            putString("isAlpha", if (isAlpha) "yesSong" else "no")
                            //   remove("folder_last_modified") // Force rescan
                            apply()
                        }

                        coroutineScope.launch {
                            val folderUri = selectedFolderUri.value ?: return@launch
                            val songs = loadSongsWithRoom(context, folderUri, sharedPreferences, isAlpha)
                            withContext(Dispatchers.Main) {
                                mp3Files.clear()
                                mp3Files.addAll(songs)
                                lazyListState.scrollToItem(0) // Scroll to top after refresh
                                Log.d("Sorting", "CLICKED SORT Loading songs with alpha sort: $isAlpha")
                                //Log.d("Sorting", "First song: ${songs.firstOrNull()?.title}")
                            }
                        }
                    }
                )
            }

            if (showSearchDialog.value) {
                SearchDialog(
                    mp3Files = mp3Files,
                    onDismiss = { showSearchDialog.value = false },
                    onSongSelected = { song ->
                        playSongWithService(
                            song,
                            mp3Files,
                            isShuffleEnabled.value,
                            indexOf = mp3Files.indexOf(song),
                            showCurrentView = showCurrentView,
                            currentPlaying = currentPlaying,
                            albumArt = albumArt,
                            context = context
                        )
                        showSearchDialog.value = false
                    }
                )
            }
        }
    }

    private suspend fun restorePlaybackState(
        context: Context,
        currentPlaying: MutableState<Song?>,
        albumArt: MutableState<Bitmap?>,
        sharedPreferences: SharedPreferences
    ) = withContext(Dispatchers.IO) {
        sharedPreferences.getString("LastSongUri", null)?.let { uriString ->
            val uri = Uri.parse(uriString)
            val sortAlpha = sharedPreferences.getString("isAlpha", "no") == "yesSong"
            val songs = loadSongsWithRoom(context, Uri.parse(sharedPreferences.getString("lastSelectedFolder", "")!!), sharedPreferences, sortAlpha)
            songs.find { it.uri == uri }?.let { song ->
                withContext(Dispatchers.Main) {
                    currentPlaying.value = song
                }
                // Prepare MediaService with this song and last position, but do not auto-play
                val lastPosition = sharedPreferences.getInt("lastPosition", 0)
                val intent = Intent(context, MediaService::class.java).apply {
                    action = "PREPARE_SONG"
                    putExtra("songUri", song.uri.toString())
                    putExtra("position", lastPosition)
                }
                context.startService(intent)
                launch {
                    val retriever = MediaMetadataRetriever()
                    try {
                        retriever.setDataSource(context, uri)
                        val art = retriever.embeddedPicture
                        withContext(Dispatchers.Main) {
                            albumArt.value = art?.let { BitmapFactory.decodeByteArray(it, 0, it.size) }
                        }
                    } finally {
                        retriever.release()
                    }
                }
            }
        }
    }

    @Composable
    fun SearchDialog(
        mp3Files: List<Song>,
        onDismiss: () -> Unit,
        onSongSelected: (Song) -> Unit
    ) {
        var searchQuery by remember { mutableStateOf("") }
        val searchResults = remember { mutableStateListOf<Song>() }
        val coroutineScope = rememberCoroutineScope()

        LaunchedEffect(searchQuery) {
            coroutineScope.launch {
                delay(300)
                searchResults.clear()
                if (searchQuery.isNotBlank()) {
                    searchResults.addAll(
                        mp3Files.filter {
                            it.title.contains(searchQuery, ignoreCase = true) ||
                                    it.artist.contains(searchQuery, ignoreCase = true)
                        }
                    )
                }
            }
        }

        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Search Songs",
                    color = textColor,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Column {
                    TextField(
                        value = searchQuery,
                        onValueChange = { searchQuery = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Color(0xFF1D1F1B), RoundedCornerShape(8.dp))
                            .padding(8.dp)
                            .border(1.dp, textColor.copy(alpha = 0.5f), RoundedCornerShape(8.dp)),
                        placeholder = {
                            Text(
                                text = "Search by song or artist",
                                color = searchTextColor
                            )
                        },
                        textStyle = LocalTextStyle.current.copy(
                            color = searchTextColor,
                            fontSize = 16.sp
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    LazyColumn(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(max = 200.dp)
                    ) {
                        items(searchResults.size) { index ->
                            val song = searchResults[index]
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable {
                                        onSongSelected(song)
                                    }
                                    .padding(8.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column {
                                    Text(
                                        text = song.title,
                                        color = textColor,
                                        fontWeight = FontWeight.Bold,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                    Text(
                                        text = song.artist,
                                        color = textColor.copy(alpha = 0.7f),
                                        fontSize = 12.sp,
                                        maxLines = 1,
                                        overflow = TextOverflow.Ellipsis
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel", color = buttonsColor)
                }
            },
            containerColor = backgroundColor,
            textContentColor = textColor
        )
    }

    @Composable
    fun SettingsDialog(
        onDismiss: () -> Unit,
        onSettingsChanged: (Boolean) -> Unit
    ) {
        val context = LocalContext.current
        val sharedPreferences = remember { context.getSharedPreferences("MusicPlayerPrefs", MODE_PRIVATE) }
        //  var isAlpha by remember { mutableStateOf(sharedPreferences.getString("isAlpha", "no") == "yesSong") }
        var isAlpha by remember { mutableStateOf(sharedPreferences.getString("isAlpha", "no") == "yesSong") }
        AlertDialog(
            onDismissRequest = onDismiss,
            title = {
                Text(
                    text = "Settings",
                    color = textColor,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(
                            checked = isAlpha,
                            onCheckedChange = { isAlpha = it }, // Only update local state
                            colors = CheckboxDefaults.colors(
                                checkedColor = buttonsColor,
                                uncheckedColor = textColor.copy(alpha = 0.6f)
                            )
                        )
                        Text(
                            text = "Sort songs alphabetically",
                            color = textColor,
                            modifier = Modifier.padding(start = 8.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        sharedPreferences.edit {
                            putString("isAlpha", if (isAlpha) "yesSong" else "no")
                            apply()
                        }
                        onSettingsChanged(isAlpha) // Notify parent of change
                        onDismiss()
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = buttonsColor
                    ),
                    modifier = Modifier.padding(end = 8.dp)
                ) {
                    Text("DONE")
                }
            },
            containerColor = backgroundColor,
            textContentColor = textColor
        )
    }

    @Composable
    fun NowPlayingBottomBar(
        fileName: String,
        songName: String,
        artist: String,
        isPlaying: Boolean,
        albumArt: MutableState<Bitmap?>,
        currentPlaying: MutableState<Song?>,
        mp3Files: MutableList<Song>,
        onPlayPause: (Boolean) -> Unit,
        onClick: () -> Unit,
        modifier: Modifier = Modifier,
        albumArtColors: PaletteUtil.AlbumArtColors
    ) {
        Log.d("MainActivity", "NowPlayingBottomBar recomposing. isPlaying parameter: $isPlaying. Current song: $songName")
        val backgroundColor = Color(0xCC1D1F1B)
        val textColor = Color.White
        val context = LocalContext.current

        Row(
            modifier = modifier
                .fillMaxWidth()
                .height(70.dp)
                .clickable { onClick() }
                .pointerInput(Unit) {
                    detectVerticalDragGestures { change, dragAmount ->
                        // Detect swipe up
                        if (dragAmount < -20f) { // negative = upwards swipe
                            onClick()
                        }
                    }
                }
                .background(
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            Color(0xFF343135),
                            albumArtColors.mutedVibrant ?: Color(0xFF416077),
                            Color(0xFF343135)
                        )
                    ),
                    shape = RoundedCornerShape(
                        topStart = 8.dp,
                        topEnd = 8.dp,
                        bottomEnd = 2.dp,
                        bottomStart = 2.dp
                    )
                )
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            if (albumArt.value != null) {
                Image(
                    bitmap = albumArt.value!!.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier
                        .size(54.dp)
                        .clip(RoundedCornerShape(6.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.blank_album_art),
                        contentDescription = "Default album art",
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                    )
                }
            }
            Spacer(modifier = Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = songName,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                    maxLines = 1,
                    overflow = TextOverflow.Clip,
                    modifier = Modifier
                        .width(240.dp)
                        .then(
                            if (songName.length > 5) Modifier.basicMarquee(
                                iterations = Int.MAX_VALUE,
                                initialDelayMillis = 1000,
                                velocity = 50.dp
                            ) else Modifier
                        )
                )
                Text(
                    text = artist,
                    fontSize = 12.sp,
                    color = Color.White.copy(alpha = 0.7f),
                    maxLines = 1
                )
            }

            IconButton(
                onClick = {
                    isShuffleEnabled.value = !isShuffleEnabled.value
                    setShuffleWithService(isShuffleEnabled.value)
                    sharedPreferences.edit {
                        putBoolean("shuffle_enabled", isShuffleEnabled.value)
                    }
                    displayText.value = "Shuffle: ${isShuffleEnabled.value}"
                }
            ) {
                Icon(
                    imageVector = Icons.Default.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (isShuffleEnabled.value) Color.White else Color.White.copy(alpha = 0.3f)
                )
            }

            IconButton(onClick = {
                Log.d("MainActivity", "NowPlayingBottomBar: onPlayPause clicked. Current isPlaying parameter (before toggle): $isPlaying")
                togglePlayPauseWithService()
            }) {
                Icon(
                    imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                    contentDescription = if (isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(32.dp),
                    tint = Color.White
                )
            }
        }
    }
}