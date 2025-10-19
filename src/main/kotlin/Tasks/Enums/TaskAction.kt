package Tasks.Enums

/**
 * Defines the actions a given task is doing. This is used to determine how to handle
 * resource locking and thread safety as well as provide information to anyone
 * reading TaskGraph to see what resources are being accessed.
 */
enum class TaskAction
{
    Read,
    Write,
    Copy,
    Delete,
    Merge,
    Repair
}