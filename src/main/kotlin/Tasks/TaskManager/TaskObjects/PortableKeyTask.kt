package Tasks.TaskManager.TaskObjects

import Enums.LogLevel
import Enums.Permissions
import Global.serverEnv
import KeyStore.keyStore
import Log.arkLog
import Structs.Api.LogResponse
import Structs.Api.StringResponse
import Structs.UserSettings
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import Tasks.Structs.TaskSettings
import Tasks.TaskManager.Task
import Util.deserialize
import Util.serialize
import kotlinx.coroutines.sync.withLock

/**
 * Task responsible for generating a portable key. Assumes that prior security validation checks have passed to reach
 * this point. Given a username that exists on Ark, a new portable key with a one time use will be generated allowing
 * for another unregistered device to create a locked key for itself.
 */
class PortableKeyTask : Task()
{
    override suspend fun runTask(jsonParams: String)
    {
        val userSettings = deserialize<UserSettings>(jsonParams) ?: UserSettings()

        /**We can't proceed unless there's an actual username provided.
         * We're assuming that the security system has already validated this data before reaching this point
         * so this message should never actually be invoked in production code unless some other issue or bug
         * has allowed us to reach this broken stage.
         */
        if(userSettings.username.isEmpty())
        {
            arkLog(TaskCategory.Security, "Failed to write portable key for user because no valid user name was provided",
                LogLevel.Error)

            val clientLogResult = LogResponse()
            clientLogResult.error = true
            clientLogResult.message = """User params were empty when passed into PortableKeyTask::runTask().
                |Without a valid username to verify no key can be created. Please report this bug to the developers.
            """.trimMargin()
            val json = serialize(clientLogResult)

            endTask(false, false, json)
            return
        }

        val registeredUsers = serverEnv.get().getUserManifest().registeredUsers
        val bannedUsers = serverEnv.get().getUserManifest().deactivatedUsers
        val username = userSettings.username

        //Deny all portable keys owned by a deactivated user account.
        if(bannedUsers.contains(username))
        {
            arkLog(TaskCategory.Security, "$username has been deactivated. " +
                    "Rejecting portable key.", LogLevel.Error)
            endTask(true, true)
            return
        }


        /**
         * Find the user that matches the requested key. If found, issue the portable key.
         */
        val keys = registeredUsers.keys
        if(keys.contains(username))
        {
            keyStore.keyStoreMutex.withLock {
                val newPortableKey = keyStore.issuePortableKey(username)

                /**
                 * End task and record error if we failed to encrypt the key.
                 */
                if(newPortableKey.isEmpty())
                {
                    arkLog(TaskCategory.Security, "Failed to encrypt the portable key for" +
                            " user: ${username}: Please investigate keyStore::issuePortableKey() " +
                            "for more details.", LogLevel.Error)

                    val clientLogResult = LogResponse()
                    clientLogResult.error = true
                    clientLogResult.message = "Failed to encrypt portable key during key issuance."

                    endTask(false, false)
                    return
                }

                val encryptedKeyResult = StringResponse(newPortableKey)
                arkLog(TaskCategory.Security, "portable key: $newPortableKey has been created" +
                        " for user: $username", LogLevel.Warn)

                endTask(true, false) //End task but keep object for key retrieval later.
            }
        }


        arkLog(TaskCategory.Security, "$username is not a registered user on Ark. " +
                "Rejecting portable key.", LogLevel.Error)


    }

    /**
     * Companion object to help construct the settings and parameters for a PortableKeyTask object.
     * @param user UserSettings required for the creation of a portable key.
     */
    companion object
    {
        fun createTaskSettings(user: UserSettings) : TaskSettings
        {
            val settings = TaskSettings()
            settings.action = TaskAction.Write
            settings.category = TaskCategory.Security
            settings.params = serialize(user)
            settings.owner = user.username
            settings.permissions = Permissions.Admin
            return settings
        }
    }
}