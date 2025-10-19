package Structs

import kotlinx.serialization.Serializable

/**
 * Manifest that denotes the status of what files are checked out, and what operations
 * on those files are being attempted. This is always held and stored inside a changelist manifest as a
 * nested json struct.
 */
@Serializable
data class CheckoutManifest(var init : Boolean = false)
{

    var projectParent = "" //Name of the repo this file belongs to.
    var fileName = ""
    var changelistNumber : ULong = 0u
    var versionNumber : ULong = 0u
    var exclusiveCheckout = false //True if binary file. File locking required in that case.

    var checkedoutBy = mutableListOf<String>()

}
