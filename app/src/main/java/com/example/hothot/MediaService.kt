package com.example.hothot

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import androidx.core.content.edit
import com.example.hothot.data.SongDatabase
import kotlinx.coroutines.runBlocking
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log
import android.widget.RemoteViews
import androidx.palette.graphics.Palette
import com.example.hothot.R
import kotlin.math.pow
import kotlin.math.sqrt
import kotlin.math.pow
import kotlin.math.max
import kotlin.math.min
import android.graphics.Color
import androidx.core.content.ContextCompat
import java.util.Locale
import androidx.core.graphics.ColorUtils
import androidx.annotation.ColorInt
import androidx.annotation.DrawableRes
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.LinearGradient
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import android.util.TypedValue

import android.graphics.Typeface


class MediaService : Service(), MediaPlayer.OnCompletionListener, MediaPlayer.OnPreparedListener, MediaPlayer.OnErrorListener {
    private val binder = LocalBinder()
     var mediaPlayer: MediaPlayer? = null
    private val playlist = mutableListOf<Pair<String, Uri>>()
    private var currentIndex = 0
    private var shuffleEnabled = false
    private var isPrepared = false
    private val sharedPreferences by lazy { getSharedPreferences("MusicPlayerPrefs", MODE_PRIVATE) }

    override fun onCreate() {
        super.onCreate()
        mediaPlayer = MediaPlayer().apply {
            setWakeMode(applicationContext, PowerManager.PARTIAL_WAKE_LOCK)
            setOnCompletionListener(this@MediaService)
            setOnPreparedListener(this@MediaService)
            setOnErrorListener(this@MediaService)
        }
        shuffleEnabled = sharedPreferences.getBoolean("shuffle_enabled", false)
    }

    override fun onBind(intent: Intent?): IBinder = binder

    inner class LocalBinder : Binder() {
        fun getService(): MediaService = this@MediaService
    }

    fun setPlaylist(songs: List<Pair<String, Uri>>, shuffle: Boolean) {
        playlist.clear()
        playlist.addAll(songs)
        shuffleEnabled = shuffle
        // Do not reset currentIndex here
    }

    fun play(index: Int = 0) {
        android.util.Log.d("MediaService", "play called, playlist size: ${playlist.size}, index: $index")
        if (playlist.isNotEmpty()) {
            currentIndex = index.coerceIn(0, playlist.size - 1)
            playCurrent()
        } else {
            android.util.Log.w("MediaService", "play called but playlist is empty!")
        }
    }

    fun getCurrentPosition(): Int {
        return mediaPlayer?.currentPosition ?: 0
    }

    fun playPause() {
        android.util.Log.d("MediaService", "playPause called. isPlaying=${mediaPlayer?.isPlaying}, isPrepared=$isPrepared, currentIndex=$currentIndex, playlistSize=${playlist.size}")
        if (mediaPlayer?.isPlaying == true) {
            pause()
        } else {
            if (isPrepared) {
                android.util.Log.d("MediaService", "Resuming playback with start()")
                val lastPosition = sharedPreferences.getInt("lastPosition", 0)
                if (lastPosition > 0) {
                    mediaPlayer?.seekTo(lastPosition)
                    android.util.Log.d("MediaService", "Seeked to lastPosition: $lastPosition")
                }
                mediaPlayer?.start()
                sharedPreferences.edit {
                    putBoolean("isPlaying", true)
                    apply()
                }
            } else if (playlist.isNotEmpty()) {
                android.util.Log.d("MediaService", "Not prepared, calling play($currentIndex)")
                play(currentIndex)
            } else {
                val lastSongUri = sharedPreferences.getString("LastSongUri", null)
                if (lastSongUri != null) {
                    val roomSongs = loadPlaylistFromRoom()
                    if (roomSongs.isNotEmpty()) {
                        setPlaylist(roomSongs, shuffleEnabled)
                        val index = roomSongs.indexOfFirst { it.second.toString() == lastSongUri }
                        if (index >= 0) {
                            currentIndex = index
                            prepareSongWithoutPlaying(Uri.parse(lastSongUri))
                        }
                    }
                } else {
                    android.util.Log.w("MediaService", "Cannot play: playlist empty and no LastSongUri")
                    Toast.makeText(this, "No song to play", Toast.LENGTH_SHORT).show()
                }
            }
        }

        updateNotification() // Add this line
    }

