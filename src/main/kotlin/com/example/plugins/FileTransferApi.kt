package com.example.plugins

import Structs.Api.ArkRpcRequest
import Structs.Api.FileTransferRequest
import Util.serialize

/**
 * API function to handle file transfer requests through the existing RPC system.
 * This integrates with your existing authentication and encryption framework.
 *
 * Note: This function is no longer used as the dispatch target directly.
 * Clients send ArkRpcRequest(functionName="runFileTransferTask", args=...).
 * The server looks up the function in ArkFunctionRegistry and invokes it.
 * Kept for reference and backwards compatibility if needed.
 */
suspend fun handleFileTransferRequest(json: String, user: Structs.UserSettings): String {
    return Tasks.TaskRunner.runFileTransferTask(json, user)
}

/**
 * Helper function to create file transfer requests for common operations.
 * Returns ArkRpcRequest which is fully serializable (no function closure fields).
 */
@OptIn(kotlin.io.encoding.ExperimentalEncodingApi::class)
object FileTransferApi {

    /**
     * Create an upload request.
     * The server dispatches to "runFileTransferTask" via ArkFunctionRegistry.
     */
    fun createUploadRequest(filePath: String, fileName: String, fileData: ByteArray): ArkRpcRequest {
        val fileRequest = FileTransferRequest(
            action = "upload",
            filePath = filePath,
            fileName = fileName,
            fileSize = fileData.size.toLong(),
            checksum = kotlin.io.encoding.Base64.encode(fileData)
        )

        return ArkRpcRequest(
            functionName = "runFileTransferTask",
            args = serialize(fileRequest)
        )
    }

    /**
     * Create a download request.
     * The server dispatches to "runFileTransferTask" via ArkFunctionRegistry.
     */
    fun createDownloadRequest(filePath: String): ArkRpcRequest {
        val fileRequest = FileTransferRequest(
            action = "download",
            filePath = filePath
        )

        return ArkRpcRequest(
            functionName = "runFileTransferTask",
            args = serialize(fileRequest)
        )
    }

    /**
     * Create a delete request.
     * The server dispatches to "runFileTransferTask" via ArkFunctionRegistry.
     */
    fun createDeleteRequest(filePath: String): ArkRpcRequest {
        val fileRequest = FileTransferRequest(
            action = "delete",
            filePath = filePath
        )

        return ArkRpcRequest(
            functionName = "runFileTransferTask",
            args = serialize(fileRequest)
        )
    }
}
