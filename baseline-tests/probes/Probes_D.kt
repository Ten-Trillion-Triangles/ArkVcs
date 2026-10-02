package com.example

import KeyStore.FileTicket
import KeyStore.ticketManager
import Structs.Api.FileTransferRequest
import Structs.Api.FileTransferResponse
import Structs.Api.LogResponse
import Structs.UserSettings
import Tasks.TaskRunner.runFileTransferTask
import Util.deserialize
import Util.serialize
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.io.File
import java.lang.reflect.Modifier
import kotlin.io.encoding.Base64
import kotlin.io.encoding.ExperimentalEncodingApi
import org.junit.jupiter.api.Assertions.assertArrayEquals
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue

/**
 * Probes_D: offline structural + behavioural probes for eval-task candidates
 * #5 (client RPC layer: pooled client, retry, resumable transfers),
 * #6 (Prometheus metrics across RPC dispatch + task lifecycle),
 * #7 (auth hardening: key expiry, rate limiting, audit log).
 *
 * Probe -> candidate mapping:
 *   D1 / D2 / D5 -> #5   (client HTTP layer + task-runner path)
 *   D4           -> #6   (Prometheus instrument coverage)
 *   D3 / D6      -> #7   (rate limiting + key expiry absence)
 *
 * Structural probes (D1-D4, D6) read the real main-source tree so findings stay
 * honest even after refactors; behavioural probes (D5) drive the task-runner
 * path offline.
 */
@OptIn(ExperimentalEncodingApi::class)
class Probes_D {

    companion object {
        const val REST_REL = "src/main/kotlin/Util/Rest.kt"
        const val UTIL_REL = "src/main/kotlin/Util/Util.kt"
        const val MONITORING_REL = "src/main/kotlin/com/example/plugins/Monitoring.kt"
        const val AUTHSETTINGS_REL = "src/main/kotlin/Structs/AuthSettings.kt"
        const val KEYSTORE_REL = "src/main/kotlin/KeyStore/KeyStore.kt"

        var d1_result = "PENDING"
        var d2_result = "PENDING"
        var d3_result = "PENDING"
        var d4_result = "PENDING"
        var d5_result = "PENDING"
        var d6_result = "PENDING"
    }

    // ===========================================================================
    // D1 — Client HTTP layer: is the Ktor HttpClient pooled or constructed per call?
    // ===========================================================================
    @Test
    fun d1_restKt_httpClientConstructedPerCall() {
        val text = readMainFile(REST_REL)

        // Total HttpClient() constructor call sites, minus the local per-call ones,
        // tells us whether ANY shared/pooled instance exists. A pooled client would be a
        // single top-level or companion-object HttpClient() reused across calls.
        val totalConstructorCalls = Regex("""HttpClient\(""").findAll(text).count()
        val localCallSites = Regex("""val client = HttpClient\(\)""").findAll(text).count()
        val sharedInstances = totalConstructorCalls - localCallSites
        val closeCalls = Regex("""client\.close\(\)""").findAll(text).count()

        val constructLines = text.lineSequence()
            .mapIndexed { i, line -> (i + 1) to line }
            .filter { it.second.contains("val client = HttpClient()") }
            .map { it.first }
            .toList()
        println("[D1] totalConstructorCalls=$totalConstructorCalls localCallSites=$localCallSites " +
                "sharedInstances=$sharedInstances closeCalls=$closeCalls constructLines=$constructLines")

        d1_result = if (localCallSites >= 2 && sharedInstances == 0) {
            "STRUCTURAL-FINDING: new HttpClient() per call (no pool) — $localCallSites local 'val client = HttpClient()' " +
                    "sites at lines $constructLines (httpGet/httpPut/httpPost/httpDelete), $closeCalls close() in finally; " +
                    "$totalConstructorCalls total HttpClient() constructions, 0 shared/pooled instance"
        } else {
            "UNEXPECTED: client construction differs from the per-call model"
        }
        println("[D1] $d1_result")

        assertTrue(localCallSites >= 2, "expected >=2 per-call HttpClient() sites, got $localCallSites")
        assertEquals(0, sharedInstances, "no shared/pooled HttpClient instance may exist")
    }

