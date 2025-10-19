package Global

import KeyStore.KeyStoreObj
import KeyStore.keyStore
import Structs.AuthSettings
import Structs.ConnectionSettings
import Structs.GlobalManifest
import Structs.GlobalSettings
import Structs.UserManifest
import Structs.UserSettings
import Util.deserialize
import Util.getHomeFolder
import Util.serialize
import java.io.File


/**
 * Data object to store client config. As well as load and save it to disk.
 */
@kotlinx.serialization.Serializable
data class ClientConfig(@kotlinx.serialization.Transient val cinit: Boolean = false)
{
//============================================== Properties ==========================================================//
    private var args = mutableListOf<String>() //Program arguments.
    private val configDir = "${getHomeFolder()}/.Ark" //Default config directory.
    private var userSettings = UserSettings() //User settings loaded from config file.
    private var connectionSettings = ConnectionSettings() //Connection settings loaded from config file.



//============================================== Functions ==========================================================//
    /**
     * Gets the program arguments as a list of strings.
     *
     * @return A new copy of the list of program arguments.
     */
    fun getArgs() : List<String>
    {
        val argsCopy = args
        return argsCopy
    }


    /**
     * Gets the config directory.
     *
     * @return The config directory.
     */
    fun getConfigDir() : String
    {
        return configDir
    }

    /**
     * Returns a read only copy of the user settings.
     */
    fun getUserSettings() : UserSettings
    {
        return userSettings.copy()
    }


    fun setArgs(args : List<String>)
    {
        this.args = args.toMutableList()
    }
}


/**
 * Data object to store server config. As well as load and save it to disk.
 */
@kotlinx.serialization.Serializable
data class ServerConfig(@kotlinx.serialization.Transient val cinit: Boolean = false)
{
    private var globalSettings = GlobalSettings() //General server settings.
    private var globalManifest = GlobalManifest() //Mapping of all repo's on Ark.
    private var userManifest = UserManifest() //All users registered to Ark.
    private var authSettings = AuthSettings() //Authentication and encryption settings.
    private var databaseDir = "${getHomeFolder()}/Ark-D" //Location of the Ark database. Can be reconfigured by the server admin.
    private var runningAsServer = false
    private var keyStore = KeyStoreObj() //Keystore saved to disk. Will be loaded to keyStore singleton.

//============================================== Functions ==========================================================//

    fun getDatabaseDir() : String
    {
        val readOnlyCopy = databaseDir
        return readOnlyCopy
    }

    fun getGlobalSettings() : GlobalSettings
    {
        return globalSettings.copy()
    }

    fun getAuthSettings() : AuthSettings
    {
        return authSettings.copy()
    }

    fun getKeyStore() : KeyStoreObj?
    {
        return keyStore
    }

    fun getGlobalManifest() : GlobalManifest
    {
        return globalManifest
    }

    fun getUserManifest() : UserManifest
    {
        return userManifest
    }

    fun isServer() : Boolean
    {
        return runningAsServer
    }
}




/**
 * Client side environment object. Houses global variables relevant to the client and
 * users of the service.
 */
object env
{
   private var data = ClientConfig()

    fun load()
    {
        data = try{
            deserialize(File("${data.getConfigDir()}/config.json"))
        }catch(e: Exception) {
            ClientConfig()
        }
    }

    fun save()
    {
        try{
            File("${data.getConfigDir()}/config.json").writeText(serialize(data))
        }catch(e: Exception) {
            println("Failed to save config: ${e.message}")
        }
    }

    fun get() : ClientConfig
    {
        return data.copy()
    }

    fun set(config: ClientConfig)
    {
        data = config
        save()
    }

    fun setArgs(args: List<String>)
    {
        data.setArgs(args)
    }
}

/**
 * Server side environment object. Houses server settings and configurations relevant
 * to the server, and system administrators.
 */
object serverEnv
{
    private var data = ServerConfig()

    fun load()
    {
        data = try{
            deserialize(File("${data.getDatabaseDir()}/Server-config.json"))
        }catch(e: Exception) {
            ServerConfig()
        }
    }

    fun save()
    {
        try{
            File("${data.getDatabaseDir()}/Server-config.json").writeText(serialize(data))
        }catch(e: Exception) {
            println("Failed to save config: ${e.message}")
        }
    }

    fun get() : ServerConfig
    {
        return data.copy()
    }

    fun set(config: ServerConfig)
    {
        data = config
        save()
    }
}


data class ArgumentParser(val cinit: Boolean = false)
{
    var boolFlags = mutableListOf<String>()    // Arguments starting with '-'
    var valueFlags = mutableMapOf<String, String>() // Key-value pairs from '=' arguments
    var args = mutableListOf<String>()         // Remaining non-flag arguments

    companion object {
        /**
         * Creates and returns an ArgumentParser with arguments from the env object.
         * 
         * @return ArgumentParser instance with parsed arguments from env
         */
        fun fromEnv(): ArgumentParser
        {
            val parser = ArgumentParser()
            parser.parse(env.get().getArgs())
            return parser
        }
    }

    /**
     * Parses command line arguments into bool flags, value flags, and remaining arguments.
     * 
     * @param arguments List of command line arguments to parse
     */
    fun parse(arguments: List<String>)
    {
        val argList = arguments.toMutableList() // Create mutable copy for processing
        var i = 0
        
        while (i < argList.size) {
            val arg = argList[i]
            
            when {
                // Bool flags: arguments starting with '-'
                arg.startsWith("-") -> {
                    boolFlags.add(arg)
                    argList.removeAt(i) // Remove processed flag
                }
                // Value flags: arguments containing '='
                arg.contains("=") -> {
                    val parts = arg.split("=", limit = 2) // Split into key and value
                    valueFlags[parts[0]] = parts[1]
                    argList.removeAt(i) // Remove processed flag
                }
                // Handle spaced '=' (key = value)
                arg == "=" && i > 0 && i < argList.size - 1 -> {
                    valueFlags[argList[i-1]] = argList[i+1] // Map key to value
                    argList.removeAt(i+1) // Remove value
                    argList.removeAt(i)   // Remove '='
                    argList.removeAt(i-1) // Remove key
                    i-- // Adjust index after removals
                }
                else -> i++ // Skip non-flag arguments
            }
        }
        
        // Add remaining arguments to args list
        args.addAll(argList)
    }
}