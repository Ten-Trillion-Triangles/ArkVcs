package com.example

import Enums.Permissions
import Tasks.Enums.TaskAction
import Tasks.Enums.TaskCategory
import Tasks.TaskManager.Task
import Tasks.TaskManager.taskGraph
import Tasks.TaskManager.taskManager
import Tasks.Structs.TaskSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.withLock
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.TimeUnit
import kotlin.reflect.KClass

/**
 * Candidate #4 probes: "Implement TaskGraph as a topologically-ordered,
 * resource-lock-gated, cycle-detecting scheduler that runs on a plain JVM."
 *
 * Each probe prints a verdict line "[Cn] PASS|FAIL|CRASH|N-A — detail".
 * Verdicts are soft (printed) so all five probes in the suite run and produce
 * evidence; hard assertions are only used where the probe's expected outcome
 * is a PASS (C4) — C2 failing is the *finding*, not a suite error.
 */
class Probes_C {

    /** Recording task: notes wall-clock start/end, simulates work of [jsonParams] ms. */
    private class ProbeTask : Task() {
        @Volatile var startNano = -1L
        @Volatile var endNano = -1L

        override suspend fun runTask(jsonParams: String) {
            startNano = System.nanoTime()
            val workMs = jsonParams.toLongOrNull() ?: 0L
            var waited = 0L
            while (waited < workMs) {
                delay(20)
                waited += 20
            }
            endNano = System.nanoTime()
        }
    }

    private fun probeSettings(name: String, workMs: Long): TaskSettings = TaskSettings().apply {
        owner = "probe-c"
        description = name
        category = TaskCategory.Empty
        action = TaskAction.Read
        permissions = Permissions.ReadOnly
        params = workMs.toString()
    }

    private fun ms(nanoDelta: Long): Long = TimeUnit.NANOSECONDS.toMillis(nanoDelta)

    /** KClass is invariant; taskManager wants KClass<Task> but we need our recording subclass. */
    @Suppress("UNCHECKED_CAST")
    private fun <T : Task> KClass<T>.asTaskClass(): KClass<Task> = this as KClass<Task>

    // ------------------------------------------------------------------ C1

    /**
     * C1: call taskGraph.init() in a unit test on a plain JVM.
     * Expected: crash — TaskGraph.kt:38 eagerly constructs CoroutineScope(Dispatchers.Main),
     * so the object's <clinit> throws (Main dispatcher module not installed on plain JVM).
     */
    @Test
    fun c1_taskGraphInitOnPlainJvm() {
        val outcome = try {
            taskGraph.init(5000)
            "returned normally"
        } catch (t: Throwable) {
            val chain = generateSequence(t) { it.cause }
                .joinToString(" <- ") { "${it.javaClass.simpleName}${it.message?.let { m -> ": $m" } ?: ""}" }
            "threw — $chain"
        }
        when {
            outcome.startsWith("returned") ->
                println("[C1] PASS — taskGraph.init() did not throw on plain JVM (unexpected; check assumptions)")
            else ->
                println("[C1] CRASH — ${outcome}")
        }
        println("[C1]   evidence: TaskGraph.kt:38 eager `val sweepScope = CoroutineScope(Dispatchers.Main)` inside `object taskGraph` — " +
            "the object's <clinit> fails on a plain JVM; note: if a sibling probe touched taskGraph first, the JVM rethrows NoClassDefFoundError instead of the root cause.")
    }

    // ------------------------------------------------------------------ C2

