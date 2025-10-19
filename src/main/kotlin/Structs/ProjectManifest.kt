package Structs
import Enums.FileType
import Enums.ForkSettings
import Enums.Permissions
import kotlinx.serialization.*


/**
 * Defines the settings file for a given project.
 * Projects are ark's internal term for repositories.
 */
@Serializable
data class ProjectManifest(var init : Boolean = false)
{
    var projectName = "" //Official name of the repo/project
    var parentProjectName = "" //Only valid if this is a fork.
    var forkType = ForkSettings.Repo //Current state of our fork if valid.

    /*Latest known changelist number in the project.
    Used to denote the range to start scanning and mapping changelists.*/
    var latestChangelist : ULong = 0u
    var changelistMap : MutableMap<ULong, ChangelistManifest> = mutableMapOf()

    /*Denotes each user and permission level for this project.
    Global permissions of Maintainer or above will override project level permissions*/
    var userList = mutableMapOf<String, Permissions>()

    //List of all checked out files for the project
    var checkoutList = mutableListOf<CheckoutManifest>()

    /*Virtual file system for the project. Used to map files to real paths on a
    developer's machine.
     */
    var virtualFileSystem = VirtualFileSystem()

    /*Maps relative filenames from the project root to the version manifest for that file.*/
    var versionMap = mutableMapOf<String, VersionManifest>()

    //Weather the ark client service should attempt to automatically checkout files when they become writable.
    var automaticCheckout = true

    //If true changelists that are mergeable cannot be submitted until they are reviewed by a maintainer or admin.
    var requireReview = false

    //List of file types to automatically checkout. Determines if a type should be excluded from automatic checkout.
    var automaticCheckoutFileTypes = mutableSetOf<FileType>()

    /**
     * Maps file extensions to the type of file they are. This determines weather Ark must treat a file type as
     * exclusive checkout or not. File types can be treated as text which are mergeable, or binary which are not which
     * must be treated as exclusive checkout.
     */
    var extensionTypes = mutableMapOf<String, FileType>()



}
