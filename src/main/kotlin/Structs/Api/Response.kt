package Structs.Api

import Enums.Permissions
import Structs.ProjectManifest
import Structs.VirtualFileSystem


/**
 * Response that returns a single void function with no parameters or return values.
 * This is useful for responses that warrant some specific action to be taken that
 * has values stored in a known place thusly not needing to be passed as a parameter.
 *
 * The old closure-based field was replaced with a string name to fix the serialization
 * blocker: function references cannot cross the serialization boundary.
 * Client-side dispatch uses ArkFunctionRegistry lookup by functionName.
 */
@kotlinx.serialization.Serializable
data class VoidResponse(val cinit: Boolean = true)
{
    var functionName = ""
}

/**
 * Response that returns a single void function with a json parameter.
 * This is useful for responses that warrant some specific action to be taken that
 * has values stored in a known place thusly not needing to be passed as a parameter.
 *
 * The old closure-based field was replaced with string names to fix the serialization
 * blocker: function references cannot cross the serialization boundary.
 * Client-side dispatch uses ArkFunctionRegistry lookup by functionName.
 */
@kotlinx.serialization.Serializable
data class VoidParamResponse(val cinit: Boolean = true)
{
    var functionName = ""
    var args = ""
}

/**
 * Response that returns a list of virtual paths to the expected project location of files inside of it.
 * This is useful for building a file tree for a repo, or list of virtual locations in the repo's structure
 * that are to be downloaded. The virtual project root path is provided to denote the root folder of the repo
 * and it's project contents. The virtual paths are relative to the project root in a virtual space where the
 * client's own file system and absolute path is not accounted for. The client must resolve the real path to the
 * location of project files, and then resolve the relative path to the specific file.
 *
 * @see VirtualFileSystem for more information on how Ark's virtual file system works.
 */
@kotlinx.serialization.Serializable
data class FileResponse(val cinit: Boolean = true)
{
    var virtualProjectRootPath = ""
    var virtualPaths = mapOf<String, String>()
}

/**
 * Response that returns information about a repo stored on the server. This is intended to be used for visual
 * purposes in the UI and informing the user of the current state of the repo.
 *
 * @param projectManifest The manifest file which contains information about the project itself.
 * @param accessRights The access rights that the user has to the repo.
 */
@kotlinx.serialization.Serializable
data class RepoInfoResponse(val cinit: Boolean = true)
{
    var projectManifest = ProjectManifest()
    var accessRights = Permissions.Write
}

/**
 * Response that returns logging info regarding an Ark operation to the client.
 *
 * @param error Boolean that indicates if the response is a success or failure.
 * @param message String that contains information about the operation.
 */
@kotlinx.serialization.Serializable
data class LogResponse(val cinit: Boolean = true)
{
    var error = false
    var message = ""
}

/**
 * Response struct containing a single string value. Useful for things like auth key transfer, or challenge responses.
 */
@kotlinx.serialization.Serializable
data class StringResponse(var string: String = "")
