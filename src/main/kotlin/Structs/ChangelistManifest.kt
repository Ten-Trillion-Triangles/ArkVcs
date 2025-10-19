package Structs

import Enums.CheckoutMethod
import kotlinx.serialization.Serializable

/**
 * Defines a given changelist and it's contents. This includes files, users, versions,
 * submission dates, etc.
 */
@Serializable
data class ChangelistManifest(var init : Boolean = false)
{
    var projectOwner = "" //Repo name this file changelist belongs to.
    var changelistNumber : ULong = 0u
    var submissionDate : String = "" //Formatted year/month/day.
    var files = mutableMapOf<FileManifest, CheckoutMethod>() //Each file and the action to apply.
    var versions = mutableMapOf<FileManifest, VersionManifest>() //Each file and it's current version.
    var commitMessage = ""
    var reviewRequired =  false

}
