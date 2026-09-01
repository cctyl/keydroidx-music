package io.github.cctyl.keydroidx.music.network.crypto

import android.util.Base64
import java.security.KeyFactory
import java.security.spec.X509EncodedKeySpec
import java.util.Random
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * 网易云 **weapi（网页端）** 加密。
 *
 * 实现依据：`NeteaseCloudMusicApiBackup/util/crypto.js` 的 `weapi()`，
 * 配合 `util/request.js` 中的 weapi 分支。流程为
 * 「随机密钥 + AES-CBC 双重加密 + RSA 无填充加密」：
 *
 * 1. 随机 16 位 base62 字符串作为 `secretKey`；
 * 2. 明文 JSON 用固定密钥 `0CoJUm6Qyw8W8jud` / IV `0102030405060708` 做 AES-CBC，
 *    输出 **Base64 文本**；
 * 3. 上一步的 Base64 **文本本身**（不是解码后的字节）再用 `secretKey` 做一次
 *    AES-CBC，输出即 `params`；
 * 4. `secretKey` **反转**后用 RSA 公钥做**无填充**加密，输出 128 字节小写 hex 即 `encSecKey`。
 *
 * 与 [EapiCrypto] 是两套完全独立的协议：eapi 走 `interface*.music.163.com/eapi/…`，
 * weapi 走 `music.163.com/weapi/…`。本项目只读接口用 eapi，
 * **写接口（发评论等）必须走 weapi** —— 参考实现里 `module/comment.js`
 * 就固定传了 `createOption(query, 'weapi')`。
 */
object WeapiCrypto {
    private const val PRESET_KEY = "0CoJUm6Qyw8W8jud"
    private const val IV = "0102030405060708"
    private const val BASE62 = "abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789"

    /**
     * 网易云 RSA 公钥（1024 位，模长 128 字节），硬编码于参考实现 `util/crypto.js`。
     *
     * ⚠️ 共 216 个 base64 字符，**任何一位抄错都会导致 `Error parsing public key`**
     * （首次接入时曾漏抄第 68 位的 `X`，请求因此完全发不出去）。
     * 如需修改，请用脚本从 `util/crypto.js` 提取后重新生成，切勿手工改动。
     */
    private const val PUBLIC_KEY_B64 =
        "MIGfMA0GCSqGSIb3DQEBAQUAA4GNADCBiQKBgQDgtQn2JZ34ZC28NWYpAUd98iZ3" +
            "7BUrX/aKzmFbt7clFSs6sXqHauqKWqdtLkF2KexO40H1YTX8z2lSgBBOAxLsvakl" +
            "V8k4cBFK9snQXE9/DDaFt6Rr7iVZMldczhC0JNgTz+SHXT6CBHuX3e9SdB1Ua44o" +
            "ncaTWz7OBGLbCiK45wIDAQAB"

    /** 1024 位公钥的模长（字节） */
    private const val RSA_BLOCK_SIZE = 128

    private val RANDOM = Random()

    /**
     * 加密请求体，返回可直接放入表单的 `params` / `encSecKey`。
     *
     * @param plainJson 明文 JSON 字符串（调用方用 `JSONObject` 序列化得到）
     */
    fun encrypt(plainJson: String): Map<String, String> {
        val secretKey = buildString {
            repeat(16) { append(BASE62[RANDOM.nextInt(BASE62.length)]) }
        }

        val first = aesCbcBase64(
            plainJson.toByteArray(Charsets.UTF_8),
            PRESET_KEY.toByteArray(Charsets.UTF_8),
            IV.toByteArray(Charsets.UTF_8)
        )
        val params = aesCbcBase64(
            first.toByteArray(Charsets.UTF_8),
            secretKey.toByteArray(Charsets.UTF_8),
            IV.toByteArray(Charsets.UTF_8)
        )
        val encSecKey = rsaNoPaddingHex(secretKey.reversed())

        return mapOf("params" to params, "encSecKey" to encSecKey)
    }

    private fun aesCbcBase64(data: ByteArray, key: ByteArray, iv: ByteArray): String {
        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), IvParameterSpec(iv))
        return Base64.encodeToString(cipher.doFinal(data), Base64.NO_WRAP)
    }

    /**
     * RSA 无填充（NoPadding）加密。
     *
     * 入参先左补零到模长 128 字节：数值上与原始短报文等价，
     * 但部分 Provider 要求 NoPadding 输入必须等长于模长，补零可规避该差异。
     */
    private fun rsaNoPaddingHex(data: String): String {
        val keyBytes = Base64.decode(PUBLIC_KEY_B64, Base64.DEFAULT)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(X509EncodedKeySpec(keyBytes))

        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, publicKey)

        val src = data.toByteArray(Charsets.UTF_8)
        val block = ByteArray(RSA_BLOCK_SIZE)
        System.arraycopy(src, 0, block, RSA_BLOCK_SIZE - src.size, src.size)

        return cipher.doFinal(block).joinToString("") { "%02x".format(it) }
    }
}
