package com.example

import Structs.Api.ArkRpcRequest
import Structs.Api.Request
import Structs.AuthSettings
import Structs.UserSettings
import Util.getClientKey
import Util.decryptString
import Util.encryptString
import Util.serialize
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import java.io.File
import kotlin.collections.IndexedValue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Probe suite B — verifies the state of the RPC migration and the auth-key bootstrap
 * gap in ArkVcs (isolated copy). Maps to eval-task candidate #3:
 * "Finish the RPC migration + bootstrap the auth key so the server actually authenticates".
 *
 * Probe map:
 *   B1 — new RPC path works: ArkRpcRequest round-trips, registry dispatch resolves names.
 *        (Re-implements ArkRpcFixVerificationTest probes 1/1b/2 as JUnit5-native tests
 *        because the vintage engine for kotlin.test/JUnit4 classes is not resolvable
 *        offline under Gradle 8.4; the original class's XML result is corroborating
 *        evidence when available.)
 *   B2 — legacy closure path is still broken: Request.function (a Kotlin suspend
 *        lambda) cannot be serialized by kotlinx; the lenient Util.serialize degrades
 *        the payload to an empty string.
 *   B3 — auth key bootstrap: no code in src/main ever assigns a non-empty value to
 *        AuthSettings.cachedKey / cachedInitialKey → every decrypt/encrypt call in
 *        ApiRoutes uses empty-string keys, so the live server can never authenticate.
 *   B4 — crypto layer round-trip with a deterministic keypair: proves XSalsa20-Poly1305
 *        + Argon2 key derivation works when a real key is supplied, i.e. the ONLY
 *        remaining gap in candidate #3 is key bootstrap, not broken crypto.
 *   B5 — migration completeness: ApiRoutes.kt portable-key branch still deserializes
 *        into the legacy Request struct, not ArkRpcRequest.
 */
class Probes_B
{

    companion object
    {
        const val EXPECTED_USER = "probe_user"
        const val EXPECTED_HWID = "probe_hwid_1234"
        const val PROBE_FUNC = "runFileTransferTask"
        const val PROBE_ARGS = """{"action":"upload","filePath":"/tmp/x","fileName":"x.bin"}"""
    }

    //==================================================================================
    // B1 — new RPC path: wire format + name-registry dispatch both work.
    //==================================================================================
    @Test
    fun b1_arkRpcRequestRoundTripsAndRegistryDispatches()
    {
        val request = ArkRpcRequest(functionName = PROBE_FUNC, args = PROBE_ARGS)
        val json = Json.encodeToString(ArkRpcRequest.serializer(), request)
        val decoded = Json.decodeFromString(ArkRpcRequest.serializer(), json)

        assertNotNull(decoded)
        assertEquals(PROBE_FUNC, decoded.functionName)
        assertEquals(PROBE_ARGS, decoded.args)
        // The new wire format carries a name, not the old closure field.
        assertTrue(json.contains("\"functionName\""))
        assertTrue(!json.contains("\"function\":"),
            "payload must not carry the old closure field")
    }

    @Test
    fun b1b_fixedResponseTypesCarryNamesNotClosures()
    {
        val void = Structs.Api.VoidResponse(); void.functionName = "onChallengeAccepted"
        val json = Json.encodeToString(Structs.Api.VoidResponse.serializer(), void)
        val decoded = Json.decodeFromString(Structs.Api.VoidResponse.serializer(), json)
        assertEquals("onChallengeAccepted", decoded.functionName)

        val voidParam = Structs.Api.VoidParamResponse(); voidParam.functionName = "onDataReady"
        voidParam.args = """{"k":"v"}"""
        val decodedParam = Json.decodeFromString(
            Structs.Api.VoidParamResponse.serializer(),
            Json.encodeToString(Structs.Api.VoidParamResponse.serializer(), voidParam))
        assertEquals("onDataReady", decodedParam.functionName)
    }

    @Test
    fun b1c_registryLookupDispatchesToBoundCallable() = runBlocking {
        b1c_probeBody()
    }

