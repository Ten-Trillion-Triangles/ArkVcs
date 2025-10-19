package Structs.Api

import Tasks.Structs.TaskSettings
import io.ktor.utils.io.KtorDsl

/**
 * Data class that holds information for the client regarding a long-running task that exceeds
 * the scope of holding a rest api connection open. Contains the task settings to help the client
 * check up and request status of the job at given intervals.
 */
@kotlinx.serialization.Serializable
data class ArkJob(val cinit: Boolean = false)
{
    /**
     * Information on the running task.
     */
    var taskInfo = TaskSettings()

    /**
     * Expected interval the client should wait on each attempt to request a result from
     * the server.
     */
    var checkInTime : ULong = 1000u
}
