# ArkVcs baseline probe suites

Offline probe suites that establish **what works and what does not** in this codebase.
They were written to choose a coding-eval task and are kept here so anyone
authoring or re-running an eval on ArkVcs gets the same baseline instead of
re-deriving it.

Source: four isolated subagent probes run 2026-10-02 against four separate repo
copies. The `.kt` files in `probes/` are **byte-identical** to those copies
(SHA-256 recorded below). Nothing here ever modified the main working tree.

---

## ⚠️ Read this before you run or reuse anything here

**Most of these probes assert the BROKEN state.** They are forensic evidence, not
a test suite for a fix. Several of them **must go RED once the bug is fixed** —
that red is the probe succeeding at its job, not a regression.

If you paste them into a repo as "tests" and hand that to a coding agent, you are
telling the agent its correct fix is a failure. Classify before you reuse.

### Classification

| Probe | Asserts | Kind | After a correct fix |
|---|---|---|---|
| `A1_fabricatedTicketNeverValidates_issueTicketNeverWired` | a fabricated ticket never validates | **SNAPSHOT** | 🔴 goes red once tickets are actually issued |
| `A2_isValidFilePathRejectsTraversal` | validator rejects `../` | **INVARIANT** | stays green — keep |
| `A2_isValidFilePathBehaviorMatrix` | validator's full accept/reject matrix | **INVARIANT** | stays green — keep |
| `A3_issueValidateDiscardOneTimeLifecycle` | issue→validate→discard one-time semantics | **INVARIANT** | stays green — keep |
| `A4_guardExpression_isTautology_alwaysTrue` | the `!=Complete \|\| !=Failed` guard is always true | **SNAPSHOT** | 🔴 goes red once the guard is corrected |
| `A4_markTaskHungOverwritesCompleteStatus` | a Complete task flips to Hung | **SNAPSHOT** | 🔴 goes red once fixed |
| `A7_argumentParser_*` (2 tests) | the parser handles spaced `key = value` | **INVARIANT** (currently RED) | 🟢 goes green when `Config.kt` is fixed |
| `b1/b1b/b1c` RPC path | name-based dispatch round-trips | **INVARIANT** | stays green — keep |
| `b2_legacyClosurePathStillCannotSerialize` | the legacy closure path is still broken | **SNAPSHOT** | 🔴 goes red if the legacy path is removed |
| `b3_noMainSourceInitializesAuthKeys` | zero code bootstraps `cachedKey` | **SNAPSHOT** | 🔴 goes red once bootstrap is added |
| `b3b_runtimeConsequenceEmptyKeyDecryptAlwaysEmpty` | empty key ⇒ decrypt yields `""` | **INVARIANT** | stays green — it is a crypto precondition |
| `b4_cryptoRoundTripWithDeterministicKey` | crypto round-trips; wrong key/tamper fail | **INVARIANT** | stays green — keep |
| `b5_portableKeyBranchStillUsesLegacyRequestStruct` | portable-key branch is still on the legacy struct | **SNAPSHOT** | 🔴 goes red once migrated |
| `c1_taskGraphInitOnPlainJvm` | `taskGraph` cannot init headless | **SNAPSHOT** | 🔴 goes red once `Dispatchers.Main` is removed |
| `c2_diamondOrderingIsTopological` | (soft) no dependency gating exists | **SNAPSHOT** (soft) | verdict flips once a DAG exists |
| `c3_markAndSweepRemovesGarbageTask` | two-phase GC; removal primitive works | **INVARIANT** | stays green — keep |
| `c4_resourceLockBlocksSecondHolder` | resource lock genuinely blocks | **INVARIANT** | stays green — keep |
| `c5_cycleDetectionLogicPresent` | (soft) no cycle-detection API exists | **SNAPSHOT** (soft) | verdict flips once added |
| `d1_restKt_httpClientConstructedPerCall` | no pooled HttpClient | **SNAPSHOT** | 🔴 goes red once pooling is added |
| `d2_utilKt_onErrorActionSkipsNoRetry` | no retry/backoff exists | **SNAPSHOT** | 🔴 goes red once retry is added |
| `d3_noRateLimitingMechanismInMainSource` | no rate limiting exists | **SNAPSHOT** | 🔴 goes red once added |
| `d4_noRpcDispatchOrTaskLifecycleCountersRegistered` | registry wired, nothing measured | **SNAPSHOT** | 🔴 goes red once instruments are added |
| `d5_runFileTransferTask_withValidTicketUploads…` | the runner works offline end-to-end | **INVARIANT** | stays green — keep |
| `d5b_runFileTransferTask_badInputReturnsError` | bad input ⇒ `LogResponse.error=true` | **INVARIANT** | stays green — keep |
| `d6_noKeyExpiryOrTtlLogicInAuthSettingsOrKeyStore` | no key TTL exists | **SNAPSHOT** | 🔴 goes red once TTL is added |

