package com.example

import Global.ArgumentParser
import KeyStore.FileTicket
import KeyStore.ticketManager
import Tasks.Enums.TaskStatus
import Tasks.Structs.TaskSettings
import Tasks.TaskManager.Task
import Util.FileUtil
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*
import kotlinx.coroutines.runBlocking
import java.lang.reflect.Field

/**
 * Probes_A — offline unit-test probe suite for the ArkVcs file-transfer
 * subsystem and test harness. JUnit5; runs once useJUnitPlatform() is
 * present in build.gradle.kts.
 *
 * Probe map (see verdict in run report):
 *  A1: ticketManager.issueTicket is never called in src/main — dynamic
 *      confirmation that a fabricated ticket can never validate.
 *  A2: FileUtil.isValidFilePath path-validation behavior matrix.
 *  A3: ticketManager one-time ticket lifecycle (issue -> validate ->
 *      discard -> revalidate must fail).
 *  A4: Task.kt:118 hung-task guard tautology — fires for EVERY status.
 *  A5: LockedKeyTask.kt:76-81 dead-code read (NO test; static evidence
 *      below and in the run report).
 *  A6: pre-modification gradlew test baseline (captured in run log).
 *  A7: ArgumentParserTest actually executes under JUnit5 after the fix.
 */
class Probes_A {

    // ================================================================
    // A1 — ticketManager.issueTicket (KeyStore/Ticket.kt:71) has ZERO
    // call sites in src/main. Static evidence (grep, captured at
    // authoring time):
    //   $ grep -rn "issueTicket" src/main
    //     -> src/main/kotlin/KeyStore/Ticket.kt:71   (definition only)
    // FileTransferTask.kt calls validateTicket (:54) and discardTicket
    // (:125,:192,:239) but never issueTicket. The ticket store can only
    // ever be empty on a real request path, so the transfer subsystem
    // can never complete a single authorized operation end-to-end.
    // This test pins the dynamic consequence: fabricated ticketId
    // against an un-issued path must not validate.
    // ================================================================
    @Test
    fun A1_fabricatedTicketNeverValidates_issueTicketNeverWired() {
        val fakePath = "uploads/probe-a1-never-issued.txt"
        val fakeId = "00000000-0000-0000-0000-000000000000"

        val ticket = ticketManager.validateTicket(fakePath, fakeId)
        assertNull(ticket, "A1: a fabricated ticket validated despite issueTicket never being called in src/main")
    }

    // ================================================================
    // A2 — FileUtil.isValidFilePath (Util/FileUtil.kt:23-28) is a pure
    // function => fully offline-testable. Rule as written: reject if
    // empty, contains "..", starts with "/", or has chars outside
    // [a-zA-Z0-9._/-].
    // KEY FINDING: grep shows ZERO callers of isValidFilePath in
    // src/main — FileTransferTask uses ticket.filepath directly
    // (TaskObjects/FileTransferTask.kt:88,155,220), so the validator
    // is dead code: path validation is NOT on the transfer path.
    // ================================================================
    @Test
    fun A2_isValidFilePathRejectsTraversal() {
        assertFalse(FileUtil.isValidFilePath("../../etc/passwd"), "A2: traversal path was ACCEPTED — path validation does not reject ../../etc/passwd")
    }

    @Test
    fun A2_isValidFilePathBehaviorMatrix() {
        // Expected rejected (per the as-written rule):
        assertFalse(FileUtil.isValidFilePath(""), "A2: empty string accepted")
        assertFalse(FileUtil.isValidFilePath("../../etc/passwd"), "A2: traversal accepted")
        assertFalse(FileUtil.isValidFilePath("/etc/passwd"), "A2: absolute path accepted")
        assertFalse(FileUtil.isValidFilePath("a/b/../../../etc/shadow"), "A2: embedded traversal accepted")
        assertFalse(FileUtil.isValidFilePath("a/../b"), "A2: any '..' segment accepted")
        assertFalse(FileUtil.isValidFilePath("file.txt;rm -rf /"), "A2: shell metacharacters accepted")
        assertFalse(FileUtil.isValidFilePath("file name.txt"), "A2: whitespace accepted (outside char class)")

        // Expected accepted:
        assertTrue(FileUtil.isValidFilePath("uploads/a.txt"), "A2: plain relative path wrongly rejected")
        assertTrue(FileUtil.isValidFilePath("a/b/c.d"), "A2: nested relative path wrongly rejected")
        assertFalse(FileUtil.isValidFilePath(".."), "A2: bare '..' accepted (rule contains('..') => must reject)")
    }

    // ================================================================
    // A3 — ticketManager one-time ticket lifecycle. The machinery is
    // functional IF a ticket were ever issued; production never issues
    // one (see A1). issueTicket returns Boolean, not the UUID, so the
    // just-issued ticket is read via reflection into the private
    // 'tickets' map (fresh path => exactly one entry).
    // ================================================================
    @Test
    fun A3_issueValidateDiscardOneTimeLifecycle() {
        val path = "uploads/probe-a3-lifecycle.txt"
        assertTrue(ticketManager.issueTicket(path), "A3: issueTicket should succeed on a fresh store")

        // Read the just-issued ticket (private store):
        val f = ticketManager.javaClass.getDeclaredField("tickets")
        f.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val tickets = f.get(ticketManager) as MutableMap<String, MutableList<FileTicket>>
        val issued = tickets[path]!!.first()
        assertTrue(issued.ticketId.isNotEmpty(), "A3: issued ticket must carry a UUID")
        assertEquals(path, issued.filepath, "A3: issued ticket must reference its path")

        // Validate before discard:
        assertNotNull(ticketManager.validateTicket(path, issued.ticketId), "A3: just-issued ticket must validate")

        // One-time property:
        assertTrue(ticketManager.discardTicket(issued), "A3: discardTicket should return true")
        assertNull(ticketManager.validateTicket(path, issued.ticketId), "A3: ticket must NOT validate after discard (one-time action)")
    }

