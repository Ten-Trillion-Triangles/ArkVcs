package Log

import Enums.LogLevel
import Global.env
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext


/**
 * Class that handles logging for the Ark server. Stores logs in a buffer, and writes them to a file when the buffer
 * writes them to the file after a designated amount of time clearing the buffer afterward. Buffer is written to using
 * the static function arkLog.
 *
 * @see arkLog
 */
object logManager
{

//=============================================== Properties =========================================================//
    /**
     * Stored list of log messages waiting to be written. Once the log scan time is cleared, the list will be written
     * to the log file in a background thread.
     */
    private val logCache = mutableListOf<String>()

    private val logMutex = Mutex()

    //Coroutine dispatchers to use for log scanning and writing.
    val logScanScope = CoroutineScope(Dispatchers.Default)
    val logWriteScope = CoroutineScope(Dispatchers.IO)

    /**
     * Defines each log level's verbosity level as a number that can be compared to each other.
     * Any value lower than the log level will be ignored.
     */
   private val verbosityMap = mutableMapOf<LogLevel, Int>(LogLevel.Debug to 0, LogLevel.Log to 1, LogLevel.Warn to 2, LogLevel.Error to 3,
        LogLevel.Fatal to 4)

    /**
     * Default verbosity level for logs. This value will be set on the startup of the ArkServer.
     */
    var verbosityLevel = LogLevel.Error


    //Duration to wait between log scans.
    var scanInterval = 2000

//=============================================== Functions =========================================================//

    /**
     * Adds a log message to the buffer if its verbosity level is higher than or equal to the current verbosity level.
     * Regardless of the verbosity level, the log message is printed to the console.
     *
     * @param log The log message to be added.
     * @param level The verbosity level of the log message.
     */
    fun addToBuffer(log : String, level : LogLevel)
    {
        //Ignore logs with a lower verbosity level And do not add to the file buffer.
        if(verbosityMap[level]!! >= verbosityMap[verbosityLevel]!!)
        {
            logCache.add(log)
        }

        //Always print all logs to the console.
        println(log)
    }




    /**
     * Writes all log messages currently in the buffer to the log file.
     * If the buffer is empty, does nothing.
     *
     * If not empty, an io corutine is started to write the buffer to the log file. The parent coroutine is suspended
     * until the io write is complete.
     * @return Nothing.
     */
    suspend fun writeBufferedLogs()
    {
        if(logCache.isEmpty())
        {
            return //Nothing to do since the buffer is empty.
        }

        val job = logWriteScope.launch {
            logMutex.withLock {
                val logDir = "${env.get().getConfigDir()}/logs"
                var bufferAppend =  "" //String to append the buffer array into a single blob.

                for(line in logCache)
                {
                    bufferAppend += "$line\n"
                }

                try{
                    Util.appendStringToFile("${logDir}/Ark-log.txt", bufferAppend)
                }
                catch(e: Exception)
                {
                    return@withLock
                }

                withContext(Dispatchers.Main)
                {
                    logCache.clear()
                }
            }
        }

        job.join() //Wait for the io write to finish before resuming.

    }


    /**
     * Suspends indefinitely and continuously scans the log buffer at a specified interval in the background.
     * When the buffer is not empty, it is written to the log file and cleared.
     */
    suspend fun scanLogBuffer()
    {
        while(true)
        {
            delay(scanInterval.toLong())
            writeBufferedLogs()
        }
    }


    /**
     * Initializes the log manager by starting the log scan coroutine. From this point, global settings cannot be
     * changed.
     */
    fun init()
    {
        logScanScope.launch {
            scanLogBuffer()
        }
    }

}



/**
 * Adds a log message to the log buffer and prints it to the console.
 * The message is prepended with the verbosity level of the message.
 *
 * The log buffer will be written at the specified scan interval which is being checked on a separate thread.
 * Once the scan interval is hit the buffer will be appended, then cleared on the main thread, and all the threads
 * will be resumed.
 *
 * @param message The log message to be logged.
 * @param level The verbosity level of the message. Defaults to [LogLevel.Log].
 */
fun arkLog(system : TaskCategory, message : String, level : LogLevel = LogLevel.Log)
{
    val formattedMessage = "${system.name}: ${level.name}: $message"
    logManager.addToBuffer(formattedMessage, level)
}