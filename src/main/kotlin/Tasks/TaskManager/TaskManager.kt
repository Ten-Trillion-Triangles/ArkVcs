package Tasks.TaskManager

import Tasks.Structs.TaskSettings
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.reflect.KClass

/**
 * Singleton object to manage all concurrent tasks in the Ark server.
 * Manages running tasks, file locks, thread safety, and other resource locks.
 * Can be read by taskGraph to determine what tasks to sweep, provide information to the
 * admin and users, and to allow users and the admin to cancel tasks.
 */
object taskManager
{
//=============================================== Properties =========================================================//

    /**
     * Used to generate unique task IDs. Incremented each time a new task is created. Decremented when a task is ends.
     */
    private var taskIdCounter : Long = 0
    private val runningTasks = mutableListOf<Task>() //List of all running tasks.
    private val fileLocks = mutableMapOf<String, Mutex>() //Map of resource locks such as files.

//=============================================== Internal Mutex =====================================================//

    //Required for reads and writes to the runningTasks list.
    private val runningTasksMutex = Mutex()
    private val resourceLockMutex = Mutex() //Required for reads and writes to the fileLocks map.

//=============================================== Functions ==========================================================//

    fun getTaskIdCounter() : Long
    {
        val result = taskIdCounter
        return result
    }

    /**
     * Retrieves a lock for a given resource. If the resource doesn't exist, creates a
     * lock for it. If the resource does exist, returns the existing lock.
     *
     * @param resource the resource to lock
     * @return the lock for the given resource
     */
    suspend fun getResourceLock(resource : String) : Mutex
    {

        resourceLockMutex.withLock {

            //Create if it doesn't exist
            if (!fileLocks.containsKey(resource))
            {
                val fileMutex = Mutex()
                fileLocks[resource] = fileMutex
                return fileMutex
            }

            //Return if it does
            if(fileLocks[resource] != null)
            {
                return fileLocks[resource]!!
            }

            //Create value if the map value is null
            else
            {
                val fileMutex = Mutex()
                fileLocks[resource] = fileMutex
                return fileMutex
            }
        }
    }


    /**
     * Returns a list of all running tasks. This is used by taskGraph to determine what tasks to sweep,
     * provide information to the admin and users, and to allow users and the admin to cancel tasks.
     *
     * @return A list of all running tasks
     */
    suspend fun getTasks() : List<Task>
    {
        var tasks = mutableListOf<Task>()

        runningTasksMutex.withLock {
            tasks = runningTasks
        }

        return tasks
    }

    /**
     * Finds a task by its ID.
     *
     * @param taskId the task ID to search for
     * @return the task with the given ID if it exists, null otherwise
     */
    suspend fun getTaskById(taskId : Long) : Task?
    {
        var task : Task?

        runningTasksMutex.withLock {
            task = runningTasks.find { it.getTaskIdRef() == taskId }
        }

        return task
    }


    /**
     * Construct a new task object based on defined task settings and class, then start the task and
     * add it to the list of running tasks.
     *
     * @param taskSettings the settings for the task
     * @param dispatcher the dispatcher to be used for the task, defaults to [Dispatchers.Default]
     * @param taskClass the class of the task
     *
     * @return Returns the task id for later retrieval in the event the task will take longer than 1 second
     * to process and return.
     */
    suspend fun createTask(taskSettings : TaskSettings, dispatcher : CoroutineDispatcher, taskClass : KClass<Task>) : Long
    {
        taskIdCounter++
        val newTask = Task.create(taskSettings, dispatcher, taskClass)

        runningTasksMutex.withLock{
            runningTasks.add(newTask)
        }

        return newTask.getTaskIdRef()
    }


    /**
     * Ends a task with the given ID. This marks the task as completed (with a status of [TaskStatus.Complete]) and
     * sets the task as garbage to be collected by taskGraph. If the task is not found, does nothing.
     *
     * @param taskId the ID of the task to end
     * @param markGarbage whether the task should be marked as garbage to be collected by taskGraph
     */
    suspend fun endTask(taskId : Long, markGarbage : Boolean)
    {
        val task = getTaskById(taskId) ?: return
        task.endTask(true, markGarbage)
    }


    /**
     * Ends the specified task, marking it as completed and optionally marking it as garbage.
     *
     * @param task The task to be ended.
     * @param markGarbage True if the task should be marked as garbage to be collected by taskGraph, false otherwise.
     */
    suspend fun endTask(task : Task, markGarbage : Boolean)
    {
        task.endTask(true, markGarbage)
    }

    /**
     * Marks a task as garbage directly. This is useful when a task is completed and needs to be addressed by another
     * request or process. In such cases, the task can't be removed by taskGraph until the request or process is
     * has collecteed the results of the task held in it's object. Once done the task should then be marked as
     * garbage manually.
     */
    suspend fun markTaskAsGarbage(task : Task)
    {
        task.endTask(true, true)
    }


    suspend fun destroyTask(task : Task)
    {
        runningTasksMutex.withLock {
            task.getRunningJob()?.cancel()
            runningTasks.remove(task)

        }
    }


    /**
     * Retrieves the result from a task using the task ID from the provided settings.
     *
     * @param settings The task settings containing the task ID to retrieve results from
     * @return The result string from the task, or empty string if task is not found
     */
    suspend fun getResultFromTask(settings: TaskSettings) : String
    {
        resourceLockMutex.withLock {
            val task = getTaskById(settings.taskId)
            return task?.getResult() ?: ""
        }
    }



}