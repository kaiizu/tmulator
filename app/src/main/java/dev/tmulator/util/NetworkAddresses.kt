package dev.tmulator.util

import android.content.Context
import android.net.ConnectivityManager
import android.provider.Settings
import dev.tmulator.transport.NetworkIdentity
import java.net.Inet4Address
import java.net.NetworkInterface
import java.security.MessageDigest

object NetworkAddresses {
    data class Info(
        val ipv4: List<String>,
        val identity: NetworkIdentity?,
    )

    fun inspect(context: Context): Info {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val props = try {
            cm?.activeNetwork?.let { cm.getLinkProperties(it) }
        } catch (_: Throwable) {
            null
        }

        val ips = LinkedHashSet<String>()
        var identity: NetworkIdentity? = null

        if (props != null) {
            val ipv4Links = props.linkAddresses.filter { it.address is Inet4Address }
            ipv4Links.mapNotNull { (it.address as? Inet4Address)?.hostAddress }
                .filter { !it.startsWith("169.254.") && it != "127.0.0.1" }
                .forEach { ips.add(it) }

            val primary = ipv4Links.firstOrNull()
            if (primary != null) {
                val address = primary.address as Inet4Address
                val ipBytes = address.address
                val maskBytes = prefixMask(primary.prefixLength)
                val gatewayBytes = try {
                    props.routes.firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                        ?.gateway?.address ?: ByteArray(4)
                } catch (_: Throwable) {
                    ByteArray(4)
                }
                val mac = macFor(props.interfaceName)?.takeUnless { it.isZeroMac() } ?: fallbackMac(context)
                identity = NetworkIdentity(
                    mac = mac,
                    ip = ipBytes,
                    netmask = maskBytes,
                    gateway = gatewayBytes,
                )
            }
        }

        if (ips.isEmpty()) {
            try {
                NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .filter { it.isUp && !it.isLoopback }
                    .flatMap { nif ->
                        nif.inetAddresses.toList().filterIsInstance<Inet4Address>()
                    }
                    .mapNotNull { it.hostAddress }
                    .filter { !it.startsWith("169.254.") && it != "127.0.0.1" }
                    .forEach { ips.add(it) }
            } catch (_: Throwable) {
            }
        }

        if (identity == null) {
            try {
                val nif = NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
                    .firstOrNull { nif ->
                        nif.isUp && !nif.isLoopback &&
                            nif.inetAddresses.toList().any { it is Inet4Address }
                    }
                val address = nif?.inetAddresses?.toList()?.filterIsInstance<Inet4Address>()?.firstOrNull()
                if (address != null) {
                    identity = NetworkIdentity(
                        mac = macFor(nif.name)?.takeUnless { it.isZeroMac() } ?: fallbackMac(context),
                        ip = address.address,
                        netmask = byteArrayOf(255.toByte(), 255.toByte(), 255.toByte(), 0),
                        gateway = ByteArray(4),
                    )
                }
            } catch (_: Throwable) {
            }
        }

        return Info(ips.toList(), identity)
    }

    fun localIpv4(context: Context): List<String> = inspect(context).ipv4

    private fun prefixMask(prefix: Int): ByteArray {
        val clamped = prefix.coerceIn(0, 32)
        val out = ByteArray(4)
        for (i in 0 until 32) {
            if (i < clamped) {
                out[i / 8] = (out[i / 8].toInt() or (0x80 ushr (i % 8))).toByte()
            }
        }
        return out
    }

    private fun macFor(interfaceName: String?): ByteArray? {
        return try {
            val name = interfaceName ?: return null
            val address = NetworkInterface.getByName(name)?.hardwareAddress ?: return null
            if (address.size >= 6) address.copyOfRange(0, 6) else null
        } catch (_: Throwable) {
            null
        }
    }

    private fun ByteArray.isZeroMac(): Boolean = all { it == 0.toByte() }

    /**
     * Android hides the real Wi-Fi MAC on modern releases. Use a stable,
     * locally-administered MAC derived from ANDROID_ID so ENPC clients have a
     * non-zero hardware identifier.
     */
    private fun fallbackMac(context: Context): ByteArray {
        val androidId = runCatching {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)
        }.getOrNull() ?: "tmulator"
        val digest = MessageDigest.getInstance("SHA-256").digest(androidId.toByteArray())
        val mac = digest.copyOfRange(0, 6)
        mac[0] = ((mac[0].toInt() and 0xFC) or 0x02).toByte() // locally administered, unicast
        return mac
    }

    fun format(ip: ByteArray): String {
        if (ip.size != 4) return "0.0.0.0"
        return ip.joinToString(".") { (it.toInt() and 0xFF).toString() }
    }

    fun parseIpv4(value: String?): ByteArray? {
        if (value.isNullOrBlank()) return null
        val parts = value.split('.')
        if (parts.size != 4) return null
        val bytes = ByteArray(4)
        for (i in 0..3) {
            val part = parts[i].toIntOrNull() ?: return null
            if (part !in 0..255) return null
            bytes[i] = part.toByte()
        }
        return bytes
    }
}