    private suspend fun b1c_probeBody() {
        val name = "probeB_echo"
        com.example.ArkFunctionRegistry.register(
            name, { json, user: UserSettings -> "echo:$json:${user.username}" })
        try {
            val sig = com.example.ArkFunctionRegistry.get(name)
            assertNotNull(sig, "registered function must resolve by name")
            val user = UserSettings(); user.username = "operator"
            val result = sig!!.function("""{"x":1}""", user)
            assertEquals("echo:{\"x\":1}:operator", result)
            assertEquals(null, com.example.ArkFunctionRegistry.get("probeB-not-registered"))
        } finally {
            com.example.ArkFunctionRegistry.clear()
        }
    }

    //==================================================================================
    // B2 — legacy closure path is still broken, as documented.
    //==================================================================================
    @Test
    fun b2_legacyClosurePathStillCannotSerialize()
    {
        val request = Request()
        request.function = { json, _ -> "handled:$json" }

        val strict = runCatching { Json.encodeToString(Request.serializer(), request) }
        assertTrue(strict.isFailure,
            "Closure field in Request still blocks strict serialization (expected: old path unfixed)")

        // The lenient Util.serialize swallows the failure into an empty string —
        // this is exactly the failure signature the live app showed.
        val lenient = serialize(request)
        assertTrue(lenient.isEmpty() || !lenient.contains("handled"),
            "Lenient serialize degrades the closure payload to nothing")
    }

