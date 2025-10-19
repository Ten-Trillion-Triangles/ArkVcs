package Structs

import Enums.FileType

/**
 * Manifest json struct for a file. Denotes absolute values about the file such as,
 * project name that owns it, relative pathname of the file, and type of file.
 *
 * This struct is held inside other json structs including changelist manifest, and version manifests.
 */
@kotlinx.serialization.Serializable
data class FileManifest(var init : Boolean = false)
{
    var projectName = "" //Repo this file is in.
    var filename = "" //File name without path to file.
    var relativeName = "" //Includes project relative path to the file for duplicate protection.
    var type = FileType.Text //Determines if we need to use exclusive locking or not.

    var versionNumber : ULong = 0u
    var arkDir = ""
    var arkPath = ""


}
