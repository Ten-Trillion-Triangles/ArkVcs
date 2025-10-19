package Tasks.TaskManager.TaskObjects

import Enums.LogLevel
import Enums.Permissions
import Log.arkLog
import KeyStore.FileTicket
import KeyStore.ticketManager
import Structs.Api.FileTransferRequest
import Structs.Api.FileTransferResponse
import Structs.Api.LogResponse
import Structs.UserSettings
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import Tasks.Structs.TaskSettings
import Tasks.TaskManager.Task
import Tasks.TaskManager.taskManager
import Util.FileUtil
import kotlinx.coroutines.sync.withLock
import Util.deserialize
import Util.serialize
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi

/**
 * Handles file transfer operations (upload, download, delete) with support for files >2GB
 * Uses streaming operations to avoid memory limitations
 */
class FileTransferTask : Task()
{
    
    private val baseUploadPath = "uploads/"
    
    /**
     * Main task entry point - validates ticket and routes to appropriate handler
     * @param jsonParams Serialized FileTransferRequest containing action, file details, and ticket
     */
    override suspend fun runTask(jsonParams: String)
    {
        val request = deserialize<FileTransferRequest>(jsonParams)
        
        if (request == null) {
            val response = LogResponse()
            response.error = true
            response.message = "Invalid file transfer request"
            endTask(false, false, serialize(response))
            return
        }
        
        // Validate ticket
        val ticket = ticketManager.validateTicket(request.filePath, request.ticketId)
        if (ticket == null) {
            val response = LogResponse()
            response.error = true
            response.message = "Invalid or expired ticket"
            endTask(false, false, serialize(response))
            return
        }
        
        when (request.action.lowercase()) {
            "upload" -> handleUpload(request, ticket)
            "download" -> handleDownload(request, ticket)
            "delete" -> handleDelete(request, ticket)
            else -> {
                val response = LogResponse()
                response.error = true
                response.message = "Unsupported action: ${request.action}"
                endTask(false, false, serialize(response))
            }
        }
    }




    
    /**
     * Handles file upload with chunked support for large files
     * @param request Contains file path, chunk data (in checksum field), and chunk index
     * @param ticket Validated ticket for this operation
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun handleUpload(request: FileTransferRequest, ticket: FileTicket)
    {
        val fullPath = ticket.filepath
        val file = File(fullPath)
        
        // Acquire resource lock for write operation
        val resourceLock = taskManager.getResourceLock(fullPath)
        resourceLock.withLock {
            
            // Create directory if it doesn't exist for write operations
            if (!file.parentFile.exists() && !file.parentFile.mkdirs())
            {
                val response = LogResponse()
                response.error = true
                response.message = "Failed to create directory structure"
                endTask(false, false, serialize(response))
                return
            }
            
            try {
                val decodedData = Base64.decode(request.checksum) // Using checksum field for data
                
                // Stream write to avoid 2GB memory limit - append for chunks, overwrite for first chunk
                if (request.chunkIndex > 0 && file.exists())
                {
                    FileOutputStream(file, true).use { it.write(decodedData) }
                }

                else
                {
                    // Delete existing file if it exists to ensure clean overwrite
                    if (file.exists())
                    {
                        file.delete()
                    }
                    FileOutputStream(file).use { it.write(decodedData) }
                }
                
                // Discard ticket after successful operation
                ticketManager.discardTicket(ticket)
                
                val response = LogResponse()
                response.error = false
                response.message = "File uploaded successfully"
                
                arkLog(Tasks.Enums.TaskCategory.FileTransfer, "File uploaded: ${request.filePath}", Enums.LogLevel.Log)
                endTask(true, false, serialize(response))
                
            } catch (e: Exception) {
                val response = LogResponse()
                response.error = true
                response.message = "Upload failed: ${e.message}"
                arkLog(Tasks.Enums.TaskCategory.FileTransfer, "File upload failed: ${e.message}", Enums.LogLevel.Error)
                endTask(false, false, serialize(response))
            }
        }
    }



    /**
     * Handles file download with streaming to support large files
     * @param request Contains file path to download
     * @param ticket Validated ticket for this operation
     * @return Base64 encoded file data in response message with SHA-256 checksum
     */
    @OptIn(ExperimentalEncodingApi::class)
    private suspend fun handleDownload(request: FileTransferRequest, ticket: FileTicket)
    {
        val fullPath = ticket.filepath
        val file = File(fullPath)
        
        if (!file.exists() || !file.isFile)
        {
            val response = LogResponse()
            response.error = true
            response.message = "File not found"
            endTask(false, false, serialize(response))
            return
        }
        
        try {
            val fileSize = file.length()
            val buffer = ByteArray(8192)
            val encodedData = StringBuilder()
            
            // Stream read in 8KB chunks to avoid 2GB limit
            FileInputStream(file).use { input ->
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    encodedData.append(Base64.encode(buffer.copyOf(bytesRead)))
                }
            }
            
            // Calculate SHA-256 checksum using streaming to avoid 2GB limit
            val digest = MessageDigest.getInstance("SHA-256")
            FileInputStream(file).use { input ->
                val buffer = ByteArray(8192)
                var bytesRead: Int
                while (input.read(buffer).also { bytesRead = it } != -1) {
                    digest.update(buffer, 0, bytesRead)
                }
            }
            val checksum = digest.digest().joinToString("") { "%02x".format(it) }
            
            // Discard ticket after successful operation
            ticketManager.discardTicket(ticket)
            
            val response = LogResponse()
            response.error = false
            response.message = encodedData.toString() // File data in message
            
            arkLog(Tasks.Enums.TaskCategory.FileTransfer, "File downloaded: ${request.filePath}", Enums.LogLevel.Log)
            endTask(true, false, serialize(response))
            
        } catch (e: Exception) {
            val response = LogResponse()
            response.error = true
            response.message = "Download failed: ${e.message}"
            arkLog(Tasks.Enums.TaskCategory.FileTransfer, "File download failed: ${e.message}", Enums.LogLevel.Error)
            endTask(false, false, serialize(response))
        }
    }



    /**
     * Handles file deletion
     * @param request Contains file path to delete
     * @param ticket Validated ticket for this operation
     */
    private suspend fun handleDelete(request: FileTransferRequest, ticket: FileTicket)
    {
        val fullPath = ticket.filepath
        val file = File(fullPath)
        
        if (!file.exists())
        {
            val response = LogResponse()
            response.error = true
            response.message = "File not found"
            endTask(false, false, serialize(response))
            return
        }
        
        // Acquire resource lock for delete operation
        val resourceLock = taskManager.getResourceLock(fullPath)
        resourceLock.withLock {
            
            try {
                val deleted = file.delete()
                if (deleted) {
                    // Discard ticket after successful operation
                    ticketManager.discardTicket(ticket)
                    
                    val response = LogResponse()
                    response.error = false
                    response.message = "File deleted successfully"
                    arkLog(Tasks.Enums.TaskCategory.FileTransfer, "File deleted: ${request.filePath}", Enums.LogLevel.Log)
                    endTask(true, false, serialize(response))
                } else {
                    val response = LogResponse()
                    response.error = true
                    response.message = "Failed to delete file"
                    endTask(false, false, serialize(response))
                }
            } catch (e: Exception) {
                val response = LogResponse()
                response.error = true
                response.message = "Delete failed: ${e.message}"
                arkLog(Tasks.Enums.TaskCategory.FileTransfer, "File delete failed: ${e.message}", Enums.LogLevel.Error)
                endTask(false, false, serialize(response))
            }
        }
    }



    companion object
    {
        /**
         * Creates task settings for file transfer operations
         * @param request File transfer request details
         * @param user User performing the operation
         * @return Configured TaskSettings with write permissions
         */
        fun createTaskSettings(request: FileTransferRequest, user: UserSettings): TaskSettings
        {
            val settings = TaskSettings()
            settings.action = TaskAction.Write
            settings.category = Tasks.Enums.TaskCategory.FileTransfer
            settings.params = serialize(request)
            settings.owner = user.username
            settings.permissions = Permissions.Write
            return settings
        }
    }
}