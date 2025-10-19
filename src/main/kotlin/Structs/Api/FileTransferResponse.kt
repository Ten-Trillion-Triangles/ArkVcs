package Structs.Api

import kotlinx.serialization.Serializable

@Serializable
data class FileTransferResponse(
    var success: Boolean = false,
    var message: String = "",
    var filePath: String = "",
    var fileSize: Long = 0,
    var checksum: String = "",
    var bytesTransferred: Long = 0
)


data class FileTicket(val cinit: Boolean = false)
{
    
}