    /**
     * C2: diamond dependency graph A->B, A->C, B->D, C->D.
     * Nothing in the data model expresses dependencies, so the only available
     * "scheduler" is registration order: create B, C, D back-to-back and check
     * whether D is gated behind B's and C's completion. Expected: it is NOT.
     */
    @Test
    fun c2_diamondOrderingIsTopological() = runBlocking {
        val bId = taskManager.createTask(probeSettings("B", 400), Dispatchers.Default, ProbeTask::class.asTaskClass())
        val cId = taskManager.createTask(probeSettings("C", 400), Dispatchers.Default, ProbeTask::class.asTaskClass())
        // D "depends on" B and C — registered immediately after them; nothing expresses that edge.
        val dId = taskManager.createTask(probeSettings("D", 400), Dispatchers.Default, ProbeTask::class.asTaskClass())

        val b = taskManager.getTaskById(bId) as ProbeTask
        val c = taskManager.getTaskById(cId) as ProbeTask
        val d = taskManager.getTaskById(dId) as ProbeTask

        listOf(b, c, d).forEach { it.getRunningJob()?.join() }

        val t0 = minOf(b.startNano, c.startNano, d.startNano)
        val rel = { n: Long -> ms(n - t0) }
        val gated = d.startNano >= b.endNano && d.startNano >= c.endNano
        val verdict = if (gated) "PASS — D started only after both B and C completed"
        else "FAIL — D started at ${rel(d.startNano)}ms, but B ended at ${rel(b.endNano)}ms and C at ${rel(c.endNano)}ms; no dependency edges exist, D is gated only by registration order"
        println("[C2] $verdict")
        println("[C2]   timeline: B ${rel(b.startNano)}->${rel(b.endNano)}ms | C ${rel(c.startNano)}->${rel(c.endNano)}ms | D ${rel(d.startNano)}->${rel(d.endNano)}ms")
        println("[C2]   evidence: no edges/depends-on fields on Task, TaskSettings, taskManager, or taskGraph — topological gating is a NEW feature, not present today")
        // soft: verdict printed; the FAIL outcome is the finding
        assertTrue(t0 >= 0 && b.endNano > b.startNano)
    }

    // ------------------------------------------------------------------ C3

    /**
     * C3: does markAndSweep actually REMOVE a completed task from the graph,
     * or only mark it? Also: is taskGraph even callable on a plain JVM?
     */
    @Test
    fun c3_markAndSweepRemovesGarbageTask() = runBlocking {
        val id = taskManager.createTask(probeSettings("sweep-me", 200), Dispatchers.Default, ProbeTask::class.asTaskClass())
        val task = taskManager.getTaskById(id)!!
        task.getRunningJob()?.join()
        taskManager.endTask(id, markGarbage = true) // status=Complete, isGarbage=true
        val listedBefore = taskManager.getTaskById(id) != null
        val countBefore = taskManager.getTasks().size

        val sweepCall = try {
            taskGraph.markAndSweep()
            "returned"
        } catch (t: Throwable) {
            "threw ${t.javaClass.simpleName}: ${t.message}"
        }

        // Emulate the sweep step (TaskGraph.kt:77 destroyTask) directly, proving
        // the removal primitive even though the watchman is unreachable.
        val removalWorked = try {
            taskManager.destroyTask(task)
            taskManager.getTaskById(id) == null && taskManager.getTasks().size == countBefore - 1
        } catch (t: Throwable) {
            false
        }

        val verdict = when {
            sweepCall.startsWith("returned") -> "PASS — markAndSweep callable"
            else -> "CRASH — taskGraph unreachable on plain JVM ($sweepCall)"
        }
        println("[C3] $verdict — listedBeforeSweep=$listedBefore; underlying destroyTask primitive ${if (removalWorked) "removes the task from runningTasks (TaskManager.kt:173-180)" else "FAILED"}")
        println("[C3]   code-read: two-phase — a pass MARKS garbage/hung tasks (TaskGraph.kt:81-113), only the NEXT pass SWEEPS them (lines 72-79: destroyTask + markedTasks.remove). A plain Complete task with a dead job is NOT swept on the same pass it finished.")
    }

    // ------------------------------------------------------------------ C4

