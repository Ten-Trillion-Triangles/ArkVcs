package Util

import java.io.File
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

object FileUtil {
    
    @OptIn(ExperimentalEncodingApi::class)
    fun calculateChecksum(data: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val hash = digest.digest(data)
        return Base64.encode(hash)
    }
    
    fun ensureDirectoryExists(filePath: String): Boolean {
        val file = File(filePath)
        val parentDir = file.parentFile
        return parentDir?.exists() == true || parentDir?.mkdirs() == true
    }
    
    fun isValidFilePath(filePath: String): Boolean {
        return filePath.isNotEmpty() && 
               !filePath.contains("..") && 
               !filePath.startsWith("/") &&
               filePath.matches(Regex("^[a-zA-Z0-9._/-]+$"))
    }
    
    fun getFileExtension(fileName: String): String {
        return fileName.substringAfterLast('.', "")
    }
}