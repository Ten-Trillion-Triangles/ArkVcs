package Structs.Api

import kotlinx.serialization.Serializable

/**
 * Serializable RPC request replacing the broken Request class.
 *
 * The old Request carried a closure field that cannot be serialized:
 *   var function: (suspend (json: String, user: UserSettings) -> String)?
 *
 * ArkRpcRequest solves this by replacing the function reference with a string name.
 * The name is used to look up the actual function in ArkFunctionRegistry server-side.
 * Only the name crosses the wire — the callable stays in the registry.
 *
 * @param functionName The registry name of the function to invoke
 * @param args Serialized JSON arguments for the function
 *
 * @see com.example.ArkFunctionRegistry
 */
@Serializable
data class ArkRpcRequest(
    val functionName: String,
    val args: String
)
