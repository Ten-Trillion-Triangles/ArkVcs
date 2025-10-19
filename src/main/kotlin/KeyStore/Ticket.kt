package KeyStore

import java.util.UUID

/**
 * Denotes a link between a file path and a uuid that validates that exact file path my have a file system
 * operation be applied to it as a one time action.
 */
@kotlinx.serialization.Serializable
data class FileTicket(val cinit: Boolean = true)
{
    /**
     * Direct path to where the given file is actually located on the Ark database.
     * This differs from a virtual file path which replicates the structure of the
     * project when held on a user's system.
     *
     * The reason for this is that we must append metadata to help identify a file
     * by its version number and other identifiers that Ark uses to associate files
     * with changelists, and revisions.
     *
     * @see Structs.VirtualFileSystem
     * @see Structs.ChangelistManifest
     * @see Structs.VersionManifest
     */
    var filepath = ""

    /**
     * UUID converted to a string. Each ticket has a uuid we can use to authorize
     * a one time file action request being sent by a client.
     */
    var ticketId = ""

    override fun equals(other: Any?): Boolean {
        if (other == null) return false
        if (other !is FileTicket) return false
        return this.cinit == other.cinit && this.filepath == other.filepath && this.ticketId == other.ticketId
    }

    override fun hashCode(): Int {
        var result = cinit.hashCode()
        result = 31 * result + filepath.hashCode()
        result = 31 * result + ticketId.hashCode()
        return result
    }
}


/**
 * Singleton that tracks which tickets have been issued, and which tickets have been discarded.
 */
object ticketManager
{
    private var tickets = mutableMapOf<String, MutableList<FileTicket>>()
    private var discardedTickets = mutableListOf<String>()


    /**
     * Issue new ticket for allowing a file action to occur. This is a required security measure that must be checked
     * because repo and global permissions are insufficient to determine if a file operation was authorized.
     * And if an operation was not explicit authorized by a repo read, write, or delete request then
     * the Ark file system can end up damaged by an unexpected and unauthorized action.
     *
     * @param path The true file path on the ark server that our target file is located at. This would be typically
     * provided by ark after resolving a repo request, the file version expected, and the changelist in which it's
     * stored at. Normally it is not possible to directly interact with the real file's location as Ark uses
     * a virtual file system to map the files the user should have on their system to the version ark has which
     * is both renamed by appending changelist, and version information, and is also located in on a path that
     * would be intended to be looked up, rather than matching the original path the files would be when
     * returned to the user as a fully functioning codebase or project.
     */
    fun issueTicket(path: String) : Boolean
    {
        /**
         * All tickets have an uuid number that is used to authorize an action on this file once, and only once.
         * So we need to generate a new uuid to ensure that we have a unique blessing by the server to
         * take the given file action.
         */
        val uuid = UUID.randomUUID().toString()
        val newTicket = FileTicket()
        newTicket.filepath = path
        newTicket.ticketId = uuid

        //Don't proceed if the uuid has been discarded as this is likely malicious traffic or a bug of some kind.
        if(discardedTickets.contains(uuid))
        {
            return false
        }

        val existingTickets = tickets[path] ?: mutableListOf<FileTicket>()
        existingTickets.add(newTicket)
        tickets[path] = existingTickets
        return true
    }


    /**
     * Validates a ticket for a specific file path and ticket ID
     * @param filePath The file path to validate
     * @param ticketId The ticket ID to validate
     * @return FileTicket if valid, null if invalid
     */
    fun validateTicket(filePath: String, ticketId: String): FileTicket? {
        val existingTickets = tickets[filePath] ?: return null
        return existingTickets.find { it.ticketId == ticketId && it.filepath == filePath }
    }
    
    /**
     * discard a ticket that's been used to perform a file operation. This ensures that any additional requests
     * against that file will no longer pass with the old ticket. This is a security measure to ensure that
     * the file operation is only performed once.
     *
     * @param ticket The ticket to be discarded.
     */
    fun discardTicket(ticket: FileTicket) : Boolean
    {
        val uuid = ticket.ticketId
        val existingTickets = tickets[ticket.filepath] ?: mutableListOf<FileTicket>()
        existingTickets.remove(ticket)
        tickets[ticket.filepath] = existingTickets
        discardedTickets.add(uuid)
        return true
    }

}

