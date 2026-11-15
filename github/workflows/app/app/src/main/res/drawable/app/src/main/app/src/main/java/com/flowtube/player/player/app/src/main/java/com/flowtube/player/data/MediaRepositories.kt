package com.flowtube.player.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import java.net.URLEncoder

data class MediaItemModel(
    val id: String,
    val title: String,
    val subtitle: String,
    val streamUrl: String,
    val thumbnailUrl: String = "",
    val isVideo: Boolean = true,
    val isOnline: Boolean = false,
    val peerTubeUuid: String = "",
    val peerTubeHost: String = ""
)

class LocalMediaRepository(private val context: Context) {

    suspend fun loadLocalMedia(): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val list = mutableListOf<MediaItemModel>()
        list.addAll(queryVideos())
        list.addAll(queryAudio())
        list
    }

    private fun queryVideos(): List<MediaItemModel> {
        val items = mutableListOf<MediaItemModel>()
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.BUCKET_DISPLAY_NAME
        )
        context.contentResolver.query(
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
            projection, null, null,
            "${MediaStore.Video.Media.DATE_ADDED} DESC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val folderCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.BUCKET_DISPLAY_NAME)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id)
                items.add(
                    MediaItemModel(
                        id = "vid_$id",
                        title = cursor.getString(titleCol) ?: "Vídeo",
                        subtitle = cursor.getString(folderCol) ?: "Local",
                        streamUrl = uri.toString(),
                        isVideo = true
                    )
                )
            }
        }
        return items
    }

    private fun queryAudio(): List<MediaItemModel> {
        val items = mutableListOf<MediaItemModel>()
        val projection = arrayOf(
            MediaStore.Audio.Media._ID,
            MediaStore.Audio.Media.TITLE,
            MediaStore.Audio.Media.ARTIST
        )
        context.contentResolver.query(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Audio.Media.IS_MUSIC} != 0",
            null,
            "${MediaStore.Audio.Media.TITLE} ASC"
        )?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = cursor.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id)
                items.add(
                    MediaItemModel(
                        id = "aud_$id",
                        title = cursor.getString(titleCol) ?: "Áudio",
                        subtitle = cursor.getString(artistCol) ?: "Artista",
                        streamUrl = uri.toString(),
                        isVideo = false
                    )
                )
            }
        }
        return items
    }
}

class OnlineVideoRepository {

    suspend fun searchVideos(query: String): List<MediaItemModel> = withContext(Dispatchers.IO) {
        val encoded = URLEncoder.encode(query.trim(), "UTF-8")
        val endpoint = "https://sepiasearch.org/api/v1/search/videos?search=$encoded&count=25&nsfw=false"
        val jsonText = httpGet(endpoint) ?: return@withContext emptyList()
        val data = JSONObject(jsonText).optJSONArray("data") ?: return@withContext emptyList()

        val results = mutableListOf<MediaItemModel>()
        for (i in 0 until data.length()) {
            val obj = data.getJSONObject(i)
            val uuid = obj.optString("uuid", "")
            val title = obj.optString("name", "Vídeo")
            val pageUrl = obj.optString("url", "")
            val thumb = obj.optString("thumbnailUrl", "")
            val channel = obj.optJSONObject("channel")?.optString("displayName") ?: "PeerTube"
            val host = try { URI(pageUrl).host ?: "" } catch (_: Exception) { "" }

            if (uuid.isNotEmpty() && host.isNotEmpty()) {
                results.add(
                    MediaItemModel(
                        id = "online_$uuid",
                        title = title,
                        subtitle = "$channel • $host",
                        streamUrl = pageUrl,
                        thumbnailUrl = thumb,
                        isVideo = true,
                        isOnline = true,
                        peerTubeUuid = uuid,
                        peerTubeHost = host
                    )
                )
            }
        }
        results
    }

    suspend fun resolveStreamUrl(item: MediaItemModel): String? = withContext(Dispatchers.IO) {
        if (!item.isOnline || item.peerTubeHost.isEmpty()) return@withContext item.streamUrl
        val detailUrl = "https://${item.peerTubeHost}/api/v1/videos/${item.peerTubeUuid}"
        val jsonText = httpGet(detailUrl) ?: return@withContext null
        val obj = JSONObject(jsonText)

        // Prioriza HLS (.m3u8) para qualidade adaptativa sem travar
        val playlists = obj.optJSONArray("streamingPlaylists")
        if (playlists != null && playlists.length() > 0) {
            val hls = playlists.getJSONObject(0).optString("playlistUrl", "")
            if (hls.isNotEmpty()) return@withContext hls
        }

        // Alternativa: ficheiro MP4 direto
        val files = obj.optJSONArray("files")
        if (files != null && files.length() > 0) {
            val mp4 = files.getJSONObject(0).optString("fileUrl", "")
            if (mp4.isNotEmpty()) return@withContext mp4
        }
        null
    }

    private fun httpGet(urlString: String): String? {
        return try {
            val conn = (URL(urlString).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10000
                readTimeout = 10000
                setRequestProperty("User-Agent", "FlowTubePlayer/1.0")
            }
            if (conn.responseCode in 200..299) {
                conn.inputStream.bufferedReader().use { it.readText() }
            } else null
        } catch (_: Exception) {
            null
        }
    }
}

class FavoritesStorage(context: Context) {
    private val prefs = context.getSharedPreferences("flowtube_favs", Context.MODE_PRIVATE)

    fun loadFavorites(): List<MediaItemModel> {
        val raw = prefs.getString("items", "[]") ?: "[]"
        val arr = JSONArray(raw)
        val list = mutableListOf<MediaItemModel>()
        for (i in 0 until arr.length()) {
            val o = arr.getJSONObject(i)
            list.add(
                MediaItemModel(
                    id = o.getString("id"),
                    title = o.getString("title"),
                    subtitle = o.getString("subtitle"),
                    streamUrl = o.getString("streamUrl"),
                    thumbnailUrl = o.optString("thumbnailUrl", ""),
                    isVideo = o.optBoolean("isVideo", true),
                    isOnline = o.optBoolean("isOnline", false),
                    peerTubeUuid = o.optString("peerTubeUuid", ""),
                    peerTubeHost = o.optString("peerTubeHost", "")
                )
            )
        }
        return list
    }

    fun toggleFavorite(item: MediaItemModel): List<MediaItemModel> {
        val current = loadFavorites().toMutableList()
        val exists = current.any { it.id == item.id }
        if (exists) {
            current.removeAll { it.id == item.id }
        } else {
            current.add(0, item)
        }
        val arr = JSONArray()
        current.forEach {
            arr.put(JSONObject().apply {
                put("id", it.id)
                put("title", it.title)
                put("subtitle", it.subtitle)
                put("streamUrl", it.streamUrl)
                put("thumbnailUrl", it.thumbnailUrl)
                put("isVideo", it.isVideo)
                put("isOnline", it.isOnline)
                put("peerTubeUuid", it.peerTubeUuid)
                put("peerTubeHost", it.peerTubeHost)
            })
        }
        prefs.edit().putString("items", arr.toString()).apply()
        return current
    }
}
