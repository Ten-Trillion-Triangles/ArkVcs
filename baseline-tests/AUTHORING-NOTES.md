# ArkVcs → DataAnnotation coding-eval task candidates (PROBE-VERIFIED)

4 isolated subagent probe suites (candidates #1/#2, #3, #4, #5/#6/#7) ran offline
against 4 copies of the repo and produced file:line verdicts. This document is the
ground-truth "what works now / what doesn't" that the task choice is based on.

## 0. What this codebase is
ArkVcs = "Ark Version Control System". User-owned, **MIT** (commit
`891c818383bac3ad579cdec3c1c8e2b4a8d1f59c`, GPL-3.0→MIT; GPL backed up at
/tmp/ArkVcs-LICENSE-GPL.bak). Kotlin 2.0.21 / Ktor 3.0.1 / JDK 24 / Gradle 8.4.
Root = real Ktor server (RPC `/ark/{request,connect,status}`, Bearer auth over
encrypted keys, Argon2id+XSalsa20 crypto, TaskManager/TaskGraph/Task runner
system, KeyStore+tickets, manifest structs, VirtualFileSystem, Prometheus
scaffolding, WebSockets/SSE). `ArkClient/` is a nested Ktor server TEMPLATE with
empty routing/sockets/security stubs — NOT a client; real client logic is in
root `Util/Rest.kt`.

---

## 1. WORKS vs DOESN'T (probe-verified, per candidate)

### #1 Complete file-transfer
- DOESN'T WORK — ticket issuance: `ticketManager.issueTicket` (Ticket.kt:71)
  has **zero call sites** in main src. `FileTransferTask.kt:54` validates a
  ticket but nothing ever issues one → every real transfer dies at :55
  "Invalid or expired ticket". [A1]
- DOESN'T WORK — path security: `FileUtil.isValidFilePath` (FileUtil.kt:23)
  correctly rejects `../../etc/passwd` BUT has **zero callers**; the transfer
  writes to `ticket.filepath` directly (Runners.kt:88/155/220) → validator is
  dead code, traversal not enforced on the live path. [A2]
- WORKS (offline) — one-time ticket lifecycle: issue→validate→discard→
  revalidate==null runs cleanly. [A3]
- WORKS (offline) — the runner: `runFileTransferTask` runs with an in-memory
  KeyStore; upload writes exact payload bytes; one-time ticket consumed;
  bad-input path returns `LogResponse{error=true,"Invalid or expired ticket"}`.
  So it IS checkable offline. [D5, D5b]
- DOESN'T WORK — response type: `FileTransferResponse` (FileTransferResponse.
  kt:6, imported at FileTransferTask.kt:9) is **never instantiated**; the
  runner serializes a `LogResponse` instead → the real response struct is dead
  code. Runner has `//todo: Validate the user has write access` (Runners.kt:107)
  → no permission boundary. No resume token in the response (chunkIndex exists
  on the request but the response carries no offset). [D5]
- **Net: subsystem is functionally dead at runtime; fixable + offline-testable.**

### #2 Make the test suite green + fix latent bugs
- DOESN'T WORK — runner mismatch (baseline): `./gradlew --offline test` =
  **7 tests, 1 failure** = `Global.ArgumentParserTest > initializationError
  (InvalidTestClassError via the JUnit4 vintage runner)`; the 3 parser tests
  never ran. [A6]
- DOESN'T WORK — latent real bug: after adding `useJUnitPlatform()`, the 3
  parser tests DO run but `testParseArguments` **FAILS**: `expected <localhost>
  but was <null>`. Root cause = `ArgumentParser.parse` (Config.kt) — the
  `arg.contains("=")` branch shadows the spaced `key = value` branch, so a bare
  `"="`/`host` token is consumed wrong. [A7] — this is a genuine subtle logic
  bug, not just config.
- DOESN'T WORK — second config gap: adding `useJUnitPlatform()` (JUnit
  Platform) **drops the 4 `kotlin.test`/JUnit4 tests** (ApplicationTest +
  ArkRpcFixVerificationTest) from the run because the vintage engine is absent
  on the classpath → you must add `junit-vintage-engine` too or the older
  tests silently vanish. [A7]
- **Net: a compound but fully root-caused, offline-verifiable task (fix runner +
  add vintage engine + fix the ArgumentParser branch bug + latent Task.kt:118
  tautology). Cleanest `gradlew test` oracle of all candidates — but low model
  separation because a passing suite is a binary green.**

### #3 Finish RPC migration + bootstrap the auth key
- WORKS — the new RPC path: `ArkRpcRequest` round-trips, `ArkFunctionRegistry`
  dispatch resolves the bound callable, response types carry names not
  closures. [B1]
