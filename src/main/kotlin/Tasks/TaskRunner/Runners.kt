package Tasks.TaskRunner

import Enums.Permissions
import Global.serverEnv
import Structs.StringPair
import Structs.UserSettings
import Tasks.TaskManager.Task
import Tasks.TaskManager.TaskObjects.LockedKeyTask
import Tasks.TaskManager.TaskObjects.PortableKeyTask
import Tasks.TaskManager.TaskObjects.FileTransferTask
import Util.requirePermission
import kotlinx.coroutines.Dispatchers


/**
 * The runners file contains static functions that automate the process of building tasks,
 * managing permissions boundaries, and determining weather to wait for the job, or to send the client a
 * ticket to instruct it to check back later for long-running jobs. When creating and running a task,
 * a task runner function should be used here instead of directly invoking the task. Since it ensures that
 * permissions and other prerequisites are correctly handled.
 */

//======================================================================================================================
/**
 * Task to issue a portable key to a user. Automatically handles permissions boundaries, task spawning and management,
 * and execution of the task. Key issuance is not considered a long-running task so the job will fully complete before
 * returning the result vai the rest api.
 *
 * @param json Not used for this function.
 * @param user Required to find the user and get permissions.
 */
suspend fun runPortableKeyTask(json: String, user: UserSettings) : String
{
    val username = user.username
    val permissions = serverEnv.get().getUserManifest().registeredUsers[username]
    val minimumIssuancePermissions = serverEnv.get().getAuthSettings().minimumAccountCreationPermission

    /**Permissions need to be tested against the user that called this. But the user that we're issuing might not
     * be the user that called this so we need to assume that value is passed through the json and use it
     * to look up the target user.
     */
    val targetUser = serverEnv.get().getUserManifest().registeredUsers[json] ?: UserSettings()

    if(permissions == null)
    {
        throw IllegalArgumentException()
    }

    /**
     * If we can't find the user as registered user, we'll default to none level permissions. This will let us
     * end the function by failing the permissions check so we don't need to do any extra steps to directly test
     * and verify the user exists. If they aren't valid, or they do not have the required permission level this
     * check will fail.
     */
    requirePermission(permissions, minimumIssuancePermissions, "Issue portable key")

    val taskSettings = PortableKeyTask.createTaskSettings(targetUser)
    val task = Task.create(taskSettings, Dispatchers.Default, PortableKeyTask::class)
    task.getRunningJob()?.join()

    return task.getResult()
}


/**
 * Task runner to issue a locked key from a portable key. Automatically handles job creation, management,
 * and results collection.
 *
 * @param json stored as a string pair of PortableAuthKey and a string holding the hwid of the device the key
 * belongs to in order to convert to a LockedAuthKey
 * @param user Unused as this is an admin task as far as taskGraph is concerned.
 *
 * @see Structs.PortableAuthKey
 * @see PortableKeyTask
 * @see Structs.LockedAuthKey
 * @see LockedKeyTask
 * @see Tasks.TaskManager.taskGraph
 */
suspend fun runLockedKeyTask(json: String, user: UserSettings) : String
{

    val portableKey = json //Help make the code more readable.

    /**
     * Issuing a locked key is always treated as a task owned by the admin account even if it's a user that's attempting
     * to obtain the locked key. This is because the portable key has already been blessed with the permission of
     * the ark server admin when it is created due to passing the required permission check to even create a key.
     */
    val adminUser = serverEnv.get().getUserManifest().adminAccount
    val taskSettings = LockedKeyTask.createSettings(portableKey)
    val task = Task.create(taskSettings, Dispatchers.Default, LockedKeyTask::class)
    task.getRunningJob()?.join()

    return task.getResult()
}

/**
 * Task runner for file transfer operations. Handles upload, download, and delete operations
 * with proper permissions and task management.
 *
 * @param json FileTransferRequest serialized as JSON
 * @param user User requesting the file operation
 */
suspend fun runFileTransferTask(json: String, user: UserSettings): String {
    val request = Util.deserialize<Structs.Api.FileTransferRequest>(json) ?: throw IllegalArgumentException("Invalid file transfer request")

    //todo: Validate the user has write acsess, validate they can perform whatever repo operation comes prior to this.

    val taskSettings = FileTransferTask.createTaskSettings(request, user)
    val task = Task.create(taskSettings, Dispatchers.IO, FileTransferTask::class)
    task.getRunningJob()?.join()
    
    return task.getResult()
}