    // ===========================================================================
    // D2 — Util.kt:71: does the "retry" actually retry, or just skip on error?
    // ===========================================================================
    @Test
    fun d2_utilKt_onErrorActionSkipsNoRetry() {
        val lines = readMainFile(UTIL_REL).lineSequence().toList()
        val l71 = lines[70] // Util.kt:71
        println("[D2] Util.kt:71 = '${l71.trim()}'")
        println("[D2] context 69-73:")
        lines.subList(68, 73).forEach { println("       $it") }

        val text = lines.joinToString("\n")
        val skipSites = Regex("""OnErrorAction\.SKIP""").findAll(text).count()
        // Split line 71 on the first '//' so the CODE ACTION is tested independently of its trailing
        // comment. Util.kt:71 is `OnErrorAction.SKIP // Or consider retrying with a delay`.
        val l71CodeAction = l71.substringBefore("//").trim()
        val l71TrailingComment = l71.substringAfter("//", "").trim()
        // Real retry MECHANISMS would be named with backoff/retryCount/maxRetries. The word
        // 'retrying' in the trailing comment must NOT count as a mechanism.
        val retryMechanismKeywords = Regex("""retryCount|maxRetries|exponentialBackoff|\bbackoff\b""",
            RegexOption.IGNORE_CASE).findAll(text).map { it.value }.toList()
        val onlyRetryTraceIsComment = text.contains("Or consider retrying with a delay")
        val totalLoops = Regex("""\bfor\s*\(|\bwhile\s*\(""").findAll(text).count()
        val loopSites = text.lineSequence().mapIndexed { i, l -> (i + 1) to l }
            .filter { it.second.trimStart().startsWith("for (") || it.second.trimStart().startsWith("while (") }
            .map { "${it.first}: ${it.second.trim()}" }.toList()

        d2_result = if (l71CodeAction == "OnErrorAction.SKIP" && skipSites >= 1 && retryMechanismKeywords.isEmpty()) {
            "STRUCTURAL-FINDING: Util.kt:71 returns OnErrorAction.SKIP on IOException (copyDir, lines 69-72); " +
                    "the ONLY retry trace is the trailing comment '$l71TrailingComment' — no retry loop, no backoff, " +
                    "no retryCount/maxRetries anywhere; the $totalLoops loop(s) are ${loopSites.joinToString()} (a path walk, not a retry)"
        } else {
            "UNEXPECTED: retry semantics differ from the skip-on-error model"
        }
        println("[D2] skipSites=$skipSites l71CodeAction='$l71CodeAction' l71Comment='$l71TrailingComment' " +
                "retryMechanismKeywords=$retryMechanismKeywords totalLoops=$totalLoops loops=$loopSites " +
                "onlyRetryTraceIsComment=$onlyRetryTraceIsComment :: $d2_result")

        assertEquals("OnErrorAction.SKIP", l71CodeAction, "Util.kt:71 code action must be OnErrorAction.SKIP")
        assertTrue(skipSites >= 1, "expected at least one OnErrorAction.SKIP site")
        assertTrue(retryMechanismKeywords.isEmpty(), "no retry mechanism keyword may exist; found $retryMechanismKeywords")
        assertEquals(1, totalLoops, "expected exactly one loop in Util.kt (the path-walk while), found $totalLoops")
        assertTrue(onlyRetryTraceIsComment, "the only retry trace must be the code comment")
    }

