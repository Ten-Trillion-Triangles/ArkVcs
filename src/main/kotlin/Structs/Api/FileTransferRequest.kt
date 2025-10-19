package Structs.Api

import kotlinx.serialization.Serializable
import java.util.UUID
import kotlin.uuid.Uuid

@Serializable
data class FileTransferRequest(
    val action: String = "", // "upload", "download", "delete", "overwrite", "merge"
    val filePath: String = "",
    val fileName: String = "",
    val fileSize: Long = 0,
    val chunkIndex: Int = 0,
    val totalChunks: Int = 1,
    val checksum: String = "",
    var ticketId: String = ""
)


