package Tasks.TaskManager

import Enums.Permissions
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import Tasks.Enums.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * taskGraph is a class that acts as a task watchdog, garbage collector, and user monitoring system for running
 * tasks in the Ark server. Because long-running multithreaded tasks have such high stakes in regard to file access,
 * file safety, and data safety, it is important to monitor and manage them, and provide the ability for the users
 * and admins to manage, and directly kill stuck tasks.
 */
object taskGraph
{
    /**
     * Tasks that have been marked for garbage collection or marked as hung. Once a task is finished it becomes
     * visible to be monitored by taskGraph's garbage collector. Tasks which are either hung, or marked as garbage
     * will be swept by taskGraph at set intervals defined by the global config file.
     */
    private val markedTasks = mutableListOf<Task>()

    /**
     * Time in milliseconds that taskGraph will wait between sweeps.
     */
    private var sweepInterval : Int = 5000 //In milliseconds

    /**
     * taskGraph performs marks and sweeps on the main thread and is not a multithreaded operation, but is
     * an async coroutine operation.
     */
    private val sweepScope = CoroutineScope(Dispatchers.Main)


    fun init(interval : Int)
    {
        sweepInterval = interval

        sweepScope.launch {

            withContext(NonCancellable){

                while(true)
                {
                    markAndSweep()
                    delay(sweepInterval.toLong())
                }
            }
        }
    }

    /**
     * Collect all tasks and then scan them to determine if they are completed or hung.
     * Any task marked as completed but is still running is hung. Likewise, any task marked as running
     * but does not have a running job is hung.
     */
    suspend fun markAndSweep()
    {
        //Gather read only copy of all tasks.
        val tasks = taskManager.getTasks()

        //Copy marked tasks so we can remove them on a loop safely.
        val markedCopy = markedTasks.toList()

        //Sweep all tasks marked as garbage.
        for(garbage in markedCopy)
        {
            /**
             * Destroy the task and then remove it from the list of marked tasks.
             */
            taskManager.destroyTask(garbage)
            markedTasks.remove(garbage)
        }

        //Mark any new tasks not already marked as garbage. They will be swept on the next pass.
        for(task in tasks)
        {
            //Any tasks marked as garbage can be swept.
            if(task.isGarbage)
            {
                markedTasks.add(task)
            }

            /**
             * Any task that is marked as complete but still has a running job is hung.
             */
            if(task.getTaskSettings().getStatus() == TaskStatus.Complete && task?.getRunningJob()?.isActive == true)
            {
                markedTasks.add(task)
                task.markTaskHung() //Denote that this task is hung and should be swept on the next sweep.
            }

            //Mark any tasks that are running but do not have a running job.
            if(task.getTaskSettings().getStatus() == TaskStatus.Running && task?.getRunningJob()?.isActive == false)
            {
                markedTasks.add(task)
                task.markTaskHung() //Denote that this task is hung and should be swept on the next sweep.
            }

            //Collect any hung tasks that were hung from exiting jobs and mark them as well.
            if(task.getTaskSettings().getStatus() == TaskStatus.Hung)
            {
                task.markTaskHung()
                markedTasks.add(task)
            }

        }

    }

    /**
     * Search for tasks that match the given criteria. Only applies filters for parameters that are not empty or null.
     * @param username Filter by task owner username. Empty string ignores this filter.
     * @param permissions Filter by required permissions. Null ignores this filter.
     * @param action Filter by task action type. Null ignores this filter.
     * @param status Filter by task status. Null ignores this filter.
     * @param category Filter by task category. Null ignores this filter.
     * @return List of tasks matching the specified criteria.
     */
    suspend fun searchTasks(
        username : String = "",
        permissions: Permissions? = null,
        action : TaskAction? = null,
        status : TaskStatus? = null,
        category : TaskCategory? = null
    ) : List<Task>
    { 
        return taskManager.getTasks().filter { task ->
            (username.isEmpty() || task.getTaskSettings().owner == username) &&
            (permissions == null || task.getTaskSettings().permissions == permissions) &&
            (action == null || task.getTaskSettings().action == action) &&
            (status == null || task.getTaskSettings().getStatus() == status) &&
            (category == null || task.getTaskSettings().category == category)
        }
    }
}