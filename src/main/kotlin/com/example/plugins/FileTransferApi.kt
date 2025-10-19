package com.example.plugins

import Structs.Api.FileTransferRequest
import Structs.Api.Request
import Tasks.TaskRunner.runFileTransferTask
import Util.serialize

/**
 * API function to handle file transfer requests through the existing RPC system.
 * This integrates with your existing authentication and encryption framework.
 */
suspend fun handleFileTransferRequest(json: String, user: Structs.UserSettings): String {
    return runFileTransferTask(json, user)
}

/**
 * Helper function to create file transfer requests for common operations
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
object FileTransferApi {
    
    fun createUploadRequest(filePath: String, fileName: String, fileData: ByteArray): Request {
        val fileRequest = FileTransferRequest(
            action = "upload",
            filePath = filePath,
            fileName = fileName,
            fileSize = fileData.size.toLong(),
            checksum = kotlin.io.encoding.Base64.encode(fileData)
        )
        
        val request = Request()
        request.json = serialize(fileRequest)
        request.function = ::handleFileTransferRequest
        return request
    }
    
    fun createDownloadRequest(filePath: String): Request {
        val fileRequest = FileTransferRequest(
            action = "download",
            filePath = filePath
        )
        
        val request = Request()
        request.json = serialize(fileRequest)
        request.function = ::handleFileTransferRequest
        return request
    }
    
    fun createDeleteRequest(filePath: String): Request {
        val fileRequest = FileTransferRequest(
            action = "delete",
            filePath = filePath
        )
        
        val request = Request()
        request.json = serialize(fileRequest)
        request.function = ::handleFileTransferRequest
        return request
    }
}