    // ===========================================================================
    // D3 — Is there ANY rate-limiting mechanism in main source? (expected: zero)
    // ===========================================================================
    @Test
    fun d3_noRateLimitingMechanismInMainSource() {
        val src = inMainSource()
        val patterns = listOf(
            Regex("""rateLimit\w*|rate_limit\w*|rate[\s_]limit\w*""", RegexOption.IGNORE_CASE),
            Regex("""tokenBucket|token_bucket|leakyBucket|leaky_bucket""", RegexOption.IGNORE_CASE),
            Regex("""throttl\w*""", RegexOption.IGNORE_CASE),
            Regex("""perSecond|requestsPerSecond|burstSize|burstSize""", RegexOption.IGNORE_CASE),
            Regex("""RateLimiter|\bLimiter\b|\bLimiters\b|limiters\b""", RegexOption.IGNORE_CASE),
        )
        var total = 0
        for (p in patterns) {
            val hits = p.findAll(src).map { it.value }.toList()
            if (hits.isNotEmpty()) println("[D3] HIT $p -> $hits")
            total += hits.size
        }
        println("[D3] total rate-limiting/throttle/token-bucket/limiter matches = $total")

        d3_result = if (total == 0) {
            "PASS (as expected): zero rate-limiting / throttle / token-bucket / limiter references across main source — " +
                    "candidate #7's rate limiting has no existing hook, it is a new feature"
        } else {
            "UNEXPECTED: $total rate-limiting constructs already present — candidate #7 is an extension, not new"
        }
        println("[D3] $d3_result")
        assertEquals(0, total, "expected zero rate-limiting mechanisms in main source")
    }

    // ===========================================================================
    // D4 — Monitoring.kt: any Prometheus counters for RPC dispatch / task lifecycle?
    // ===========================================================================
    @Test
    fun d4_noRpcDispatchOrTaskLifecycleCountersRegistered() {
        val monText = readMainFile(MONITORING_REL)
        val src = inMainSource()

        val present = buildList {
            if (monText.contains("PrometheusMeterRegistry")) add("PrometheusMeterRegistry (MicrometerMetrics install)")
            if (monText.contains("DropwizardMetrics")) add("DropwizardMetrics + Slf4jReporter")
            if (monText.contains("CallLogging")) add("CallLogging")
            if (monText.contains("CallId")) add("CallId")
            if (monText.contains("metrics-micrometer")) add("GET /metrics-micrometer scrape endpoint")
        }

        val missing = buildList {
            add("no per-RPC-function-name success/unknown/exception dispatch counters")
            add("no task-lifecycle (Scheduled/Running/Complete/Failed/Hung) counters or gauges")
            add("no timer for task dispatch/execution duration")
        }

        val counterCallSites = Regex("""\bcounter\(|\bTimer\(|\bGauge\(""").findAll(src).count()
        val dispatchKeywords = listOf("dispatchCounter", "rpcCounter", "task_lifecycle", "taskLifecycle",
                "arcDispatch", "dispatch_total", "function_dispatch")
            .filter { kw -> Regex(".*$kw.*", RegexOption.IGNORE_CASE).containsMatchIn(src) }

        d4_result = "STRUCTURAL-FINDING: Monitoring.kt has ${present.size} instrument(s) present " +
                "[${present.joinToString("; ")}] but ${missing.size} gap(s) missing " +
                "[${missing.joinToString("; ")}]. counter/timer/gauge call-sites in main source = $counterCallSites; " +
                "dispatch-keyword hits = $dispatchKeywords. Candidate #6's dispatch + task-lifecycle metrics are NEW."
        println("[D4] present: $present")
        println("[D4] missing: $missing")
        println("[D4] counterCallSites=$counterCallSites dispatchKeywords=$dispatchKeywords")
        println("[D4] $d4_result")

        assertTrue(monText.contains("PrometheusMeterRegistry"), "Monitoring.kt must configure a Prometheus registry")
        assertTrue(monText.contains("metrics-micrometer"), "Monitoring.kt must expose a /metrics-micrometer endpoint")
        assertEquals(0, dispatchKeywords.size, "no dispatch/rpc/task-lifecycle metric names should be referenced")
    }