    fun pause() {
        mediaPlayer?.pause()
        sharedPreferences.edit {
            putBoolean("isPlaying", false)
            putInt("lastPosition", mediaPlayer?.currentPosition ?: 0)
            apply()
        }

        updateNotification() // Add this line
    }

    fun next() {
        if (playlist.isEmpty()) return
        currentIndex = if (shuffleEnabled) (playlist.indices - currentIndex).random() else (currentIndex + 1) % playlist.size
        sharedPreferences.edit {
            putInt("lastPosition", 0) // Reset position to start from beginning
            putBoolean("isPlaying", true) // Ensure playback starts
            apply()
        }
        playCurrent()
        updateWidgetWithCurrentSong()
        updateNotification()
    }

    fun previous() {
        if (playlist.isEmpty()) return
        currentIndex = if (shuffleEnabled) (playlist.indices - currentIndex).random() else (currentIndex - 1 + playlist.size) % playlist.size

        sharedPreferences.edit {
            putInt("lastPosition", 0) // Reset position to start from beginning
            putBoolean("isPlaying", true) // Ensure playback starts
            apply()
        }

        playCurrent()
        updateWidgetWithCurrentSong()
        updateNotification()
    }

    fun setShuffle(enabled: Boolean) {
        shuffleEnabled = enabled
    }

    fun isPlaying(): Boolean = mediaPlayer?.isPlaying == true

    fun getCurrentIndex(): Int = currentIndex

    private fun playCurrent() {
        isPrepared = false
        mediaPlayer?.reset()
        val uri = playlist.getOrNull(currentIndex)?.second
        if (uri == null) {
            android.util.Log.w("MediaService", "playCurrent: Invalid uri at index $currentIndex, playlist size: ${playlist.size}")
            return
        }
        // Save current song to preferences added by deepseek
        sharedPreferences.edit {
            putString("LastSongUri", uri.toString())
            apply()
        }
        //end deepseek

        val intent = Intent("com.example.hothot.ACTION_SONG_CHANGED")
        intent.putExtra("songUri", uri.toString())
        sendBroadcast(intent)

        try {
            mediaPlayer?.setDataSource(applicationContext, uri)
            mediaPlayer?.prepareAsync()
            updateNotification()
            android.util.Log.d("MediaService", "Preparing song: Uri=$uri")
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Error setting data source for uri: $uri", e)
            next()
        }
    }