- WORKS (as documented) — legacy closure path still broken: strict
  `Json.encodeToString(Request)` **fails** on the closure field; lenient
  `Util.serialize` degrades it to `""` — matches the live-app symptom. [B2]
- DOESN'T WORK — auth bootstrap: **zero** code ever sets a non-empty
  `AuthSettings.cachedKey`/`cachedInitialKey` (only the `""` defaults at
  AuthSettings.kt:37,39 + a local `var` in ApiRoutes.kt:58-64). Consequence
  proven: `decryptString(realToken, settings.cachedKey)` → `""`. **The server
  can never authenticate at runtime.** [B3, B3b]
- WORKS — crypto underneath is SOUND: `getClientKey`→32 bytes; serialize→
  encrypt→decrypt→deserialize round-trips an `ArkRpcRequest`; wrong key and
  tampered ciphertext both → `""`. So the ONLY gap is key bootstrap, not
  crypto. [B4]
- DOESN'T WORK — migration is half-finished: locked-key branch (ApiRoutes.kt:69)
  uses `ArkRpcRequest` + registry dispatch, but the **portable-key path
  (ApiRoutes.kt:145) still deserializes the legacy `Request` struct**. [B5]
- **Net: precisely scoped "fix + bootstrap" with a deterministic reproducible
  failure signature (`""` from decrypt). CAVEAT: the probe oracle required real
  offline-JUnit-Platform wrangling (vintage engine unresolvable under Gradle 8.4;
  only jupiter 5.11.3 cached; had to convert ArkRpcFixVerificationTest to
  JUnit5 + revert the wrapper to 8.4). That fragility would hit the two models
  too if the task's own verifier leans on a JUnit5 runner — bake it into
  environment/ first.**

### #4 Headless DAG task-graph scheduler
- DOESN'T WORK (hard blocker) — `taskGraph.init()` **CRASHES** on a plain
  JVM: `IllegalStateException: Module with the Main dispatcher is missing`
  thrown at TaskGraph.kt:45, root cause = eager
  `CoroutineScope(Dispatchers.Main)` at TaskGraph.kt:38. One-line fix is a
  prerequisite to anything else. [C1]
- DOESN'T WORK — no DAG: diamond A→B/A→C/B→D/C→D → **B, C, D all start at 0ms**;
  D does NOT wait for B&C. There are **no dependency edges** in Task/
  TaskSettings/taskManager/taskGraph — ordering is pure registration order,
  zero topological gating. [C2]
- WORKS — `markAndSweep` is a two-phase GC (mark garbage/hung pass, then sweep
  via `taskManager.destroyTask` on the *next* pass). [C3]
- WORKS — resource-lock gating is REAL: `Task.getResourceLock()` is a genuine
  coroutine `Mutex` (TaskManager.kt:50-73, Task.kt:136); second holder blocked
  250ms, same-resource → same instance, distinct → distinct. This is the
  solid foundation a DAG would build on. [C4]
- DOESN'T WORK — no cycle detection: 0 matches for cycle/circular/topology/
  edge/predecessor/successor across the Tasks/ tree. **Cycle detection is a
  NEW feature, not a fix.** [C5]
- **Net: `taskGraph` today is a GC/watchdog, NOT a scheduler. Candidate #4 is
  really "introduce a topological + cycle-detecting DAG primitive (3 new
  features) on top of an existing GC + make init headless." Substantial — the
  largest of the seven.**

### #5 Complete the client RPC layer
- DOESN'T WORK — no pooling: 4× `val client = HttpClient()` at Rest.kt:28/59/
  91/122 (httpGet/Put/Post/Delete), each closed in `finally`, **0 shared
  instance, 0 timeout, 0 retry**. [D1]
