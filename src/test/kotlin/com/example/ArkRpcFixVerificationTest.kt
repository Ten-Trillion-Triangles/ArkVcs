package com.example

import Structs.Api.ArkRpcRequest
import Structs.Api.Request
import Structs.Api.VoidResponse
import Structs.Api.VoidParamResponse
import Structs.UserSettings
import Util.serialize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Evaluation probes: verifies whether the closure-serialization blocker in ArkVcs's RPC
 * path was fixed by the PCP-style name-registry dispatch (ArkFunctionRegistry + ArkRpcRequest).
 *
 * PROBE 1: The new wire format (functionName + args, both plain strings) round-trips
 *          through kotlinx.serialization across the serialization boundary.
 * PROBE 2: The registry lookup path actually resolves a registered name and dispatches
 *          to the bound callable (the only callable that ever crosses the boundary is the name).
 * PROBE 3: The residual old path — Request/VoidRequest closure fields — is documented:
 *          kotlinx cannot serialize a Kotlin function reference; any code still sending
 *          those payloads is still broken.
 */
class ArkRpcFixVerificationTest
{

    @Test
    fun probe1_arkRpcRequestRoundTripsAcrossSerializationBoundary()
    {
        val request = ArkRpcRequest(functionName = "runFileTransferTask",
            args = """{"action":"upload","filePath":"/tmp/x","fileName":"x.bin"}""")

        // The exact shape that must cross the wire: encode to JSON, decode back.
        val json = Json.encodeToString(ArkRpcRequest.serializer(), request)
        val decoded = Json.decodeFromString(ArkRpcRequest.serializer(), json)

        assertNotNull(decoded)
        assertEquals("runFileTransferTask", decoded.functionName)
        assertEquals(request.args, decoded.args)
        // No closure field named exactly "function" in the payload — the old wire format is gone.
        assertTrue(json.contains("\"functionName\""))
        assertTrue(!json.contains("\"function\":"), "payload must not carry the old closure field")
    }

    @Test
    fun probe1b_fixedResponseTypesCarryNamesNotClosures()
    {
        val void = VoidResponse(); void.functionName = "onChallengeAccepted"
        val json = Json.encodeToString(VoidResponse.serializer(), void)
        val decoded = Json.decodeFromString(VoidResponse.serializer(), json)
        assertEquals("onChallengeAccepted", decoded.functionName)

        val voidParam = VoidParamResponse(); voidParam.functionName = "onDataReady"; voidParam.args = """{"k":"v"}"""
        val decodedParam = Json.decodeFromString(VoidParamResponse.serializer(),
            Json.encodeToString(VoidParamResponse.serializer(), voidParam))
        assertEquals("onDataReady", decodedParam.functionName)
    }

    @Test
    fun probe2_registryLookupDispatchesToBoundCallable() = runBlocking {

        ArkFunctionRegistry.clear()
        ArkFunctionRegistry.register("echoArgs",
            { json, user: UserSettings -> "echo:$json:${user.username}" })

        // Exactly what ApiRoutes.kt:87-99 does: get by name, invoke with args + user.
        val sig = ArkFunctionRegistry.get("echoArgs")
        assertNotNull(sig, "registered function must resolve by name")
        val user = UserSettings(); user.username = "operator"
        val result = sig.function("""{"x":1}""", user)
        assertEquals("echo:{\"x\":1}:operator", result)

        // Unknown names must fail the lookup (server responds 'Unknown function').
        assertEquals(null, ArkFunctionRegistry.get("not-registered"))
        ArkFunctionRegistry.clear()
    }

    @Test
    fun probe3_residualClosurePathStillCannotSerialize()
    {
        val request = Structs.Api.Request()
        // Set the closure the same way the old makeRequest did.
        request.function = { json, user -> "handled:$json" }

        // kotlinx.serialization cannot encode a Kotlin function reference.
        val json = runCatching {
            Json.encodeToString(Request.serializer(), request)
        }

        assertTrue(json.isFailure,
            "Closure field in Request still blocks serialization — old path is unfixed (expected for eval)")
        // The lenient Util.serialize swallows this into an empty string, which is the
        // failure signature this blocker produced in the live app.
        val lenient = serialize(request)
        assertTrue(lenient.isEmpty() || !lenient.contains("handled"),
            "Lenient serialize degrades the closure payload to nothing")
    }
}
