package io.github.cctyl.keydroidx.music.player

import io.github.cctyl.keydroidx.music.util.NLog as Log
import io.github.cctyl.keydroidx.music.network.RetrofitClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

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
    private const val HOST_COUNT = 2

    /**
     * 取歌曲播放链接（5 级音质 × interface3 / music.163 双主机回退）。
     *
     * 返回 `null` 表示**确实没取到链接**（网络不可达 / 无版权 / 需 VIP 且无试听）。
     *
     * 历史坑：旧实现在全部档位与主机都失败后，返回一个伪造的
     * `song/media/outer/url?id=xxx.mp3` 兜底地址。这让调用方**无法区分**
     * 「取链失败」与「取链成功」：ExoPlayer 拿着这个必然 404 的地址去播放，
     * 最终错误被 onPlayerError 误归因为「无网络」。**此处不再伪造 URL**，
     * 让失败可被上层正确归因。
     */
    suspend fun fetch(songId: Long, level: String = "standard"): SongUrlResult? = withContext(Dispatchers.IO) {
        val fallbackLevels = when (level) {
            "hires"    -> listOf("hires", "lossless", "exhigh", "higher", "standard")
            "lossless" -> listOf("lossless", "exhigh", "higher", "standard")
            "exhigh"   -> listOf("exhigh", "higher", "standard")
            "higher"   -> listOf("higher", "standard")
            "standard" -> listOf("standard")
            else       -> listOf(level, "lossless", "exhigh", "higher", "standard")
        }

        for (tryLevel in fallbackLevels) {
            // 本档位下遇到网络层异常的宿主数量。双主机都不可达说明是链路问题，
            // 换音质档位毫无意义，直接跳出，避免离线时把 5×2 次请求的
            // DNS/连接超时挨个等一遍（离线场景下这一步能把取链失败耗时压到 1 个超时）。
            var unreachableHosts = 0

            for (useIf in listOf(true, false)) {
                try {
                    val payload = buildPayload(songId, tryLevel)
                    val response = RetrofitClient.eapiPost(SONG_URL_PATH, payload, useInterface = useIf)
                    val body = response.body()?.string()
                    if (body.isNullOrEmpty()) {
                        Log.w(TAG, "empty body (if=$useIf, $tryLevel), http=${response.code()}")
                        continue
                    }
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
                } catch (e: IOException) {
                    // DNS 解析失败 / 连接超时 / 连接被拒 等网络层异常。
                    // 与该档位是否有版权无关，只说明这个宿主不可达。
                    unreachableHosts++
                    Log.w(TAG, "network failure (if=$useIf, level=$tryLevel): ${e.message}")
                } catch (e: Exception) {
                    Log.w(TAG, "fetch failed for level=$tryLevel if=$useIf", e)
                }
            }

            if (unreachableHosts >= HOST_COUNT) {
                Log.w(TAG, "all hosts unreachable at level=$tryLevel, abort remaining levels")
                break
            }
        }

        Log.e(TAG, "no playable url for songId=$songId after all levels/hosts")
        null
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
