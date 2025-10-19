package Tasks.TaskManager

import Tasks.Enums.TaskStatus
import Tasks.Structs.TaskSettings
import io.ktor.util.reflect.instanceOf
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.jvm.java
import kotlin.reflect.KClass
import kotlin.reflect.jvm.isAccessible


/**
 * An open class representing a task that can be executed concurrently. Tasks are intended to provide visibility
 * and control over the execution of long-running tasks in a concurrent environment to the admin and users. The settings
 * and state of the task will be made visible to taskGraph to determine what tasks to sweep, provide information to
 * the admin and users, and to allow users and the admin to cancel tasks.
 * @see taskGraph
 *
 *
 * Tasks should be created and managed using taskManager instead of invoking this class or it's
 * companion objects directly.
 * @see taskManager
 */
open class Task
{

//=============================================== Properties =========================================================//
    protected var taskId : Long = -1
    private var taskSettings: TaskSettings = TaskSettings()
    private var dispatcher = Dispatchers.Default
    private var scope = CoroutineScope(Dispatchers.Default)
    protected var runningJob : Job? = null
    protected var requestedResources = mutableListOf<String>() //List of resources used by the task.
    protected var taskResult = "" //Result of the long running task stored as json.
    var isGarbage = false //Weather the task can be swept by taskGraph or not.


//=============================================== Mutex ==============================================================//

    val taskMutex = Mutex() //Locks reads and writes to the TaskSettings object.
    val jobMutex = Mutex() //Locks reads and writes to the runningJob object.
    val resourceMutex = Mutex() //Locks reads and writes to the requestedResources list.
    val resultMutex = Mutex() //Locks reads and writes to the result string.

//=========================================== Getter and Setters =====================================================//

    fun getTaskIdRef() : Long
    {
        return taskId
    }

    suspend fun getTaskSettings() : TaskSettings
    {
        taskMutex.withLock {
            return taskSettings.copy()
        }

    }

    suspend fun getRunningJob() : Job?
    {
        var job : Job?

        jobMutex.withLock {
            job = runningJob
        }

        return job
    }

    suspend fun getResources() : List<String>
    {
        resourceMutex.withLock {
            return requestedResources
        }

    }

    suspend fun getResult() : String
    {
        resultMutex.withLock {
            return taskResult
        }

    }

//=========================================== Functions ==============================================================//

    /**
     * Initializes the task with the given [taskSettings], [dispatcher], and [scope].
     * @param taskSettings the settings for the task
     * @param dispatcher the coroutine dispatcher to use for the task. Defaults to [Dispatchers.Default].
     * @param scope the coroutine scope to use for the task. Defaults to a new scope with [Dispatchers.Default].
     */
   private fun init(taskSettings: TaskSettings, dispatcher: CoroutineDispatcher = Dispatchers.Default)
    {
        this.taskSettings = taskSettings
        this.dispatcher = dispatcher
        this.scope = CoroutineScope(this.dispatcher)
        taskId = taskManager.getTaskIdCounter()
        taskSettings.setStatus(TaskStatus.Running)
        taskSettings.taskId = taskId

        runningJob = scope.launch {
            runTask(taskSettings.params)
        }

        //Await the end of the task.
       val newScope = CoroutineScope(Dispatchers.Default).launch {
            runningJob?.join()

           if(taskSettings.getStatus() != TaskStatus.Complete || taskSettings.getStatus() != TaskStatus.Failed)
           {
               markTaskHung() //Mark hung if it has not responded with a complete or failed status.
           }

       }
    }

    /**
     * Acquire a resource lock from taskManager. This should be used to acquire locks on any resources such as
     * files, databases, io etc. rather than creating them directly.
     *
     * @param resource the resource to acquire a lock for
     *
     * @return The mutex for the given resource.
     *
     * @see taskManager
     */
    suspend fun getResourceLock(resource : String) : Mutex
    {
        return taskManager.getResourceLock(resource)
    }

    /**
     * Executes the task with the provided parameters.
     *
     * This function is responsible for performing the main logic of the task. It should be overridden
     * by subclasses to implement the specific behavior of the task. The task will be executed within
     * the coroutine context and scope defined by the task settings.
     *
     * @param jsonParams A JSON string containing the parameters needed to run the task.
     *                   The structure and content of the JSON should be defined by the specific task implementation.
     */
    open suspend fun runTask(jsonParams: String)
    {

    }


    /**
     * Ends the task, updating its status and canceling its running job.
     *
     * @param result True if the task completed successfully, false otherwise.
     * @param markGarbage True if the task should be swept by taskGraph, false otherwise.
     */
    suspend fun endTask(result: Boolean, markGarbage: Boolean, resultJson: String = "")
    {
        taskMutex.withLock {
            isGarbage = markGarbage
            taskSettings.setStatus(if (result) TaskStatus.Complete else TaskStatus.Failed)

        }

        jobMutex.withLock {
            runningJob?.cancel()
        }

        resultMutex.withLock {
            taskResult = resultJson
        }

    }


    suspend fun updateTaskStatus(status : TaskStatus, description : String)
    {
        taskMutex.withLock {
            taskSettings.setStatus(status)
            taskSettings.statusDescription = description
        }
    }


    suspend fun markTaskHung()
    {
        taskMutex.withLock {
            taskSettings.setStatus(TaskStatus.Hung)
            isGarbage = true
        }
    }

    
    companion object
    {
        /**
         * Create a new task based on a given [taskSettings], and a specific [dispatcher] and [scope].
         * The task is created with the default constructor, and then initialized with the given
         * [taskSettings], [dispatcher] and [scope].
         *
         * @param taskSettings the settings for the task
         * @param dispatcher the dispatcher to be used for the task, defaults to [Dispatchers.Default]
         * @return a new instance of T, initialized with the given parameters
         */
        fun <T : Task> create(
            taskSettings: TaskSettings,
            dispatcher: CoroutineDispatcher = Dispatchers.Default,
            clazz: KClass<T>
        ): T {
            // ensure we can call even a private constructor
            val ctor = clazz.constructors.first { it.parameters.isEmpty() }.apply {
                isAccessible = true
            }
            val instance = ctor.call()
            instance.init(taskSettings, dispatcher)
            return instance
        }

    }
}