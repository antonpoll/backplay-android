package com.example.dualspike

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import okhttp3.Request as OkRequest
import org.schabi.newpipe.extractor.downloader.Request as ExtractorRequest
import org.schabi.newpipe.extractor.downloader.Response as ExtractorResponse

/**
 * NewPipeExtractor нужен HTTP-клиент от хост-приложения — это стандартный
 * мост между Downloader (абстрактный класс NewPipeExtractor) и OkHttp.
 */
private class OkHttpNewPipeDownloader(private val client: OkHttpClient) : Downloader() {

    @Throws(IOException::class, ReCaptchaException::class)
    override fun execute(request: ExtractorRequest): ExtractorResponse {
        val dataToSend = request.dataToSend()
        val requestBody = dataToSend?.toRequestBody()

        val builder = OkRequest.Builder()
            .method(request.httpMethod(), requestBody)
            .url(request.url())
            .addHeader(
                "User-Agent",
                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
            )

        for ((name, values) in request.headers()) {
            if (values.size > 1) {
                builder.removeHeader(name)
                for (value in values) builder.addHeader(name, value)
            } else if (values.size == 1) {
                builder.header(name, values[0])
            }
        }

        client.newCall(builder.build()).execute().use { response ->
            if (response.code == 429) {
                throw ReCaptchaException("reCaptcha Challenge requested", request.url())
            }
            val body = response.body?.string()
            val latestUrl = response.request.url.toString()
            return ExtractorResponse(
                response.code, response.message, response.headers.toMultimap(), body, latestUrl
            )
        }
    }
}

object NewPipeSetup {
    private var initialized = false
    val httpClient: OkHttpClient by lazy { OkHttpClient() }

    @Synchronized
    fun ensureInit() {
        if (initialized) return
        NewPipe.init(OkHttpNewPipeDownloader(httpClient))
        initialized = true
    }
}

class YoutubeAudioDownloader(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())

    fun download(
        youtubeUrl: String,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
        onError: (Throwable) -> Unit,
        onComplete: (uri: Uri, displayName: String) -> Unit
    ) {
        Thread {
            try {
                NewPipeSetup.ensureInit()
                val info = StreamInfo.getInfo(ServiceList.YouTube, youtubeUrl)
                // Всегда берём минимальный доступный битрейт среди ВСЕХ форматов
                // (не только m4a) — трек предназначен только для фона, качество
                // некритично, а маленький файл важнее.
                val audioStream = info.audioStreams
                    .filter { it.isUrl }
                    .minByOrNull { stream ->
                        val bitrate = stream.averageBitrate
                        if (bitrate <= 0) Int.MAX_VALUE else bitrate
                    }
                    ?: throw IOException("No downloadable audio stream found for this video")

                val extension = audioStream.format?.suffix?.takeIf { it.isNotBlank() } ?: "m4a"
                val fileName = sanitizeFileName(info.name) + "." + extension

                val outUri = createDownloadUri(fileName)
                val url = audioStream.content

                var downloaded = 0L
                var total = -1L

                context.contentResolver.openOutputStream(outUri)?.use { output ->
                    // Скачиваем чанками с Range-заголовком и новым соединением на каждый —
                    // непрерывное скачивание всего файла одним запросом упирается в
                    // троттлинг googlevideo.com CDN для нестандартных клиентов (~10-30 КБ/с),
                    // периодическое переподключение обходит это, как делают yt-dlp/NewPipe.
                    while (total < 0 || downloaded < total) {
                        val rangeEnd = downloaded + CHUNK_SIZE - 1
                        val request = OkRequest.Builder()
                            .url(url)
                            .header("Range", "bytes=$downloaded-$rangeEnd")
                            .header(
                                "User-Agent",
                                "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36"
                            )
                            .build()

                        NewPipeSetup.httpClient.newCall(request).execute().use { response ->
                            if (!response.isSuccessful) {
                                throw IOException("Download failed: HTTP ${response.code}")
                            }
                            if (total < 0) {
                                total = parseTotalFromContentRange(response.header("Content-Range"))
                                    ?: response.body?.contentLength()?.takeIf { it > 0 } ?: -1
                            }
                            val bytes = response.body?.bytes() ?: ByteArray(0)
                            output.write(bytes)
                            downloaded += bytes.size

                            if (bytes.isEmpty() && total < 0) {
                                total = downloaded
                            }

                            mainHandler.post { onProgress(downloaded, total) }
                        }
                    }
                } ?: throw IOException("Could not open output stream for $outUri")

                mainHandler.post { onComplete(outUri, fileName) }
            } catch (t: Throwable) {
                mainHandler.post { onError(t) }
            }
        }.start()
    }

    /**
     * Файлы кладём в публичную папку Download/DualSpike, чтобы они были видны
     * в обычном файловом менеджере и их можно было выбрать через "Выбрать папку".
     */
    private fun createDownloadUri(fileName: String): Uri {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, fileName)
                put(MediaStore.Downloads.RELATIVE_PATH, "Download/DualSpike")
            }
            return context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IOException("Could not create MediaStore entry")
        }
        @Suppress("DEPRECATION")
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DualSpike")
        if (!dir.exists()) dir.mkdirs()
        return Uri.fromFile(File(dir, fileName))
    }

    private fun sanitizeFileName(name: String): String {
        return name.replace(Regex("[/\\\\:*?\"<>|]"), "_").trim().take(120)
    }

    private fun parseTotalFromContentRange(header: String?): Long? {
        if (header == null) return null
        val slashIndex = header.indexOf('/')
        if (slashIndex == -1) return null
        return header.substring(slashIndex + 1).toLongOrNull()
    }

    companion object {
        private const val CHUNK_SIZE = 10L * 1024 * 1024
    }
}