**INVARIANT** = describes required behaviour; safe to keep as a regression test.
**SNAPSHOT** = describes a defect's presence; it is evidence, and it inverts on fix.

---

## What is runnable today vs. what needs porting

`A`, `B`, `C`, `D` all ran green in the probe copies — but each of those copies had a
**hand-patched `build.gradle.kts`**. They do **not** drop into this repo runnable.

Why:

1. This repo's `build.gradle.kts` has **no `useJUnitPlatform()`**, so Gradle uses the
   JUnit 4 runner. All of these probes are JUnit 5 (`org.junit.jupiter.api.Test`), so
   under the stock build they are **silently skipped**, and the stock
   `ArgumentParserTest` errors with `InvalidTestClassError`.
2. `Probes_D` uses `@ParameterizedTest`, which needs `junit-jupiter-params`.

`Probes_A` is the suite to port first if you want something runnable now: it is
self-contained (no `@ParameterizedTest`, no `Component()`-based scaffolding), and its
irrelevant `A7` parser tests simply fail against today's `Config.kt` — which is itself
the finding.

### Known build.json gap (pre-existing, unrelated to these probes)

`src/test/kotlin/Global/ArgumentParserTest.kt` declares tests JUnit 5 will run, but
the **`ArgumentParser` class itself lives in `src/main/kotlin/Global/Config.kt:208`**
— there is no `src/main/kotlin/Global/ArgumentParser.kt`. Any plan that assumes a
separate parser file is misreading the tree.

### Wiring the JUnit platform (verified working offline)

```kotlin
tasks.test { useJUnitPlatform() }

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.11.4")
}
```

Also swap `kotlin-test-junit` → **`kotlin-test-junit5`**. Without that swap,
`kotlin.test.Test` maps to the JUnit 4 annotation in Jupiter mode and the existing
`ApplicationTest` / `ArkRpcFixVerificationTest` are **silently skipped** — the count
drops from 9 tests to 3 with no error. That combination was verified offline:
`BUILD SUCCESSFUL`, 9/9 green.

---

## Confirmed findings

Each line was produced by a probe above or by a direct source read.

### File transfer / tickets
- `ticketManager.issueTicket` (`src/main/kotlin/KeyStore/Ticket.kt:71`) has **zero call
  sites**. Nothing ever mints a ticket, so `validateTicket` always returns null and
  every transfer dies at `FileTransferTask.kt:55` with "Invalid or expired ticket".
- `issueTicket` returns `Boolean`, **not the generated UUID** — even when called, the
  caller cannot obtain the `ticketId` it must present.
- `ticketManager`'s `tickets` / `discardedTickets` (`Ticket.kt:53-54`) are plain
  `mutableMapOf` / `mutableListOf` with **no synchronization**. Measured: 16 threads ×
  300 ops on one path → **4800 ops, 15 `ConcurrentModificationException`**. `FileTicket`
  values are not `val`, so `issueTicket` mutates a ticket that is concurrently visible.