    private fun updateWidgetWithCurrentSong() {
        val songPair = playlist.getOrNull(currentIndex)
        if (songPair != null) {
            val title = songPair.first
            val uri = songPair.second
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(applicationContext, uri)
                val art = retriever.embeddedPicture
                val bitmap = art?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST) ?: "Unknown Artist"
                retriever.release()
                AlbumArtWidget.updateWidgets(applicationContext, bitmap, title, artist)
                //update clock widget colors
                ClockColorWidget.updateWidgets(applicationContext, bitmap)

            } catch (_: Exception) {
                AlbumArtWidget.updateWidgets(applicationContext, null, title, "Unknown Artist")
            }
        }
    }

    override fun onPrepared(mp: MediaPlayer?) {
        android.util.Log.d("MediaService", "onPrepared called")
        isPrepared = true
        if (sharedPreferences.getBoolean("isPlaying", false)) {
            val lastPosition = sharedPreferences.getInt("lastPosition", 0)
            if (lastPosition > 0) {
                mp?.seekTo(lastPosition)
                android.util.Log.d("MediaService", "Seeked to lastPosition: $lastPosition")
            }
            mp?.start()
            startForegroundWithNotification()
            android.util.Log.d("MediaService", "Started playback")
        } else {
            android.util.Log.d("MediaService", "Prepared but not playing (isPlaying=false)")

            updateNotification() // Add this line
        }
    }

    override fun onCompletion(mp: MediaPlayer?) {
        next()
    }

    override fun onError(mp: MediaPlayer?, what: Int, extra: Int): Boolean {
        android.util.Log.e("MediaService", "MediaPlayer error: what=$what, extra=$extra")
        isPrepared = false
        mediaPlayer?.reset()
        next()
        return true
    }

    private fun startForegroundWithNotification() {
        val channelId = "media_playback_channel"
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(channelId, "Media Playback", NotificationManager.IMPORTANCE_LOW)
            nm.createNotificationChannel(channel)
        }

        val songPair = playlist.getOrNull(currentIndex)
        val title = songPair?.first ?: "Now Playing"
        val uri = songPair?.second

        val contentView = RemoteViews(packageName, R.layout.notification_big)
        contentView.setTextViewText(R.id.title, title)

        if (uri != null) {
            try {
                val retriever = android.media.MediaMetadataRetriever()
                retriever.setDataSource(this, uri)
                val art = retriever.embeddedPicture
                val bitmap = art?.let { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }
                val artist = retriever.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_ARTIST)
                    ?: "Unknown Artist"
                retriever.release()

                contentView.setTextViewText(R.id.text, artist)

                if (bitmap != null) {
                    val palette = Palette.from(bitmap)
                        .clearFilters()
                        .maximumColorCount(24) // More swatches for better results
                        .generate()

// Prefer vibrant > lightVibrant > muted > dominant
                    val bgSwatch = palette.dominantSwatch
                     //   ?: palette.lightVibrantSwatch
                      //  ?: palette.mutedSwatch
                       // ?: palette.dominantSwatch

                    val backgroundColor = bgSwatch?.rgb ?: Color.DKGRAY
                    val textSwatch = listOf(
                        palette.vibrantSwatch,
                        palette.lightVibrantSwatch,
                        palette.mutedSwatch,
                        palette.darkVibrantSwatch,
                        palette.lightMutedSwatch,
                        palette.darkMutedSwatch
                    ).firstOrNull { swatch ->
                        swatch != null && ColorUtils.calculateContrast(swatch.rgb, backgroundColor) >= 3.5 //higher makes better contrast
                    }

                    val foregroundColor = textSwatch?.rgb ?: getReadableTextColor(backgroundColor)

                    // val backgroundColor = palette.getDominantColor(Color.DKGRAY)
                    //val foregroundColor = if (isColorLight(backgroundColor)) Color.BLACK else Color.WHITE
                 //   Log.d("NotifyColors", "Palette: bg=#${Integer.toHexString(backgroundColor)}, fg=#${Integer.toHexString(foregroundColor)}")
                    Log.d("Notify", "ran start forgrounnd notification")
                  //  android.util.Log.e("MediaService", "Error preparing song for resume", e)
                    contentView.setInt(R.id.root, "setBackgroundColor", backgroundColor)
                    contentView.setTextColor(R.id.title, foregroundColor)
                    contentView.setTextColor(R.id.text, foregroundColor)

                    val gradient = addGradient(bitmap)
                    contentView.setImageViewBitmap(R.id.image, gradient)

                    contentView.setImageViewBitmap(R.id.action_prev, getTintedBitmap(R.drawable.ic_skip_previous_white_24dp, foregroundColor))
                    val playPauseRes = if (mediaPlayer?.isPlaying == true) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_arrow_white_24dp
                    contentView.setImageViewBitmap(R.id.action_play_pause, getTintedBitmap(playPauseRes, foregroundColor))
                    contentView.setImageViewBitmap(R.id.action_next, getTintedBitmap(R.drawable.ic_skip_next_white_24dp, foregroundColor))

                } else {
                    // Fallback: no album art available
                    val fallback = BitmapFactory.decodeResource(resources, R.drawable.blank_album_art)
                    val fallbackColor = Color.WHITE
                    val bgColor = Color.BLACK

                    contentView.setInt(R.id.root, "setBackgroundColor", bgColor)
                    contentView.setTextColor(R.id.title, fallbackColor)
                    contentView.setTextColor(R.id.text, fallbackColor)
                    contentView.setImageViewBitmap(R.id.image, fallback)

                    contentView.setImageViewBitmap(R.id.action_prev, getTintedBitmap(R.drawable.ic_skip_previous_white_24dp, fallbackColor))
                    val playPauseRes = if (mediaPlayer?.isPlaying == true) R.drawable.ic_pause_white_24dp else R.drawable.ic_play_arrow_white_24dp
                    contentView.setImageViewBitmap(R.id.action_play_pause, getTintedBitmap(playPauseRes, fallbackColor))
                    contentView.setImageViewBitmap(R.id.action_next, getTintedBitmap(R.drawable.ic_skip_next_white_24dp, fallbackColor))
                }





            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Set click actions
        contentView.setOnClickPendingIntent(R.id.action_prev, PendingIntent.getService(this, 0, Intent(this, MediaService::class.java).apply { action = "com.example.hothot.WIDGET_PREV" }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        contentView.setOnClickPendingIntent(R.id.action_play_pause, PendingIntent.getService(this, 1, Intent(this, MediaService::class.java).apply { action = "com.example.hothot.WIDGET_PLAY_PAUSE" }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        contentView.setOnClickPendingIntent(R.id.action_next, PendingIntent.getService(this, 2, Intent(this, MediaService::class.java).apply { action = "com.example.hothot.WIDGET_NEXT" }, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.ic_notification)
            .setCustomContentView(contentView)
            .setCustomBigContentView(contentView)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        startForeground(1, notification)
    }


    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            "com.example.hothot.WIDGET_PREV" -> {
                previous()
            }
            "com.example.hothot.WIDGET_PLAY_PAUSE" -> {
                playPause()
            }
            "com.example.hothot.WIDGET_NEXT" -> {
                next()
            }
            "STOP_SERVICE" -> {
                stopSelf()
                val mainIntent = Intent(Intent.ACTION_MAIN)
                mainIntent.addCategory(Intent.CATEGORY_HOME)
                mainIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                startActivity(mainIntent)
            }
            "PREPARE_SONG" -> {
                val songUri = intent.getStringExtra("songUri")
                val position = intent.getIntExtra("position", 0)
                if (songUri != null) {
                    try {
                        // Restore playlist from Room
                        val roomSongs = loadPlaylistFromRoom()
                        if (roomSongs.isNotEmpty()) {
                            setPlaylist(roomSongs, shuffleEnabled)
                        }
                        // Set currentIndex to the song being prepared
                        val index = playlist.indexOfFirst { it.second.toString() == songUri }
                        if (index >= 0) {
                            currentIndex = index
                        } else if (playlist.isNotEmpty()) {
                            currentIndex = 0
                        }
                        isPrepared = false
                        mediaPlayer?.reset()
                        mediaPlayer?.setDataSource(applicationContext, Uri.parse(songUri))
                        mediaPlayer?.prepare()
                        if (position > 0) {
                            mediaPlayer?.seekTo(position)
                        }
                        // Do not start playback here!
                        android.util.Log.d("MediaService", "Prepared song for resume: $songUri at $position with playlist from Room")
                    } catch (e: Exception) {
                        android.util.Log.e("MediaService", "Error preparing song for resume", e)
                    }
                }
            }
            else -> {
                val lastSongUri = sharedPreferences.getString("LastSongUri", null)
                if (lastSongUri != null && playlist.isEmpty()) {
                    val roomSongs = loadPlaylistFromRoom()
                    if (roomSongs.isNotEmpty()) {
                        setPlaylist(roomSongs, shuffleEnabled)
                        val index = roomSongs.indexOfFirst { it.second.toString() == lastSongUri }
                        if (index >= 0) {
                            currentIndex = index
                            prepareSongWithoutPlaying(Uri.parse(lastSongUri))
                        }
                    }
                }
            }
        }
        return START_STICKY
    }

    private fun prepareSongWithoutPlaying(uri: Uri) {
        isPrepared = false
        mediaPlayer?.reset()
        try {
            mediaPlayer?.setDataSource(applicationContext, uri)
            mediaPlayer?.prepareAsync()
            android.util.Log.d("MediaService", "Prepared song without playing: Uri=$uri")
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Error preparing song: $uri", e)
        }
    }

    //outdated
    private fun loadCachedSongs(): List<Pair<String, Uri>> {
        val cachedSongsJson = sharedPreferences.getString("cachedSongs", null) ?: return emptyList()
        return try {
            val jsonArray = org.json.JSONArray(cachedSongsJson)
            val songs = mutableListOf<Pair<String, Uri>>()
            for (i in 0 until jsonArray.length()) {
                val json = jsonArray.getJSONObject(i)
                songs.add(json.getString("title") to Uri.parse(json.getString("uri")))
            }
            songs
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Error loading cached songs", e)
            emptyList()
        }
    }

    private fun loadPlaylistFromRoom(): List<Pair<String, Uri>> {
        return try {
            val db = SongDatabase.getDatabase(applicationContext)
            val songDao = db.songDao()
            val sortAlpha = sharedPreferences.getString("isAlpha", "no") == "yesSong"
            val entities = runBlocking {
                if (sortAlpha) songDao.getAllAlphabetical() else songDao.getAll()
            }
            entities.map { it.title to Uri.parse(it.uri) }
        } catch (e: Exception) {
            android.util.Log.e("MediaService", "Error loading playlist from Room", e)
            emptyList()
        }
    }


    private fun updateNotification() {
        if (playlist.isNotEmpty()) {
            startForegroundWithNotification() // Rebuild the notification
        } else {
            stopForeground(true) // No songs to play, remove notification
        }
    }

    private fun getReadableTextColor(@ColorInt backgroundColor: Int): Int {
        val whiteContrast = ColorUtils.calculateContrast(Color.WHITE, backgroundColor)
        val blackContrast = ColorUtils.calculateContrast(Color.BLACK, backgroundColor)
        return if (whiteContrast >= blackContrast) Color.WHITE else Color.BLACK
    }


    private fun isColorLight(@ColorInt color: Int): Boolean {
        val darkness = 1 - (0.299 * Color.red(color) + 0.587 * Color.green(color) + 0.114 * Color.blue(color)) / 255
        return darkness < 0.5
    }

    private fun getTintedBitmap(@DrawableRes id: Int, @ColorInt color: Int): Bitmap {
        val drawable = ContextCompat.getDrawable(this, id)?.mutate() ?: return Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        drawable.setTint(color)
        val bitmap = Bitmap.createBitmap(drawable.intrinsicWidth, drawable.intrinsicHeight, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        drawable.setBounds(0, 0, canvas.width, canvas.height)
        drawable.draw(canvas)
        return bitmap
    }

    private fun addGradient(src: Bitmap): Bitmap {
        val w = src.width
        val h = src.height
        val overlay = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(overlay)
        canvas.drawBitmap(src, 0f, 0f, null)

        val paint = Paint().apply {
            shader = LinearGradient(0f, 0f, (w * 0.75f) + 47f, 0f, 0xFFFFFFFF.toInt(), 0x00FFFFFF, Shader.TileMode.CLAMP)
            xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN)
        }
        canvas.drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return overlay
    }


    override fun onDestroy() {
        mediaPlayer?.release()
        mediaPlayer = null
        super.onDestroy()
    }
}