package Enums

import Structs.Api.ArkJob
import Structs.Api.FileResponse
import Structs.Api.LogResponse
import Structs.Api.RepoInfoResponse
import Structs.Api.StringResponse
import Structs.Api.VoidParamResponse
import Structs.Api.VoidResponse
import Util.deserialize
import kotlin.reflect.KClass

enum class ResponseType
{
    Void,
    VoidParam,
    File,
    Json,
    Log
}

fun <T> getResponseType(value : T) : ResponseType
{
    return when (value) {
        is Structs.Api.VoidResponse -> ResponseType.Void
        is Structs.Api.VoidParamResponse -> ResponseType.VoidParam
        is Structs.Api.FileResponse -> ResponseType.File
        is Structs.Api.LogResponse -> ResponseType.Log
        else -> ResponseType.Json
    }
}


fun <T> makeResponseStruct(type : ResponseType) : T
{
    return when (type) {
        ResponseType.Void -> Structs.Api.VoidResponse(cinit = true)
        ResponseType.VoidParam -> Structs.Api.VoidParamResponse(cinit = true)
        ResponseType.File -> Structs.Api.FileResponse()
        ResponseType.Log -> Structs.Api.LogResponse()
        ResponseType.Json -> Structs.Api.StringResponse("")
    } as T
}


/**
 * Attempts to deserialize JSON into one of the known response types and returns the result with its class type.
 * Tries each response type in sequence until one successfully deserializes.
 *
 * @param T The expected return type for the deserialized response
 * @param json The JSON string to deserialize
 * @return A Pair containing the deserialized response object and its KClass type
 * @throws IllegalArgumentException if the JSON cannot be deserialized to any known response type
 */
fun <T> getResponseStruct(json: String) : Pair<T, KClass<out Any>>
{

    /**
     * Deserialize in the order of what most calls are likely to be. Ideally, this should at least reduce
     * the cost of having to do this in multiple tries.
     */
    val voidParam = deserialize<VoidParamResponse>(json)
    val void = deserialize<VoidResponse>(json)
    val log = deserialize<LogResponse>(json)
    val file = deserialize<FileResponse>(json)
    val repo = deserialize<RepoInfoResponse>(json)
    val string = deserialize<StringResponse>(json)
    val job = deserialize<ArkJob>(json)

    val responses = listOf<Any?>(void, voidParam, file, repo, log, string, job)

    for(response in responses)
    {
        if(response != null)
        {
            return Pair(response as T, response::class)
        }
    }

    throw IllegalArgumentException("Unable to deserialize JSON to any known response type")
}
