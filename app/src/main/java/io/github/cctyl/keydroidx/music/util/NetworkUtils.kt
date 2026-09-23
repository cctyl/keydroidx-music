package io.github.cctyl.keydroidx.music.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

/**
 * 联网状态判定。
 *
 * ⚠️ 这里刻意采用**宽松判定**：本工具只用于「跳过注定失败的请求」这类优化，
 * 以及失败后的归因提示，**绝不作为阻断播放/请求的硬闸门**。
 * 真正的判据永远是请求本身是否成功。
 *
 * 历史坑：旧实现要求 `activeNetwork` 存在，且 transport ∈ {Wi-Fi, 蜂窝, 以太网, 蓝牙}。
 * 在「手机 rndis0 手工配 IP + 电脑 ICS 共享上网」这类非标准形态下会误判为无网：
 * rndis0 未经 ConnectivityService 注册 NetworkAgent（`activeNetwork` 为 null），
 * 或缺少 `NET_CAPABILITY_INTERNET`（联网校验打的是被墙的 gstatic，必然失败），
 * 或 transport 不在白名单内。但此时内核路由与 DNS 均可用，表现为
 * 「搜索正常、播放却提示无网络」。故此处放宽，并在判定失败时兜底检查网卡。
 */
object NetworkUtils {

    /**
     * 检查当前设备是否**可能**可以联网（Wi-Fi、蜂窝、以太网、USB/RNDIS、VPN 等）。
     *
     * 结果为 true 不代表上游真的可达（例如网卡 up 但 NAT 已断）。
     */
    fun isNetworkAvailable(context: Context?): Boolean {
        if (context == null) return false
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        if (cm != null && isConnectivityManagerUsable(cm)) return true
        // ConnectivityManager 判定失败时兜底看网卡：rndis0 / usb0 / rmnet* 等已 up
        // 且拿到非 link-local IPv4 的接口，说明物理链路可能存在。
        // 宁可放过，也不误杀能上网的非常规联网形态。
        return hasUsableNetworkInterface()
    }

    private fun isConnectivityManagerUsable(cm: ConnectivityManager): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            @Suppress("DEPRECATION")
            val networkInfo = cm.activeNetworkInfo ?: return false
            @Suppress("DEPRECATION")
            return networkInfo.isConnected
        }

        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        // 不再限定 transport：RNDIS/USB 网卡、VPN 等不在旧白名单内的类型同样应被认可。
        // 也刻意不要求 NET_CAPABILITY_VALIDATED —— 联网校验依赖 connectivitycheck.gstatic.com，
        // 国内环境下长期无法通过，要求它会把「能上网」判成「无网」。
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    /**
     * 兜底判定：是否存在「已启用 + 拿到可路由 IPv4 地址」的网卡。
     * 只看链路层，不判断上游是否可达（那只能靠请求结果）。
     */
    private fun hasUsableNetworkInterface(): Boolean {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            interfaces.any { nif ->
                try {
                    nif.isUp && !nif.isLoopback &&
                        Collections.list(nif.inetAddresses).any { addr ->
                            addr is Inet4Address && !addr.isLoopbackAddress && !addr.isLinkLocalAddress
                        }
                } catch (e: Exception) {
                    // 某些设备上 NPE/SocketException 由 isUp 抛出，单个网卡异常不影响整体判定
                    false
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
