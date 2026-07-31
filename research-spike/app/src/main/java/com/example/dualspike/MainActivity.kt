package com.example.dualspike

import android.Manifest
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.media.MediaPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.ListView
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import java.util.Locale

/**
 * Итоговая архитектура после серии экспериментов с audio focus:
 *
 * - Два WebView с YouTube одновременно не работают, а встроенный YouTube-канал
 *   в одном WebView упирался в ограничения embedding конкретных видео/плейлистов
 *   (ошибки 150/153) — не то, что можно починить на нашей стороне.
 * - Рабочее решение: пользователь запускает официальное приложение YouTube
 *   отдельно, а этот плеер играет локальный трек рядом — звук смешивается,
 *   потому что MediaPlayer здесь вообще никогда не обращается к AudioManager
 *   (audio focus в Android — кооперативный механизм, кто его не просит, того
 *   никто не глушит; подтверждено тестовым тоном поверх YouTube-видео).
 * - Трек можно скачать по ссылке YouTube через NewPipeExtractor (вне
 *   официального API и ToS YouTube, осознанно для личного использования),
 *   выбрать файл или указать папку — всё это ДОБАВЛЯЕТ записи в персистентную
 *   библиотеку треков, ничего не заменяя и не прерывая текущее воспроизведение.
 * - PlaybackForegroundService держит процесс живым, пока идёт воспроизведение
 *   ИЛИ скачивание — без этого и то, и другое останавливается почти сразу
 *   после блокировки экрана / ухода из приложения. Несколько параллельных
 *   скачиваний поддерживаются через счётчик "владельцев" фон-сервиса.
 */
class MainActivity : AppCompatActivity() {

    private val tag = "DualSpike"

    private var mediaPlayer: MediaPlayer? = null
    private var currentTrackUri: Uri? = null
    private var tracks: MutableList<Track> = mutableListOf()
    private var userIsSeeking = false

    private val activeForegroundOwners = mutableSetOf<String>()
    private val downloadStatusLines = linkedMapOf<String, String>()
    private var downloadCounter = 0

    private lateinit var prefs: SharedPreferences
    private lateinit var statusText: TextView
    private lateinit var bgUrlInput: EditText
    private lateinit var bgProgressBar: ProgressBar
    private lateinit var bgStatusText: TextView
    private lateinit var trackListView: ListView
    private lateinit var nowPlayingText: TextView
    private lateinit var positionText: TextView
    private lateinit var durationText: TextView
    private lateinit var seekBarPosition: SeekBar
    private lateinit var playButton: Button

    private val positionHandler = Handler(Looper.getMainLooper())
    private val positionUpdater = object : Runnable {
        override fun run() {
            val player = mediaPlayer
            if (player != null && player.isPlaying && !userIsSeeking) {
                val duration = player.duration
                if (duration > 0) {
                    seekBarPosition.progress = (player.currentPosition.toLong() * 1000 / duration).toInt()
                }
                positionText.text = formatTime(player.currentPosition)
            }
            positionHandler.postDelayed(this, 500)
        }
    }

