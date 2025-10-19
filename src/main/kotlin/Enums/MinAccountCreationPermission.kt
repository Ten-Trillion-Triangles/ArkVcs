package Enums

/**
 * Denotes the minimum global permission level a user must have to create an account.
 */
enum class MinAccountCreationPermission
{
    Admin, //Only the admin can create an account.
    Maintainer, //The admin and maintainers can create an account.
    Any //Any user can create an account.
}