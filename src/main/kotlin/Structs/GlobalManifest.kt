package Structs

import Enums.Permissions
import kotlinx.serialization.Serializable

/**
 * Top level manifest file which acts as both the root of Ark's file system and
 * the root file that stores the locations of all project manifests.
 */
@Serializable
data class GlobalManifest(var init : Boolean = false)
{
    //List of all repos stored on this server.
    var repoList = mutableListOf<String>()

    //List of all users global permission settings.
    var globalPermissions = mutableMapOf<String, Permissions>()
}
