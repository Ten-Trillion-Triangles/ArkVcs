package Enums

import kotlinx.serialization.Serializable

/**Defines the permissions for a user.
 * Permissions can be defined globally, or per project.
 * If a global permission level is Maintainer or above, the global permission setting will override
 * any project level permissions.
 */
@Serializable
enum class Permissions
{
    Admin, //Superuser level permission
    Maintainer, //Full rights over a given repo but not over other repos.
    Write, //Read/write access to a given repo.
    ReadOnly,
    None
}