- DOESN'T WORK — no retry: Util.kt:71 is literally `OnErrorAction.SKIP // Or
  consider retrying with a delay` — the "retry" is a COMMENT, not code; no
  retryCount/maxRetries/backoff anywhere. [D2]
- **Net: three independent NEW sub-parts (pool, retry, resumable transfer) +
  a real dead-code bug (the FileTransferResponse from #1).** Meaty, but
  broad + boilerplate-heavy → moderate model-separation signal.**

### #6 Observability (Prometheus across RPC dispatch + task lifecycle)
- WORKS — scaffolding: 5 instruments present (PrometheusMeterRegistry,
  DropwizardMetrics+Slf4jReporter, CallLogging, CallId, `/metrics-micrometer`).
- DOESN'T WORK — **0** `counter(`/`Timer(`/`Gauge(` call sites anywhere → the
  registry is wired but nothing is actually measured. Task = wire new
  instruments into the existing registry. [D4] **Clean + scoped but
  low-design boilerplate.**

### #7 Auth hardening (key expiry, rate limiting, audit log)
- DOESN'T WORK (all three new) — 0 rate-limiting (D3), 0 key-expiry/TTL
  (D6; KeyStore.kt:207 'bottleneck' is just an argon2-cache perf comment).
  An issued auth key is valid forever; `/ark` routes have no throttle; `arkLog`
  (LogManager.kt:73) only console-prints, fire-and-forget. [D3, D6]
  **Real security work, each sub-part independently unit-testable, but
  policy-knob-heavy → weakest model-separation of the set.**

---

## 2. MOST RECENTLY WORKED ON
The RPC closure-serialization fix (170108c + untracked wrapper):
`ArkFunctionRegistry` + `ArkRpcRequest` + ApiRoutes dispatch +
`ArkRpcFixVerificationTest` + the eval Dockerfile + ArkClient JDK-24 pins
(uncommitted). Confirmed FINISHED. Probes B1/B2 confirm the new path works and
the legacy path is intentionally still broken. Tree garbage:
`ArkClient/.hermes-tmp.pDarbS` (0-byte), `ArkClient/ArkClient.zip`.

## 3. Best-fit ranking (probe-rebased)
Criteria: clear offline oracle + multi-file + genuine design decisions +
separates two strong models.

1. **#4 DAG scheduler (re-scoped)** — BEST for a CHALLENGING task. Probes prove
   the DAG/cycle-detection genuinely don't exist and init can't even run
   headless, so a strong model has real architectural work with high design
   variance (that's what separates two strong models). Largest + most "senior."
   Rewritten one-liner: *"Add a topologically-ordered, cycle-detecting DAG
   scheduler + headless (JVM-safe) init to the task subsystem, reusing the
   existing resource-lock map; remove the Dispatchers.Main crash."*
2. **#1 file-transfer (scoped)** — BEST realistic FEATURE task. Fix ticket
   issuance + call the (dead) path validator + kill the traversal hole +
   emit a real FileTransferResponse + enforce the //todo permission. Security-
   relevant, offline-testable (in-memory KeyStore proven), clear target. Drop
   the overly-broad "permission" leg or scope it to the runner.
3. **#2 make-suite-green** — CLEANEST oracle but LOWEST model separation (a
   green `gradlew test` is binary). Strong Debugging+Code-Quality task; good
   as a *secondary* or as the pre-flight fix list, not the primary differentiator.
4. **#3 RPC-migration+bootstrap** — precise "make it actually work" gap,
   deterministic failure signature, but carries the offline-JUnit-Platform
   verifier fragility caveat (must be baked into environment/).
5. **#5 client layer** / **#6 observability** / **#7 auth hardening** — valid
   new-feature tasks, ranked by (boilerplate↘ model-separation↘).

**RECOMMENDATION:** Ship **#4** as the primary challenging task OR **#1** as the
primary realistic feature task (pick based on how hard you want it to be — the
project says "make it more complex only if both models solve it with no room
for improvement"; #4 is the higher ceiling). Use **#2** as the secondary
codebase task (cap: 1-2 tasks per codebase). Both #4 and #1 are offline-
checkable and neither needs a live server.

## 4. Pre-flight environment/ fixes (from the probes, before running models)
1. Commit the ArkClient JDK-24 toolchain pins (uncommitted → fresh clone may
   not build offline).
2. Delete `ArkClient/.hermes-tmp.pDarbS` (0-byte) + `ArkClient/ArkClient.zip`.
3. **Bake the JUnit-Platform runtime into the image** so the verifier actually
   runs: add `useJUnitPlatform()` + pin `junit-jupiter:5.11.3`/`junit-jupiter-
   engine:5.11.3` + `junit-vintage-engine` (the ONLY versions with cached
   offline metadata under Gradle 8.4; `junit-platform-launcher:1.11.3` has no
   cached jar, so serve `1.11.4` via a flat `file()` dep). Without this,
   JUnit5 probes silently skip / JUnit4 tests vanish — exactly what all 4
   subagents had to hand-solve. Bake it in so the models don't have to.
4. For #3/#4/#5: a `junit` runner that launches offline is a prerequisite to
   the verifier; ship the working `build.gradle.kts` test block.

## 5. Probe artifacts (per candidate, in the isolated copies)
- `arkvcs-probe-a/src/test/.../Probes_A.kt` (A1-A7) + modified build.gradle.kts
- `arkvcs-probe-b/src/test/.../Probes_B.kt` (B1-B5) + build.gradle.kts +
  JUnit5-converted ArkRpcFixVerificationTest
- `arkvcs-probe-c/src/test/.../Probes_C.kt` (C1-C5) + build.gradle.kts
- `arkvcs-probe-d/src/test/.../Probes_D.kt` (D1-D6, 8 invocations) + build.
  gradle.kts
None of these touched /home/cage/Desktop/Workspaces/ArkVcs.