    private val pickFileLauncher = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            addTracks(listOf(Track(displayName(uri), uri)))
        }
    }

    private val pickFolderLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
            )
            importTracksFromFolder(uri)
        }
    }

    private val notificationPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("dualspike_prefs", MODE_PRIVATE)

        statusText = findViewById(R.id.statusText)
        bgUrlInput = findViewById(R.id.bgUrlInput)
        bgProgressBar = findViewById(R.id.bgProgressBar)
        bgStatusText = findViewById(R.id.bgStatusText)
        trackListView = findViewById(R.id.trackListView)
        nowPlayingText = findViewById(R.id.nowPlayingText)
        positionText = findViewById(R.id.positionText)
        durationText = findViewById(R.id.durationText)
        seekBarPosition = findViewById(R.id.seekBarPosition)
        playButton = findViewById(R.id.playButton)

        statusText.text = "status: ready"

        findViewById<Button>(R.id.bgDownloadButton).setOnClickListener { onDownloadClicked() }
        findViewById<Button>(R.id.bgClearButton).setOnClickListener { bgUrlInput.text.clear() }
        findViewById<Button>(R.id.bgPickFileButton).setOnClickListener {
            pickFileLauncher.launch(arrayOf("audio/*", "video/*"))
        }
        findViewById<Button>(R.id.pickFolderButton).setOnClickListener {
            pickFolderLauncher.launch(null)
        }
        playButton.setOnClickListener { onPlayPauseClicked() }

        trackListView.setOnItemClickListener { _, _, position, _ ->
            playTrack(tracks[position])
        }

        findViewById<SeekBar>(R.id.bgVolumeSeekBar).setOnSeekBarChangeListener(
            object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                    val volume = progress / 100f
                    mediaPlayer?.setVolume(volume, volume)
                }
                override fun onStartTrackingTouch(seekBar: SeekBar) {}
                override fun onStopTrackingTouch(seekBar: SeekBar) {}
            }
        )

        seekBarPosition.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val duration = mediaPlayer?.duration ?: 0
                    positionText.text = formatTime((progress.toLong() * duration / 1000).toInt())
                }
            }
            override fun onStartTrackingTouch(seekBar: SeekBar) {
                userIsSeeking = true
            }
            override fun onStopTrackingTouch(seekBar: SeekBar) {
                val duration = mediaPlayer?.duration ?: 0
                mediaPlayer?.seekTo((seekBar.progress.toLong() * duration / 1000).toInt())
                userIsSeeking = false
            }
        })

        positionHandler.post(positionUpdater)

        loadLibrary()
    }

    private fun onDownloadClicked() {
        val url = bgUrlInput.text.toString().trim()
        if (url.isEmpty()) {
            bgStatusText.text = "трек: вставь ссылку на видео"
            return
        }
        bgUrlInput.text.clear()

        downloadCounter++
        val downloadKey = "dl$downloadCounter"
        updateDownloadStatus(downloadKey, "скачивание начато...")
        acquireForeground(downloadKey)

        YoutubeAudioDownloader(this).download(
            youtubeUrl = url,
            onProgress = { downloadedBytes, totalBytes ->
                val downloadedMb = downloadedBytes / 1024 / 1024
                if (totalBytes > 0) {
                    val percent = (downloadedBytes * 100 / totalBytes).toInt()
                    val totalMb = totalBytes / 1024 / 1024
                    bgProgressBar.progress = percent
                    updateDownloadStatus(downloadKey, "$percent% ($downloadedMb / $totalMb МБ)")
                } else {
                    updateDownloadStatus(downloadKey, "скачивается ($downloadedMb МБ)")
                }
            },
            onError = { error ->
                Log.e(tag, "download failed", error)
                updateDownloadStatus(downloadKey, "ошибка — ${error.message}")
                releaseForeground(downloadKey)
            },
            onComplete = { uri, name ->
                updateDownloadStatus(downloadKey, "готово: $name")
                addTracks(listOf(Track(name, uri)))
                releaseForeground(downloadKey)
            }
        )
    }

    private fun updateDownloadStatus(key: String, line: String) {
        downloadStatusLines[key] = line
        bgStatusText.text = downloadStatusLines.entries.joinToString("\n") { (k, v) -> "$k: $v" }
    }

    private fun acquireForeground(owner: String) {
        activeForegroundOwners.add(owner)
        ensureNotificationPermission()
        PlaybackForegroundService.start(this)
    }

    private fun releaseForeground(owner: String) {
        activeForegroundOwners.remove(owner)
        if (activeForegroundOwners.isEmpty()) {
            PlaybackForegroundService.stop(this)
        }
    }

    private fun importTracksFromFolder(treeUri: Uri) {
        val folder = DocumentFile.fromTreeUri(this, treeUri)
        if (folder == null || !folder.isDirectory) {
            statusText.text = "status: не удалось открыть папку"
            return
        }
        val found = folder.listFiles()
            .filter { doc ->
                doc.isFile && (doc.type?.startsWith("audio/") == true || doc.type?.startsWith("video/") == true)
            }
            .mapNotNull { doc -> doc.name?.let { Track(it, doc.uri) } }

        addTracks(found)
        statusText.text = "status: импортировано из папки: ${found.size}"
    }

    /** Добавляет треки в библиотеку без дублей и без замены текущего плейбека. */
    private fun addTracks(newTracks: List<Track>) {
        val existingUris = tracks.mapTo(mutableSetOf()) { it.uri.toString() }
        var added = false
        for (track in newTracks) {
            if (existingUris.add(track.uri.toString())) {
                tracks.add(track)
                added = true
            }
        }
        if (added) {
            tracks.sortBy { it.name.lowercase(Locale.getDefault()) }
            saveLibrary()
            refreshTrackListAdapter()
        }
    }

    private fun refreshTrackListAdapter() {
        trackListView.adapter = ArrayAdapter(
            this,
            R.layout.list_item_track,
            tracks.map { it.name }
        )
    }

    private fun saveLibrary() {
        val raw = tracks.joinToString("\n") { "${it.name}\t${it.uri}" }
        prefs.edit().putString(PREF_LIBRARY, raw).apply()
    }

    private fun loadLibrary() {
        val raw = prefs.getString(PREF_LIBRARY, null) ?: return
        tracks = raw.split("\n").filter { it.isNotBlank() }.mapNotNull { line ->
            val tabIndex = line.indexOf('\t')
            if (tabIndex == -1) null else Track(line.substring(0, tabIndex), Uri.parse(line.substring(tabIndex + 1)))
        }.toMutableList()
        refreshTrackListAdapter()
    }

    private fun playTrack(track: Track) {
        currentTrackUri = track.uri
        nowPlayingText.text = "играет: ${track.name}"
        startPlayback(track.uri)
    }

    private fun onPlayPauseClicked() {
        val player = mediaPlayer
        if (player == null) {
            val uri = currentTrackUri
            if (uri != null) {
                startPlayback(uri)
            } else {
                bgStatusText.text = "трек: сначала скачай, выбери файл или папку"
            }
            return
        }
        if (player.isPlaying) {
            player.pause()
            playButton.text = "Play"
            releaseForeground(OWNER_PLAYBACK)
        } else {
            acquireForeground(OWNER_PLAYBACK)
            player.start()
            playButton.text = "Пауза"
        }
    }

    private fun ensureNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
            if (!granted) {
                notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    private fun startPlayback(uri: Uri) {
        acquireForeground(OWNER_PLAYBACK)

        mediaPlayer?.release()
        mediaPlayer = MediaPlayer().apply {
            setDataSource(this@MainActivity, uri)
            setOnPreparedListener {
                Log.d(tag, "track prepared, starting (no audio focus requested)")
                durationText.text = formatTime(it.duration)
                val volume = findViewById<SeekBar>(R.id.bgVolumeSeekBar).progress / 100f
                it.setVolume(volume, volume)
                it.start()
                playButton.text = "Пауза"
            }
            setOnErrorListener { _, what, extra ->
                Log.e(tag, "MediaPlayer error what=$what extra=$extra")
                true
            }
            prepareAsync()
        }
    }

    private fun displayName(uri: Uri): String {
        return DocumentFile.fromSingleUri(this, uri)?.name ?: uri.lastPathSegment ?: "трек"
    }

    private fun formatTime(ms: Int): String {
        val totalSeconds = ms / 1000
        val minutes = totalSeconds / 60
        val seconds = totalSeconds % 60
        return String.format(Locale.getDefault(), "%d:%02d", minutes, seconds)
    }

    override fun onDestroy() {
        super.onDestroy()
        positionHandler.removeCallbacks(positionUpdater)
        mediaPlayer?.release()
        mediaPlayer = null
        // Не глушим сервис безусловно: если ещё идёт скачивание (owners не пуст без
        // playback), процесс должен пережить закрытие Activity — иначе скачивание,
        // прерванное свайпом приложения из недавних, обрывается на полпути.
        releaseForeground(OWNER_PLAYBACK)
    }

    companion object {
        private const val PREF_LIBRARY = "library"
        private const val OWNER_PLAYBACK = "playback"
    }
}

data class Track(val name: String, val uri: Uri)
