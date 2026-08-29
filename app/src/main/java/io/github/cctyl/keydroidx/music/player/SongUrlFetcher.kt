package io.github.cctyl.keydroidx.music.player

import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class SongUrlResult(
    val url: String,
    val actualLevel: String,
    val isTrial: Boolean = false,
    val trialStart: Int = 0,
    val trialEnd: Int = 0
)

object SongUrlFetcher {
    private const val TAG = "SongUrlFetcher"
    private const val SONG_URL_PATH = "/eapi/song/enhance/player/url/v1"

    suspend fun fetch(songId: Long, level: String = "standard"): SongUrlResult = withContext(Dispatchers.IO) {
        val fallbackLevels = when (level) {
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else       -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }

        for (tryLevel in fallbackLevels) {
            // 双主机回退：interface3 与 music.163 都试一遍
            for (useIf in listOf(true, false)) {
                try {
                    val payload = buildPayload(songId, tryLevel)
                    val response = RetrofitClient.eapiPost(SONG_URL_PATH, payload, useInterface = useIf)
                    val body = response.body()?.string() ?: continue
                    Log.d(TAG, "eapi response (if=$useIf, $tryLevel): $body")
                    val json = JSONObject(body)
                    val data = json.optJSONArray("data") ?: continue
                    if (data.length() > 0) {
                        val obj = data.getJSONObject(0)
                        val url = obj.optString("url")
                        val actualLevel = obj.optString("level", tryLevel)
                        val freeTrialInfo = obj.optJSONObject("freeTrialInfo")
                        val isTrial = freeTrialInfo != null
                        val trialStart = freeTrialInfo?.optInt("start", 0) ?: 0
                        val trialEnd = freeTrialInfo?.optInt("end", 0) ?: 0

                        if (!url.isNullOrEmpty() && url != "null") {
                            Log.d(TAG, "got url: $url  actualLevel: $actualLevel  isTrial: $isTrial (${trialStart}s-${trialEnd}s)  requested: $level")
                            return@withContext SongUrlResult(url, actualLevel, isTrial, trialStart, trialEnd)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "fetch failed for level=$tryLevel if=$useIf", e)
                }
            }
        }

        Log.e(TAG, "all levels exhausted, falling back to outer url")
        SongUrlResult("https://music.163.com/song/media/outer/url?id=$songId.mp3", "standard")
    }


    private fun buildPayload(songId: Long, level: String): Map<String, String> {
        val encodeType = if (level == "lossless" || level == "hires") "flac" else "mp3"
        return mapOf(
            "ids" to JSONArray().put(songId).toString(),
            "level" to level,
            "encodeType" to encodeType
        )
    }

}