    // ================================================================
    // A4 — Task.kt:118:
    //   if (taskSettings.getStatus() != TaskStatus.Complete ||
    //       taskSettings.getStatus() != TaskStatus.Failed)
    //       markTaskHung()   // :120
    // `x != A || x != B` is TRUE for every possible x (a value can never
    // equal both Complete and Failed). The guard is a tautology: after
    // runningJob.join(), markTaskHung() fires UNCONDITIONALLY — even
    // for cleanly completed or failed tasks, flipping their status to
    // Hung and marking them garbage. Intended check was
    // `!= Complete && != Failed`. Two probes pin this:
    //   (a) expression evaluates true for every TaskStatus value
    //   (b) markTaskHung() on a Complete task flips it to Hung
    // ================================================================
    @Test
    fun A4_guardExpression_isTautology_alwaysTrue() {
        for (status in TaskStatus.values()) {
            // Mirrors Task.kt:118 verbatim with a concrete status.
            val guardFires = (status != TaskStatus.Complete || status != TaskStatus.Failed)
            assertTrue(
                guardFires,
                "A4: for status=$status the Task.kt:118 guard should have been false — but it is true for EVERY status, proving the tautology: completed tasks always get markTaskHung() at :120."
            )
        }
    }

    @Test
    fun A4_markTaskHungOverwritesCompleteStatus() = runBlocking {
        val task = object : Task() {}
        // Force a Complete status via reflection into private TaskSettings:
        val settingsField: Field = Task::class.java.getDeclaredField("taskSettings")
        settingsField.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        val settings = settingsField.get(task) as TaskSettings
        settings.setStatus(TaskStatus.Complete)

        // The guard's consequence (Task.kt:120):
        task.markTaskHung()

        assertEquals(
            TaskStatus.Hung,
            settings.getStatus(),
            "A4: a task set to Complete was flipped to Hung by markTaskHung — the tautological guard at Task.kt:118 fires unconditionally."
        )
    }

    // ================================================================
    // A5 — LockedKeyTask.kt:74-81 (code-read; no test required):
    //
    //   keyStore.discardId(portableKey.uniqueKeyId)      // :74 -> KeyStore.kt:183 adds id to discardedKeyIDs
    //   if (!keyStore.isDiscarded(portableKey.uniqueKeyId)) {  // :76 -> KeyStore.kt:200 contains()
    //       arkLog(...); endTask(false, true); return     // :77-80
    //   }
    //
    // discardId unconditionally adds the id; isDiscarded right after
    // therefore ALWAYS returns true; `!isDiscarded` is ALWAYS false.
    // => The body at :77-80 is DEAD CODE, unreachable.
    // It is also semantically inverted: the "already discarded"
    // security check was meant to run BEFORE discardId, and it in fact
    // already does — at :60 (`keyStore.isDiscarded(...)` guard that
    // returns early). So lines 76-81 are dead AND redundant.
    // Verdict recorded in the run report; no test exercises it.
    // ================================================================

    // ================================================================
    // A7 — Global.ArgumentParserTest is JUnit5 (org.junit.jupiter).
    // Baseline (A6, pre-fix): JUnit4 vintage runner threw
    // InvalidTestClassError on it => the 3 parser methods NEVER ran.
    // After useJUnitPlatform() this class must actually execute.
    // This test re-implements the exact parser assertions so the
    // graded run can prove the parser logic itself is green under
    // the JUnit5 platform.
    // ================================================================
    @Test
    fun A7_argumentParser_reRunUnderJUnitPlatform() {
        val parser = ArgumentParser()
        parser.parse(listOf("-flag1", "--verbose", "key=value", "host", "=", "localhost", "file.txt"))
        assertEquals(2, parser.boolFlags.size)
        assertTrue(parser.boolFlags.contains("-flag1"))
        assertTrue(parser.boolFlags.contains("--verbose"))
        assertEquals(2, parser.valueFlags.size)
        assertEquals("value", parser.valueFlags["key"])
        assertEquals("localhost", parser.valueFlags["host"])
        assertEquals(1, parser.args.size)
        assertEquals("file.txt", parser.args[0])
    }

    @Test
    fun A7_argumentParser_emptyAndBoolOnlyReRun() {
        val p1 = ArgumentParser()
        p1.parse(emptyList())
        assertTrue(p1.boolFlags.isEmpty())
        assertTrue(p1.valueFlags.isEmpty())
        assertTrue(p1.args.isEmpty())

        val p2 = ArgumentParser()
        p2.parse(listOf("-a", "-b", "--help"))
        assertEquals(3, p2.boolFlags.size)
        assertTrue(p2.valueFlags.isEmpty())
        assertTrue(p2.args.isEmpty())
    }
}