    /**
     * C4: does Task.getResourceLock() actually block? Two coroutines race for
     * the same resource; the second must wait for the first's release.
     */
    @Test
    fun c4_resourceLockBlocksSecondHolder() = runBlocking {
        // A task that is genuinely running (300 ms of work) at the moment we race for its locks.
        val probe = Task.create(probeSettings("lock-race", 300), Dispatchers.Default, ProbeTask::class)
        val a1 = probe.getResourceLock("res-A")
        val a2 = probe.getResourceLock("res-A")
        val b1 = probe.getResourceLock("res-B")

        val sequence = mutableListOf<String>()
        val h1 = launch(Dispatchers.Default) {
            a1.withLock {
                sequence += "h1-in"
                delay(300)
                sequence += "h1-out"
            }
        }
        delay(50) // h1 holds the lock; now let h2 try to enter
        val h2WaitMs = CompletableDeferred<Long>()
        val h2 = launch(Dispatchers.Default) {
            val t0 = System.nanoTime()
            a2.withLock {
                sequence += "h2-in"
                h2WaitMs.complete(ms(System.nanoTime() - t0))
                delay(40)
                sequence += "h2-out"
            }
        }
        h1.join()
        h2.join()
        val blocked = h2WaitMs.await()

        println("[C4] ${if (blocked >= 200 && sequence[0] == "h1-in" && sequence[1] == "h1-out") "PASS" else "FAIL"} — second holder blocked ${blocked}ms behind the first; ordering: $sequence")
        println("[C4]   same-resource Mutex identity: ${a1 === a2}; distinct resource distinct instance: ${a1 !== b1}; lock is a real coroutine Mutex from the resourceLockMutex-guarded map (TaskManager.kt:50-73, Task.kt:136 delegates)")
        assertTrue(a1 === a2, "same resource must return the same Mutex instance")
        assertTrue(a1 !== b1, "different resources must return distinct Mutex instances")
        assertTrue(blocked >= 200, "second holder only blocked ${blocked}ms — lock would be a no-op")
        assertEquals(listOf("h1-in", "h1-out", "h2-in", "h2-out"), sequence)
    }

    // ------------------------------------------------------------------ C5

    /**
     * C5: does ANY cycle-detection / topological / dependency primitive exist?
     * Reflection sweep over the task subsystem + grep evidence.
     */
    @Test
    fun c5_cycleDetectionLogicPresent() {
        val suspects = listOf("depend", "edge", "cycle", "circul", "topolog",
            "predecessor", "successor", "parent", "child", "ready", "unlock")
        val hits = mutableListOf<String>()

        fun scan(cls: Class<*>) {
            try {
                for (m in cls.declaredMethods) {
                    // Skip synthetic/serializer-generated accessors (e.g. kotlinx
                    // serialization's access$get$childSerializers$cp) — they are
                    // not real API surface.
                    if (m.isSynthetic || m.name.startsWith("access$")) continue
                    val hay = m.name.lowercase() + " " + m.parameterTypes.joinToString(" ") { it.simpleName.lowercase() }
                    if (suspects.any { it in hay }) hits += "${cls.simpleName}.${m.name}"
                }
            } catch (t: Throwable) {
                println("[C5]   scan error on ${cls.simpleName}: $t")
            }
        }
        scan(taskManager.javaClass)
        scan(Task::class.java)
        scan(TaskSettings::class.java)
        // taskGraph.class would force the poisoned <clinit>; load without
        // initialization. NOTE: Kotlin compiles `object taskGraph` to a class
        // literally named "taskGraph" (lowercase) — probing "TaskGraph" would
        // ClassNotFoundException and falsely suggest the class is absent.
        try {
            scan(Class.forName("Tasks.TaskManager.taskGraph", false, this.javaClass.classLoader))
        } catch (t: Throwable) {
            println("[C5]   taskGraph class reflection unavailable: ${t.javaClass.simpleName}: ${t.message}")
        }

        val verdict = if (hits.isEmpty()) "FAIL — no dependency/edge/cycle/topolog API exists anywhere in the task subsystem; cycle detection would be a NEW feature"
        else "PASS — candidate APIs found: ${hits.joinToString(" | ")}"
        println("[C5] $verdict")
        println("[C5]   grep evidence: 0 matches for cycle|circular|topolog in TaskGraph.kt + TaskManager.kt; 0 matches for dependsOn|edge|prereq across src/main/kotlin/Tasks/ (whole tree)")
        assertTrue(true)
    }
}
