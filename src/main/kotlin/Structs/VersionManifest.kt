package Structs
import kotlinx.serialization.Serializable

/**
 * Denotes info about a specific version of a file held under ArkVcs.
 * This holds the relative name of the file which is its global identifier,
 * the short name of the file which is the name of the file without the remainder of the path,
 * the starting dir in which all version manifests and files by this name exist,
 * and finally, a map that denotes version numbers to individual file manifests.
 *
 * Each version manifest is held in a map of version numbers to file manifests.
 * In turn, the version number which is used to denote part of the path to and the extension of the file itself,
 * is stored in the changelist manifest files. And that in turn, is held as an array inside the project manifest.
 */
@Serializable
data class VersionManifest(var init : Boolean = false)
{
    var relativeName = "" //Relative path starting from ./Project to end path of the file.
    var shortName = "" //Name of the file without the remainder of the path.
    var arkRootDir = "" //Starting dir in which all version manifests and files by this name exist.
    var versionMap = mutableMapOf<ULong, FileManifest>()
}
