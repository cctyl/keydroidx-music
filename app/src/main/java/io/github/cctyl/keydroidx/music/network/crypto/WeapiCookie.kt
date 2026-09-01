package io.github.cctyl.keydroidx.music.network.crypto

import android.content.Context
import java.security.SecureRandom

/**
 * weapi 请求用的 Cookie 构造，逐行还原参考实现 `util/request.js` 中
 * **加密分支之前**那段公共 cookie 处理（它对 weapi / eapi / api 一视同仁）。
 *
 * 这段逻辑容易被忽略，但少注入任何一项都会让请求看起来「不像真实客户端」：
 *
 * 1. `cookieToJson` —— 按 `;` 切分后，只有按 `=` 拆分**恰好得到 2 段**的项才保留，
 *    含多个 `=` 的畸形项被丢弃；
 * 2. 注入设备档位默认值（`os` / `appver` / `osver` / `channel` / `deviceId`），
 *    cookie 里没有 `os` 时默认套用 iPhone 档；
 * 3. 注入 `ntes_kaola_ad`、`_ntes_nuid`、`_ntes_nnid`、`WNMCID`、`WEVNSM`，
 *    并强制把 `__remember_me` 置为 `true`；
 * 4. 非登录接口每次刷新 `NMTID`；
 * 5. `cookieObjToString` —— 键与值**都要做 `encodeURIComponent`**，用 `"; "` 连接。
 *
 * 第 5 点尤其关键：Java 的 `URLEncoder` 语义不同（空格编码成 `+`、会转义 `*` 等），
 * 直接拿来用会导致 `os=iPhone+OS` 而非 `iPhone%20OS`，故这里自己实现。
 *
 * ⚠️ 目前**只用于 weapi**：本项目 eapi 通道（拉评论、红心、取链等）已在真机上长期
 * 验证可用，改动它的 cookie 有回归风险，因此保持原样不动。
 */
object WeapiCookie {
    /** 参考实现 `osMap['iphone']`，即 cookie 未指定 `os` 时的默认档位 */
    private const val OS = "iPhone OS"
    private const val APPVER = "9.0.90"
    private const val OSVER = "16.2"
    private const val CHANNEL = "distribution"

    private const val PREFS = "keydroidx_music_weapi"
    private const val KEY_DEVICE_ID = "device_id"
    private const val KEY_WNMCID = "wnmcid"

    private const val HEX_LOWER = "0123456789abcdef"

    private val RANDOM = SecureRandom()
    private var prefs: android.content.SharedPreferences? = null

    fun init(context: Context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    /**
     * @param rawCookie 本地保存的原始 cookie 串
     * @param uri 网易云接口路径，如 `/api/resource/comments/add`
     */
    fun build(rawCookie: String?, uri: String): String {
        val cookie = LinkedHashMap<String, String>()

        // ① cookieToJson：只保留严格 key=value 的项
        rawCookie.orEmpty().split(";").forEach { part ->
            val arr = part.split("=")
            if (arr.size == 2) cookie[arr[0].trim()] = arr[1].trim()
        }

        val nuid = randomHex(32)

        // ② 与参考实现对象字面量顺序一致；读取的都是尚未被覆盖的键，故顺序等价
        cookie["__remember_me"] = "true"
        cookie["ntes_kaola_ad"] = "1"
        cookie["_ntes_nuid"] = cookie["_ntes_nuid"] ?: nuid
        cookie["_ntes_nnid"] = cookie["_ntes_nnid"] ?: "$nuid,${System.currentTimeMillis()}"
        cookie["WNMCID"] = cookie["WNMCID"] ?: wnmcid()
        cookie["WEVNSM"] = cookie["WEVNSM"] ?: "1.0.0"
        cookie["osver"] = cookie["osver"] ?: OSVER
        cookie["deviceId"] = cookie["deviceId"] ?: deviceId()
        cookie["os"] = cookie["os"] ?: OS
        cookie["channel"] = cookie["channel"] ?: CHANNEL
        cookie["appver"] = cookie["appver"] ?: APPVER

        // ③ 登录接口不刷新 NMTID
        if (!uri.contains("login")) {
            cookie["NMTID"] = randomHex(16)
        }

        // ④ 参考实现在无 MUSIC_U 时会补游客 MUSIC_A。本项目 weapi 只用于已登录的
        //    写操作，没有游客 token 可用，因此仅在 cookie 里本来就有时保留。
        if (!cookie.containsKey("MUSIC_U") && cookie.containsKey("MUSIC_A")) {
            cookie["MUSIC_A"] = cookie["MUSIC_A"].orEmpty()
        }

        // ⑤ cookieObjToString
        return cookie.entries.joinToString("; ") { (k, v) ->
            "${encodeURIComponent(k)}=${encodeURIComponent(v)}"
        }
    }

    /** 模拟参考实现的 module 级 WNMCID：6 位随机小写字母 + 时间戳 + 固定后缀 */
    private fun wnmcid(): String {
        prefs?.getString(KEY_WNMCID, null)?.let { return it }
        val letters = "abcdefghijklmnopqrstuvwxyz"
        val prefix = buildString {
            repeat(6) { append(letters[RANDOM.nextInt(letters.length)]) }
        }
        val v = "$prefix.${System.currentTimeMillis()}.01.0"
        prefs?.edit()?.putString(KEY_WNMCID, v)?.apply()
        return v
    }

    /** 设备 ID：与参考实现 `data/deviceid.txt` 同形，50 位大写 hex，持久化保持稳定 */
    private fun deviceId(): String {
        prefs?.getString(KEY_DEVICE_ID, null)?.let { return it }
        val v = randomHex(25).uppercase()
        prefs?.edit()?.putString(KEY_DEVICE_ID, v)?.apply()
        return v
    }

    /** 对应 `CryptoJS.lib.WordArray.random(n).toString()`，输出 2n 位小写 hex */
    private fun randomHex(bytes: Int): String {
        val buf = ByteArray(bytes)
        RANDOM.nextBytes(buf)
        return buildString(bytes * 2) {
            for (b in buf) {
                append(HEX_LOWER[(b.toInt() shr 4) and 0xF])
                append(HEX_LOWER[b.toInt() and 0xF])
            }
        }
    }

    /**
     * JS `encodeURIComponent` 的等价实现。
     *
     * 不转义：`A-Z a-z 0-9 - _ . ! ~ * ' ( )`，其余按 UTF-8 逐字节转成 `%XX`（大写）。
     * 与 `java.net.URLEncoder` 的差异正是这里不能直接用它的原因。
     */
    private fun encodeURIComponent(s: String): String {
        val sb = StringBuilder(s.length)
        for (b in s.toByteArray(Charsets.UTF_8)) {
            val c = b.toInt() and 0xFF
            val ch = c.toChar()
            val keep = when (ch) {
                in 'A'..'Z', in 'a'..'z', in '0'..'9' -> true
                '-', '_', '.', '!', '~', '*', '\'', '(', ')' -> true
                else -> false
            }
            if (keep) {
                sb.append(ch)
            } else {
                sb.append('%')
                    .append(HEX_LOWER[(c shr 4) and 0xF].uppercaseChar())
                    .append(HEX_LOWER[c and 0xF].uppercaseChar())
            }
        }
        return sb.toString()
    }
}
