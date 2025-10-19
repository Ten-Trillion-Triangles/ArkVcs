package Structs

import kotlinx.serialization.Serializable

/**
 * ArkVcs stores all files in a project/repo in versioned folders.
 * These versioned folders are split by changelist, and then version of each file.
 * This ensures Ark can quickly lookup where files are in a project.
 * However, this also means that the location of the files and even names no longer matches that of the real file system.
 * So in order to download the files back to a developer's machine we must map out a virtual file system on the server.
 * This file system will match in a relative manner to the real file system. And will do so starting from the project's
 * root folder.
 */
@Serializable
data class VirtualFileSystem(var init : Boolean = false)
{
    /*Maps each file manifest to a virtual path in ark.
    This path is the actual path that should be used in a real file system
    for the given repo.

    Ark stores the hosted files by version paths which will never match
    the real paths when located on a developer's machine.

    The key is the file manifest. This houses all the information needed by ark to
    track its storage, versioning, and project ownership.

    The value is the virtual path to the file EX:
    /ProjectName/src/main/kotlin/Structs/ProjectManifest.kt
     */
    var virtualPaths = mutableMapOf<FileManifest, String>()


}
