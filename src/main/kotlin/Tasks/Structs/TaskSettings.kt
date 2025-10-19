package Tasks.Structs

import Enums.Permissions
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import Tasks.Enums.TaskStatus
import java.time.Instant

/**
 * Data class that holds metadata and settings for a given task. Tracks who created the task,
 * what category it belongs to, the action it is doing, and the status of the task.
 */
@kotlinx.serialization.Serializable
data class TaskSettings(val cinit: Boolean = false)
{
//=========================================== Properties =============================================================//

    var owner = "" //The user that created the task
    var description = "" //A description of the task
    var category = TaskCategory.Empty //The category of the task
    var action = TaskAction.Read //The action of the task. Affects resource locks.
    var permissions = Permissions.Write //Expected permissions level.
    var params = "" //A JSON string containing the parameters needed to run the task
    var statusDescription = "" //Optional feedback for the user regarding what the task is currently doing.
    var lockedResources = listOf<String>() //A list of resources that are locked by the task. Only for visual monitoring. Does not have any acutal function.
    var taskId : Long = -1

    private var status = TaskStatus.Scheduled //The status of the task. Hung tasks will be swept by taskGraph.

    //Used to track when the task was created and to track duration of its execution.
    val created = Instant.now().toString()

//=========================================== Functions ==============================================================//
    /**
     * Sets the ongoing status of the task. This should be called by the Task class, taskManager, or taskGraph.
     * @see Tasks.TaskGraph.Task
     * @see Tasks.TaskManager.TaskManager.kt
     * @see taskGraph
     */
    fun setStatus(status: TaskStatus)
    {
        this.status = status
    }

    fun getStatus() : TaskStatus
    {
        return status
    }




}
