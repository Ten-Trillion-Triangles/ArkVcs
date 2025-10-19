package Global

import java.io.File
import java.net.NetworkInterface
import java.util.Locale

/**
 * Utility class to generate a hardware ID. Used as part of the seed for the programmatic key used for encryption.
 * Ark matches allowed devices by their hardware ID, as well as uses this hardware ID to create the client key
 * required for communication after the initial handshake, as well as to unlock the challenge issued by the server
 * upon receiving the client key.
 */
object hwidSeedProvider {

    // Lazily initialized and cached
    private val seed: String by lazy {
        detectAndGenerateHWID()
    }

    @JvmStatic
    fun getHwidSeed(): String = seed

    private fun detectAndGenerateHWID(): String {
        val os = System.getProperty("os.name").lowercase(Locale.getDefault())
        return try {
            when {
                os.contains("win") -> getWindowsHWID()
                os.contains("mac") -> getMacHWID()
                os.contains("nix") || os.contains("nux") || os.contains("aix") -> getLinuxHWID()
                else -> throw UnsupportedOperationException("Unsupported OS: $os")
            }
        } catch (e: Exception) {
            getFallbackHWID()
        }
    }

    private fun getWindowsHWID(): String {
        val process = ProcessBuilder("wmic", "csproduct", "get", "uuid")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val lines = output.lines().filter { it.isNotBlank() }
        if (lines.size >= 2) return lines[1].trim()
        throw IllegalStateException("Failed to retrieve Windows UUID")
    }

    private fun getMacHWID(): String {
        val process = ProcessBuilder("ioreg", "-rd1", "-c", "IOPlatformExpertDevice")
            .redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().readText()
        val regex = Regex("\"IOPlatformUUID\" = \"([^\"]+)\"")
        return regex.find(output)?.groupValues?.get(1)
            ?: throw IllegalStateException("Failed to retrieve macOS UUID")
    }

    private fun getLinuxHWID(): String {
        val machineIdPaths = listOf("/etc/machine-id", "/var/lib/dbus/machine-id")
        for (path in machineIdPaths) {
            val file = File(path)
            if (file.exists()) {
                val id = file.readText().trim()
                if (id.isNotEmpty()) return id
            }
        }
        throw IllegalStateException("Failed to retrieve Linux machine-id")
    }

    private fun getFallbackHWID(): String {
        val interfaces = NetworkInterface.getNetworkInterfaces()
        for (intf in interfaces) {
            if (!intf.isLoopback && intf.hardwareAddress != null) {
                return intf.hardwareAddress.joinToString("") { "%02X".format(it) }
            }
        }
        throw IllegalStateException("No valid MAC address found")
    }
}