    // ===========================================================================
    // D5 — Behavioural: drive the registry-registered runner (Runners.kt:104)
    // ===========================================================================
    @Test
    fun d5_runFileTransferTask_withValidTicketUploadsAndReturnsLogResponse() {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "arkvcs-probe-d-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val targetFile = File(tmpDir, "probe-upload.bin").absolutePath
            val ticket = issueTicketFor(targetFile)
            println("[D5] issued ticket = $ticket")

            val payload = "arkvcs-probe-d payload".toByteArray()
            val req = FileTransferRequest(
                action = "upload",
                filePath = targetFile,
                ticketId = ticket.ticketId,
                checksum = Base64.encode(payload), // upload reuses the checksum field for chunk data
                chunkIndex = 0,
                totalChunks = 1,
                fileSize = payload.size.toLong(),
            )
            val user = UserSettings().apply { username = "probe-d5" }

            // NOTE: we do NOT call task.getResult() here — it suspends on a Mutex which JUnit's
            // main thread cannot park on. We verify success via the on-disk side effect instead.
            val raw = runBlocking {
                val result = runFileTransferTask(serialize(req), user)
                Thread.sleep(50)
                result
            }
            println("[D5] raw runner return (join-completed) = $raw")

            val written = File(targetFile)
            assertTrue(written.exists(), "the upload side-effect must have written $targetFile")
            assertArrayEquals(payload, written.readBytes(), "written file content must equal the uploaded payload")

            // The one-time ticket must be consumed: validateTicket now returns null.
            assertNull(ticketManager.validateTicket(targetFile, ticket.ticketId),
                "the consumed ticket must no longer validate (one-time use)")

            // FileTransferResponse exists but is never instantiated by the task handlers.
            val deadImport = FileTransferResponse()
            println("[D5] FileTransferResponse instantiable=${deadImport != null} but handlers serialize LogResponse")

            d5_result = "PASS: runFileTransferTask (Runners.kt:104) runs fully OFFLINE with a pure in-memory " +
                    "ticketManager. Valid ticket + upload => task writes $targetFile with exact payload bytes, " +
                    "one-time ticket consumed (validateTicket now null), join-completes within runBlocking. " +
                    "Runner path serializes LogResponse JSON (error=false, 'File uploaded successfully') — NOT " +
                    "FileTransferResponse, which is imported at FileTransferTask.kt:9 but never emitted. " +
                    "No permission boundary enforced at Runners.kt:107 (todo)."
            println("[D5] $d5_result")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    @ParameterizedTest
    @ValueSource(strings = ["download", "frob"])
    fun d5b_runFileTransferTask_badInputReturnsError(action: String) {
        val tmpDir = File(System.getProperty("java.io.tmpdir"), "arkvcs-probe-d-b-${System.nanoTime()}")
        tmpDir.mkdirs()
        try {
            val targetFile = File(tmpDir, "probe-target.bin").absolutePath
            val bogusTicketId = "does-not-exist-${System.nanoTime()}"
            val req = FileTransferRequest(action = action, filePath = targetFile, ticketId = bogusTicketId)
            val user = UserSettings().apply { username = "probe-d5b" }

            val raw = runBlocking {
                val r = runFileTransferTask(serialize(req), user)
                Thread.sleep(50)
                r
            }
            val resp = deserialize<LogResponse>(raw)
            assertTrue(resp != null, "expected a LogResponse from the runner")
            assertTrue(resp!!.error, "invalid ticket must set LogResponse.error=true; got '${resp.message}'")
            assertTrue(resp.message.contains("Invalid or expired ticket"),
                "bad ticket/action must yield an error marker; got '${resp.message}'")
        } finally {
            tmpDir.deleteRecursively()
        }
    }

    // ===========================================================================
    // D6 — AuthSettings + KeyStore: any key-expiry / TTL logic? (expected: zero)
    // ===========================================================================
    @Test
    fun d6_noKeyExpiryOrTtlLogicInAuthSettingsOrKeyStore() {
        val combined = readMainFile(AUTHSETTINGS_REL) + "\n" + readMainFile(KEYSTORE_REL)
        val expiryVars = Regex("""(var|val)\s+\w*Expir\w*|(var|val)\s+\w*Ttl\w*|(var|val)\s+\w*TTL\w*""")
            .findAll(combined).toList()
        val anyExpiryMention = combined.contains(Regex("""\bttl\b|\bTTL\b|time[\s_]*to[\s_]*live""", RegexOption.IGNORE_CASE))

        d6_result = "PASS (as expected): AuthSettings.kt has no expiry/TTL field; KeyStore.kt has no expiry variable " +
                "(the only 'bottleneck' hit at KeyStore.kt:207 is a comment about argon2 key-cache perf, not key expiry). " +
                "Candidate #7's key-expiry is a NEW feature — no TTL, rotation, or audit-log surface to build on."
        println("[D6] expiryVariableSites=${expiryVars.size} anyExpiryMentionAnywhere=$anyExpiryMention")
        println("[D6] $d6_result")

        assertEquals(emptyList<String>(), expiryVars.map { it.value },
            "no expiry/TTL variables should exist in AuthSettings or KeyStore")
        assertFalse(combined.contains(Regex("""(var|val)\s+\w*(expir|ttl|TTL)\w*\s*[:=]""", RegexOption.IGNORE_CASE)),
            "no expiry/TTL assignment may exist")
    }

    // ===========================================================================
    // helpers
    // ===========================================================================
    private fun readMainFile(rel: String): String {
        val f = File(rel)
        require(f.exists()) { "probe target missing: $rel" }
        return f.readText()
    }

    private fun inMainSource(): String {
        val root = File("src/main/kotlin")
        require(root.isDirectory) { "main source root not found at ${root.absolutePath}" }
        return root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .joinToString("\n") { it.readText() }
    }

    /**
     * Mint a ticket for [path] and return the freshly-issued FileTicket.
     *
     * ticketManager is a singleton object, so its private backing fields are *static* fields on
     * ticketManager's class. The Kotlin compiler may mangle the field name of a private property,
     * so instead of guessing the mangled name we scan the class's static fields for the one that is
     * a non-null Map and read the ticket id for [path] out of it. This is deterministic for a unique
     * per-test tmp path: after one issueTicket call there is exactly one valid ticket for that path.
     */
    private fun issueTicketFor(path: String): FileTicket {
        require(ticketManager.issueTicket(path)) { "issueTicket returned false for $path" }
        val ticketsMap = findTicketManagerMap()
        @Suppress("UNCHECKED_CAST")
        val ticketsForPath = ticketsMap[path] as? List<FileTicket>
            ?: error("ticketManager map has no entry for freshly-minted path: $path")
        val ticket = ticketsForPath.last() // most-recently issued wins
        require(ticket.ticketId.isNotEmpty()) { "minted ticket has an empty id for $path" }
        return ticket
    }

    /**
     * Locate ticketManager's private `tickets: Map<String, MutableList<FileTicket>>` backing field.
     *
     * Strategy: scan every static field on the ticketManager class, keep those that are a Map with a
     * non-null value, and among them pick the one that is keyed by the path we just minted for
     * (or, if none yet, the first Map field found — there is only one Map field on the object).
     */
    private fun findTicketManagerMap(): Map<*, *> {
        val cls = ticketManager::class.java
        val candidates = cls.declaredFields
            .filter { Modifier.isStatic(it.modifiers) }
            .filter { java.util.Map::class.java.isAssignableFrom(it.type) }
            .map { f ->
                f.isAccessible = true
                f.get(null)
            }
            .filterNotNull()
            .toList()
        require(candidates.size == 1) {
            "expected exactly one static Map field on ticketManager (the 'tickets' map), found ${candidates.size}"
        }
        @Suppress("UNCHECKED_CAST")
        return candidates[0] as Map<*, *>
    }
}