- `discardTicket` leaves the emptied list in the map under the path key (never removes
  it); `discardedTickets` grows without bound (no TTL/eviction).
- `FileUtil.isValidFilePath` (`src/main/kotlin/Util/FileUtil.kt:23`) is correct and has
  **zero callers** — path validation is not on the transfer path.
- `FileTransferResponse` is **never constructed**; handlers serialize `LogResponse`
  instead.
- The `//todo` permission check sits at `src/main/kotlin/Tasks/TaskRunner/Runners.kt:107`.

### Task / scheduler / concurrency
- `Task.kt:118` — `if (status != Complete || status != Failed)` is a **tautology**; true
  for every value, so the branch always fires and `markTaskHung()` runs unconditionally.
- `Task.kt:37` builds a `CoroutineScope` with no `SupervisorJob`, never cancelled.
- `Task.kt:115` launches into an inline `CoroutineScope(Dispatchers.Default)` that is
  never retained, joined, or cancelled.
- `TaskGraph.kt:38` — `CoroutineScope(Dispatchers.Main)` on a JVM server. `taskGraph`
  cannot initialize at all on a plain JVM.
- `TaskManager.getResourceLock` (`TaskManager.kt:47-74`) returns from **inside**
  `resourceLockMutex.withLock`, and `fileLocks` entries are never removed.
- `Task.getResources()` (`Task.kt:80`) returns the internal `requestedResources` list
  **by reference**, leaking mutable state past its mutex.
- `TaskManager.kt:125` mutates `taskIdCounter++` with no lock.
- `FileTransferTask` acquires `taskManager.getResourceLock(...)` and then calls
  `endTask(...)` while holding it (`FileTransferTask.kt:92-93`, `232-233`), forming a
  nested, invertible lock order.
- **No scheduling, batching, or queueing exists** anywhere — the only parallel-related
  line is `withParallelism(1)` in `src/main/kotlin/Util/Crypto.kt:61`.

### Auth / RPC
- **Zero** code assigns a non-empty `AuthSettings.cachedKey` / `cachedInitialKey`
  (`Structs/AuthSettings.kt:37,39` hold `""` defaults), so `decryptString` with the
  server key always yields `""`.
- Crypto underneath is sound: `getClientKey` → 32 bytes; encrypt→decrypt round-trips;
  wrong key and tampered ciphertext both yield `""`.
- `ApiRoutes.kt:145` (portable-key branch) still deserializes the legacy `Request`
  struct while the locked-key branch uses `ArkRpcRequest`.

### Not implemented at all
- No merge system. `TaskAction.Merge` (`src/main/kotlin/Tasks/Enums/TaskAction.kt:14`)
  and the `ForkSettings` KDoc (`src/main/kotlin/Enums/ForkSettings.kt:7-24`) are the only
  traces. `ProjectManifest` (`src/main/kotlin/Structs/ProjectManifest.kt:13-57`) holds
  `checkoutList`, `versionMap`, `changelistMap`, `virtualFileSystem`, `extensionTypes`
  — **nothing reads or writes any of it**. `FileType.Text/Bin` exists and
  `ProjectManifest.kt:48-52` already documents the intended contract ("text which are
  mergeable, or binary which are not which must be treated as exclusive checkout").
- `FileTransferRequest` carries **no version field**, so same-version collisions cannot
  be detected on the wire today.

---

## Integrity

```
54197ad6b0c8954d37b135e97b4452d520f206fba5e7de9417b3f43932e0da8c  probes/Probes_A.kt
2f506e870fe769f00c21af4707610e8d5cb56bdc102763b124462fa247e0926b  probes/Probes_B.kt
be2ec6b3f50d0db5c2213f7f99375f47fda5a2c44b91442262deb0dcada75262  probes/Probes_C.kt
5fd4b6f0168964fecf232694d15b89a38bed137f20853f08fa5e8021255fec67  probes/Probes_D.kt
```

See `AUTHORING-NOTES.md` for the task-candidate analysis and environment pre-flight
these probes fed into.
