package Tasks.Enums

/**
 * Defines categories of tasks that are ran by the task scheduler.
 * A task in Ark is a long-running coroutine that is doing some form of major work and resource allocation.
 * Examples of a task include:
 * - Writing to files on the database
 * - Transferring files from and to the client
 * - Updating and managing changelists, file versions, and version control status
 * - Encryption operations
 * - Admin tasks and user management
 */
enum class TaskCategory
{
    Admin,
    Auth,
    Encryption,
    Security,
    Database,
    FileTransfer,
    Maintenance,
    Empty //Used for any defaults for variable declarations.

}