    //==================================================================================
    // B3 — nothing in main src bootstraps AuthSettings.cachedKey / cachedInitialKey.
    //==================================================================================
    @Test
    fun b3_noMainSourceInitializesAuthKeys()
    {
        val base = File("src/main").absoluteFile
        assertTrue(base.isDirectory, "probe B3 needs src/main at ${base.path}")

        // 1) Defaults in AuthSettings.kt: both keys must be the empty string.
        val authSettingsFile = File(base, "kotlin/Structs/AuthSettings.kt")
        val authSettingsText = authSettingsFile.readText()
        val defaultKeyMatch = Regex("""\bvar\s+cachedKey\s*=\s*"""").find(authSettingsText)
        val defaultInitialMatch = Regex("""\bvar\s+cachedInitialKey\s*=\s*"""").find(authSettingsText)
        assertNotNull(defaultKeyMatch, "AuthSettings.cachedKey default declaration not found")
        assertNotNull(defaultInitialMatch, "AuthSettings.cachedInitialKey default declaration not found")

        // 2) Scan every main source file for a MEMBER ASSIGNMENT to the AuthSettings
        //    fields (dotted receiver: "authSettings.cachedKey = ..." or
        //    "serverEnv.get().getAuthSettings().cachedKey = ..."). That is the only
        //    pattern that could ever bootstrap a real key. Bare local vars and the
        //    empty-string data-class defaults must not count.
        val memberAssignment = Regex("""\.(cachedKey|cachedInitialKey)\s*=(?!=)""")
        val findings = mutableListOf<String>()

        base.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .forEach { f ->
                val rel = f.relativeTo(base).path
                val lines = f.readLines()
                lines.forEachIndexed { idx, line ->
                    if (memberAssignment.containsMatchIn(line)) {
                        findings += "$rel:${idx + 1}: ${line.trim()}"
                    }
                }
            }

        assertEquals(0, findings.size,
            "Expected zero member assignments bootstrapping cachedKey/cachedInitialKey; " +
                "found ${findings.size}: " + findings.joinToString(" | "))
    }

    @Test
    fun b3b_runtimeConsequenceEmptyKeyDecryptAlwaysEmpty()
    {
        // Construct exactly what the server would hold at runtime: default AuthSettings.
        val settings = AuthSettings()
        assertEquals("", settings.cachedKey, "AuthSettings.cachedKey default is empty")
        assertEquals("", settings.cachedInitialKey, "AuthSettings.cachedInitialKey default is empty")

        // With an empty key every cryptoprim here must fail its size precondition
        // and return "" — proving validateAuthKey in ApiRoutes.kt:247 (decryptString
        // with cachedKey) can never yield a usable token.
        val key = getClientKey(EXPECTED_USER, EXPECTED_HWID)
        val token = encryptString("locked-key-json", key)
        assertTrue(token.isNotEmpty(), "encryption with a real 32-byte key must succeed")
        assertEquals("", decryptString(token, settings.cachedKey),
            "decrypting with the (empty) default server key must return empty string")
        assertEquals("", decryptString(token, settings.cachedInitialKey),
            "decrypting with the (empty) default initial key must return empty string")
    }

    //==================================================================================
    // B4 — crypto layer round-trip with a deterministic keypair.
    //==================================================================================
    @Test
    fun b4_cryptoRoundTripWithDeterministicKey()
    {
        val key = getClientKey(EXPECTED_USER, EXPECTED_HWID)
        assertEquals(32, key.size,
            "getEncryptionKey must produce exactly 32 bytes, got ${key.size}")

        val request = ArkRpcRequest(functionName = PROBE_FUNC, args = PROBE_ARGS)
        val json = Json.encodeToString(ArkRpcRequest.serializer(), request)
        val encrypted = encryptString(json, key)
        assertTrue(encrypted.isNotEmpty(), "encryption with a valid 32-byte key must succeed")

        val decrypted = decryptString(encrypted, key)
        assertEquals(json, decrypted, "decrypt(encrypt(x)) must return x byte-for-byte")

        val roundTrip = Json.decodeFromString(ArkRpcRequest.serializer(), decrypted)
        assertEquals(request, roundTrip, "full serialize → encrypt → decrypt → deserialize round-trip")

        // Negative controls: wrong key and tampered ciphertext must both fail cleanly.
        val wrongKey = getClientKey("other_user", "other_hwid_9999")
        assertEquals("", decryptString(encrypted, wrongKey),
            "decrypting with a different user's key must return empty string")
        val tampered = CharArray(encrypted.length).apply { this[0] = if (this[0] == 'A') 'B' else 'A' }.toString()
        assertEquals("", decryptString(tampered, key),
            "tampered ciphertext must fail MAC verification and return empty string")
    }

    //==================================================================================
    // B5 — portable-key branch in ApiRoutes.kt still deserializes into legacy Request.
    //==================================================================================
    @Test
    fun b5_portableKeyBranchStillUsesLegacyRequestStruct()
    {
        val file = File("src/main/kotlin/com/example/plugins/ApiRoutes.kt")
        assertTrue(file.isFile, "ApiRoutes.kt not found at ${file.absolutePath}")
        val text = file.readText()

        // The portable-key branch must still be deserializing into the legacy Request struct.
        val legacyLine = text.lineSequence()
            .withIndex()
            .firstOrNull { (_, l) -> l.contains("deserialize<Request>") }
        val legacyHit = legacyLine as? IndexedValue<String>
        assertNotNull(legacyHit,
            "Expected at least one deserialize<Request> call site (legacy struct) in ApiRoutes.kt")
        assertTrue(legacyHit!!.value.contains("requestBody"),
            "legacy deserialize<Request> must read the decrypted request body")

        // And confirm the NEW struct is only used on the locked-key branch, by name.
        assertTrue(text.contains("deserialize<ArkRpcRequest>(requestBody)"),
            "locked-key branch must deserialize into ArkRpcRequest")

        // Evidence line numbers for the report.
        val legacyAt = legacyHit!!.index + 1
        val rpcAt = text.lineSequence().withIndex().first { it.value.contains("deserialize<ArkRpcRequest>(requestBody)") }.index + 1
        val registryAt = text.lineSequence().withIndex().first { it.value.contains("ArkFunctionRegistry.get(arkRpcRequest.functionName)") }.index + 1

        println("B5 evidence: legacy deserialize<Request> at ApiRoutes.kt:$legacyAt ; " +
            "new deserialize<ArkRpcRequest> at ApiRoutes.kt:$rpcAt ; " +
            "registry dispatch at ApiRoutes.kt:$registryAt")
    }
}
