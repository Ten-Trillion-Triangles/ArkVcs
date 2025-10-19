package Structs

import Enums.Permissions
import kotlinx.serialization.Serializable


@Serializable
data class UserSettings(var init : Boolean = false)
{
    var username : String = ""
    var email : String = ""
    var authKey : String = ""
    var permissions : Permissions = Permissions.Write
    var repoAccessList = mutableListOf<String>()
}
