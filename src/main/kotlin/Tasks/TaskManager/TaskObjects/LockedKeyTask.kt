package Tasks.TaskManager.TaskObjects

import Enums.LogLevel
import Enums.Permissions
import Global.serverEnv
import KeyStore.keyStore
import Log.arkLog
import Structs.Api.StringResponse
import Structs.PortableAuthKey
import Structs.StringPair
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import Tasks.Structs.TaskSettings
import Tasks.TaskManager.Task
import Util.decryptString
import Util.deserialize
import Util.serialize
import kotlinx.coroutines.sync.withLock

/**
 * Task responsible for issuing a locked key from a portable key. A string pair containing the portable key and the
 * hwid of the device that the key will be locked to will be provided. The portable key will be decrypted, discarded,
 * and the locked key will be issued.
 */
class LockedKeyTask : Task()
{
    override suspend fun runTask(jsonParams: String)
    {
        var portableKey : PortableAuthKey? //The portable key to issue a locked key from.

        //Break apart the string pair to reveal the encrypted portable key and hwid.
        val stringPair = deserialize<StringPair>(jsonParams) ?: StringPair("", "")

        //Attempt to decrypt the provided portable key.
        val portableKeyJson = decryptString(stringPair.stringA, serverEnv.get().getAuthSettings().masterUserKey)


        if(portableKeyJson == "")
        {
            arkLog(TaskCategory.Security, "Unable to decrypt the provided portable key", LogLevel.Error)
            endTask(false, true)
            return
        }

        //Attempt to deserialize the decrypted portable key. Now we can finally proceed to issue the locked key.
        portableKey = deserialize<PortableAuthKey>(portableKeyJson)

        if(portableKey == null)
        {
            arkLog(TaskCategory.Security, "The provided portable key is not valid.", LogLevel.Error)
            endTask(false, true)
            return
        }

        /**
         * For security reasons, we shouldn't log the exact reason the portable key was rejected.
         * Being discarded, or being null is both the same failure that forces us to end the task so logging
         * which it is could be a security issue of the log file is ever exposed or stolen.
         */
        if(keyStore.isDiscarded(portableKey.uniqueKeyId) || keyStore.findPortableKey(portableKey.uniqueKeyId) == "")
        {
            arkLog(TaskCategory.Security, "The provided portable key is not valid.", LogLevel.Error)
            endTask(false, true)
            return
        }

        /**
         * Because the hwid isn't part of the portable key standard, we need to send it in a pair object along with
         * the portable key.
         */
        val hwid = stringPair.stringB

        keyStore.keyStoreMutex.withLock {
            keyStore.discardId(portableKey.uniqueKeyId)

            if(!keyStore.isDiscarded(portableKey.uniqueKeyId))
            {
                arkLog(TaskCategory.Security, "The provided portable key is not valid.", LogLevel.Error)
                endTask(false, true)
                return
            }

            val lockedKey = keyStore.issueLockedKey(portableKey.userId, hwid)
            endTask(true, false)
            arkLog(TaskCategory.Security, "Created a locked key for user ${portableKey.userId}", LogLevel.Warn)
            val lockedKeyResponse = StringResponse(lockedKey)
            taskResult = serialize(lockedKeyResponse)
            return
        }
    }



    companion object
    {
        fun createSettings(params : String) : TaskSettings
        {
            val settings = TaskSettings()
            settings.action = TaskAction.Write
            settings.category = TaskCategory.Security
            settings.owner = "Admin"
            settings.params = params
            settings.permissions = Permissions.Admin
            return settings
        }
    }
}