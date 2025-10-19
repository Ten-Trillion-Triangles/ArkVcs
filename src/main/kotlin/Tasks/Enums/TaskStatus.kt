package Tasks.Enums

/**
 * Denotes the status of a given task. This is used to provide logging and debugging information
 * to the system admin, as well as to provide information to TaskGraph to determine what tasks
 * to sweep.
 */
enum class TaskStatus
{
    Scheduled,
    Running,
    Complete,
    Failed,
    Hung
}