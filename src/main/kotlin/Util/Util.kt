package Util

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException


/**
 * Returns the user's home folder. Or the document folder on Windows.
 * This function expects that standard naming conventions are used.
 * Bizzare non-standard naming conventions are not supported, and it's the user's fault
 * if they do not comply.
 */
fun getHomeFolder(): File {
    val os = System.getProperty("os.name")
    return if (os.contains("Windows")) {
        File(System.getenv("USERPROFILE"))
    } else {
        File(System.getProperty("user.home"))
    }
}


/**
 * Copy file in one directory to another. Only unix is supported.
 * @param starPath The path of the file to copy.
 * @param destPath The path to copy the file to.
 */
fun copyFile(starPath : String, destPath : String) {

    try {
        val targetFile = File(destPath)
        val sourceFile = File(starPath)
        sourceFile.copyTo(targetFile, overwrite = false)
    } catch (e: NoSuchFileException) {
        println("Error: Source file not found: ${e.message}")
    } catch (e: FileAlreadyExistsException) {
        println("Error: Destination file already exists: ${e.message}")
    } catch (e: FileSystemException) {
        println("Error: Failed to create target directory: ${e.message}")
    } catch (e: IOException) {
        println("Error: I/O error occurred: ${e.message}")
    } catch (e: Exception) {
        println("Unexpected error: ${e.message}")
    }

}



/**
* Copy directory in one directory to another. Only unix is supported.
* @param starPath The path of the directory to copy.
* @param destPath The path to copy the directory to.
*/
fun copyDir(starPath : String, destPath : String) {
    //File(starPath).copyRecursively(File(destPath), true)

    File(starPath).copyRecursively(
        File(destPath),
        overwrite = false,
        onError = { file, exception ->
            when (exception) {
                is AccessDeniedException -> {
                    println("Error copying $file: Permission denied")
                    OnErrorAction.SKIP
                }
                is IOException -> {
                    println("Error copying $file: I/O error: ${exception.message}")
                    OnErrorAction.SKIP // Or consider retrying with a delay
                }
                is SecurityException -> {
                    println("Error copying $file: Security violation: ${exception.message}")
                    OnErrorAction.SKIP // Or log and skip, depending on the severity
                }
                else -> {
                    println("Unexpected error copying $file: ${exception.message}")
                    OnErrorAction.SKIP // Log the error and terminate to prevent further issues
                }
            }
        }
    )
}


/**
 * Execute a bash command.
 * @param command The command to execute. Does not pipe buffer output. Will stall the thread until
 * the command has finished.
 * @return The exit value of the command.
 */
fun executeBashCommand(command : String) : Int
{
    val process = ProcessBuilder(*command.split("\\s+".toRegex()).toTypedArray())
        .inheritIO()
        .start()
    process.waitFor()
    return process.exitValue()
}


/**
 * Find a file cascading up the directory tree. This is required because we don't know where the program's working dir
 * is compared to where our target file might be up above. This can even vary from running as jar, or as gradlew, or even
 * as a docker container.
 * @param path The path to start from.
 * @return The file found.
 */
fun findFileCascading(path : String) : File
{
    var mutablePath = path
    val maxIterations = 10
    var iterations = 0

    while (!File(mutablePath).exists())
    {
        mutablePath = "../$mutablePath"

        if(iterations > maxIterations)
        {
            return File("") //Return empty if we exceed the max limit.
        }

        iterations++
    }

    return File(mutablePath)
}


//Get the program's working directory
fun getWorkingDirectory() : String
{
    return File(".").absolutePath
}



/**
 * Write a string to a file with a Unix filepath.
 *
 * @param filepath The Unix filepath to write to.
 * @param content The string to write to the file.
 */
fun writeStringToFile(filepath: String, content: String) {
    File(filepath).writeText(content)
}

/**
 * Append a string to a file with a Unix filepath.
 *
 * @param filepath The Unix filepath to append to.
 * @param content The string to append to the file.
 */
fun appendStringToFile(filepath: String, content: String) {
    File(filepath).appendText(content)
}

/**
 * Read a string from a file with a Unix filepath.
 *
 * @param filepath The Unix filepath to read from.
 *
 * @return The string read from the file.
 */
fun readStringFromFile(filepath: String): String {
    return File(filepath).readText()
}


/**
 * Serialize any data class to a json file.
 * @param obj The object to serialize.
 * @param filePath The path to the file to write to.
 */
inline fun <reified T> serialize(obj : T, filePath : File)
{
    val json = Json.encodeToString(obj)
    writeStringToFile(filePath.absolutePath, json)
}


/**
 * Serializes an object into a json string given the loosest restrictions possible. Will attempt to
 * serialize all that it can and never throw an exception. This mechanism is required because we don't
 * know what languge has serialized the object, and we can't trust an AI model to obey the rules of
 * serialization. This is especially true for any model or api that doesn't have support for forced json
 * structuring. In this case TPipe can force it to support json as a return by prompt engineering, but we need
 * to handle unexpected output, missing values, or other nonsense as best we can.
 *
 * @param obj The object to serialize. The object must be serializable by kotlinx serialization. We'll attempt to
 * handle passing invalid objects returning an empty string as often as possible.
 *
 * @return A json string representation of the object. Will return an empty string if the object cannot be
 * serialized.
 */
@kotlinx.serialization.ExperimentalSerializationApi
inline fun <reified T> serialize(obj: T, encodedefault : Boolean = true): String
{
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = encodedefault
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        allowComments = true
        useArrayPolymorphism = true
        decodeEnumsCaseInsensitive = true
        useAlternativeNames = true
    }

    return try {
        json.encodeToString(obj)
    }
    catch (e: Exception)
    {
        ""
    }

    return ""
}


/**
 * Decode a json file into a data class.
 * @param filePath The path to the file to read from.
 * @return The decoded data class. May require casting afterward.
 */
inline fun <reified T> deserialize(filePath : File)  : T
{
    return Json.decodeFromString<T>(readStringFromFile(filePath.absolutePath))
}

/**
 * Deserializes a json string into an object of type T.
 *
 * This function is intentionally lenient in order to handle the fact that an AI model may not
 * always return proper json. We can't trust an AI model to return good json, so we have to be
 * prepared to handle unexpected input, missing values, or other nonsense as best we can.
 *
 * @param jsonString The json string to deserialize.
 *
 * @return A deserialized object of type T, or null if the string cannot be deserialized.
 */
inline fun <reified T> deserialize(jsonString: String): T?
{
    val json = Json {
        prettyPrint = true
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = true
        explicitNulls = false
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
        allowStructuredMapKeys = true
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class) allowComments = true
        useArrayPolymorphism = true
        @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class) decodeEnumsCaseInsensitive = true
        useAlternativeNames = true
    }

    return try {
        json.decodeFromString(jsonString)
    }
    catch (e: Exception)
    {
        return null
    }
}

