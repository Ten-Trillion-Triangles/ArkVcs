package com.example

import Structs.UserSettings
import java.util.concurrent.ConcurrentHashMap

/**
 * The canonical signature for all ArkVcs RPC functions.
 * - json: The serialized JSON arguments for the function
 * - user: The authenticated user making the request
 * - return: A JSON string with the function's result
 */
typealias ArkRpcFunction = suspend (json: String, user: UserSettings) -> String

/**
 * Wrapper holding a function's name alongside its actual callable.
 * The name is what crosses the wire; the callable stays in the registry.
 */
data class ArkFunctionSignature(
    val name: String,
    val function: ArkRpcFunction
)

/**
 * Thread-safe singleton registry for all ArkVcs RPC functions.
 * Provides name-based dispatch instead of closure serialization.
 *
 * Mirrors the PCP FunctionRegistry pattern: functions are registered by name at
 * startup, and calls are dispatched by looking up the name in the registry.
 * The function reference itself never crosses the serialization boundary.
 *
 * @see ArkRpcRequest
 * @see ApiRoutes
 */
object ArkFunctionRegistry
{

//======================================== Registry Core =================================================//

    private val functions = ConcurrentHashMap<String, ArkFunctionSignature>()

    /**
     * Register an RPC function under a given name.
     * The name must be unique — registering a second function under the same name replaces the first.
     *
     * @param name The string name that clients use to invoke this function
     * @param fn The actual suspend function to invoke
     */
    fun register(name: String, fn: ArkRpcFunction)
    {
        functions[name] = ArkFunctionSignature(name, fn)
    }

    /**
     * Look up a registered function by name.
     *
     * @param name The function name
     * @return The signature wrapper, or null if not found
     */
    fun get(name: String): ArkFunctionSignature?
    {
        return functions[name]
    }

    /**
     * Returns all registered function names.
     * Useful for introspection, debugging, and generating API documentation.
     */
    fun getNames(): Set<String>
    {
        return functions.keys.toSet()
    }

    /**
     * Remove all registered functions.
     * Useful for testing and hot-reload scenarios.
     */
    fun clear()
    {
        functions.clear()
    }

    /**
     * Returns the number of registered functions.
     */
    fun size(): Int = functions.size
}
