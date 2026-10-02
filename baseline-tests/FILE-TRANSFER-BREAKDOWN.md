# ArkVcs file-transfer subsystem — authoring breakdown

Evidence labels: **[SRC]** = I read the source this session. **[GREP]** = my own
repo-wide grep, zero hits. **[PROBE]** = verified by a subagent probe run offline.
**[?]** = suspected from reading, not yet empirically confirmed.

The point of this doc: separate "the broken thing IS the task" (fine) from
"the task DEPENDS on something broken" (a problem — that's the criterion).

---

## 1. WHAT EXISTS NOW

**Handlers — complete-looking, 3 actions**
- `FileTransferTask` — `FileTransferTask.kt:32-283`. `runTask` (:41) deserializes
  `FileTransferRequest`, validates ticket (:54), routes to `handleUpload` (:86),
  `handleDownload` (:153), `handleDelete` (:217). [SRC]
- Upload (:86-142): dir creation (:96), Base64-decode of **`request.checksum`** as
  the payload (:106), append if `chunkIndex>0 && file.exists()` (:109), else delete
  + overwrite (:117-121), resource lock (:92-93), discard ticket (:125), returns
  `LogResponse`. [SRC]
- Download (:153-208): streams 8KB chunks (:169-178), computes SHA-256 (:181-189),
  discards ticket (:192), returns file bytes **inside `LogResponse.message`** (:196). [SRC]
- Delete (:217-260): exists check, resource lock (:232-233), delete, discard ticket
  (:239), `LogResponse`. [SRC]

**Ticket system — implemented, one-time semantics**
- `FileTicket` (`Ticket.kt:10-45`): `filepath` + `ticketId` + `cinit`, with
  equals/hashCode. [SRC]
- `ticketManager` (`Ticket.kt:51-124`): in-memory `tickets: MutableMap<String,
  MutableList<FileTicket>>` (:53) + `discardedTickets: MutableList<String>` (:54);
  `issueTicket(path)` (:71), `validateTicket(filePath, ticketId)` (:102),
  `discardTicket(ticket)` (:114). [SRC]

**Path/IO helpers — implemented**
- `FileUtil.isValidFilePath` (`FileUtil.kt:23-28`): rejects empty, rejects `..`,
  rejects leading `/`, regex `^[a-zA-Z0-9._/-]+$`. Think: does what it says. [SRC]
- `FileUtil.calculateChecksum(ByteArray)` SHA-256→Base64 (`:11`), `ensureDirectoryExists`
  (`:17`), `getFileExtension` (`:30`). [SRC]

**Wire types**
- `FileTransferRequest` (`FileTransferRequest.kt:8-17`): action, filePath, fileName,
  fileSize, chunkIndex, totalChunks, **checksum**, ticketId. Note: no offset/resume
  field. [SRC]
- `FileTransferResponse` (`FileTransferResponse.kt:6-13`): success, message, filePath,
  fileSize, checksum, bytesTransferred. [SRC]
- `FileTransferApi` (`FileTransferApi.kt:25-77`): client request builders
  `createUploadRequest` (:31), `createDownloadRequest` (:50), `createDeleteRequest` (:66)
  → each returns `ArkRpcRequest(functionName="runFileTransferTask", args=...)`. [SRC]

**Runner + wiring**
- `runFileTransferTask` (`Runners.kt:104-114`): deserialize → **`//todo` permission
  (:107)** → `createTaskSettings` (:109) → `Task.create(..., Dispatchers.IO, ...)`
  (:110) → `join` (:111) → return result (:113). [SRC]
- Registered as an RPC: `ApiRoutes.kt:294`. [SRC]
- `FileTransferTask.createTaskSettings` (`FileTransferTask.kt:272-281`): permissions =
  `Permissions.Write`. [SRC]
- Permission machinery that DOES exist and work: `requirePermission` (`Permissions.kt:21`),
  `hasPermission` hierarchy `None<ReadOnly<Write<Maintainer<Admin` (`:35-39`). It IS used
  elsewhere (`Runners.kt:55` in the portable-key runner). [SRC]
- Resource locking that works: `taskManager.getResourceLock` (`TaskManager.kt:47-74`), a
  real `Mutex` map. `Task.getResourceLock` delegates (`Task.kt:136-139`). [SRC]

---

## 2. WHAT WORKS (verified)

- One-time ticket lifecycle in isolation: issue → validate → discard → revalidate==null. [PROBE A3]
- `FileUtil.isValidFilePath("../../etc/passwd")` returns **false** (the validator is correct). [PROBE A2]
- The full runner path **runs offline with an in-memory KeyStore**: a valid ticket +
  upload writes the exact payload bytes to disk, consumes the ticket (one-time), and
  returns a `LogResponse`; a bogus ticket returns `LogResponse{error=true,
  "Invalid or expired ticket"}`. **No live server or auth needed to test this.** [PROBE D5, D5b]
- Resource lock is real and blocking (2nd holder waits; same resource → same Mutex
  instance; distinct resource → distinct). [PROBE C4]
- `Task.create` → `runTask` → `endTask` → `getResult` plumbing works for the happy path. [PROBE D5]
- SHA-256 + Base64 helpers work. [SRC + PROBE D5]

---

## 3. WHAT DOESN'T WORK

**F1. Ticket issuance is never wired → the whole feature is dead at runtime. [GREP]**
`ticketManager.issueTicket` (`Ticket.kt:71`) has **zero call sites anywhere in the repo**
(only its own definition). Nothing ever puts a ticket in the store, so
`validateTicket` (`Ticket.kt:102`) always returns null, so
`FileTransferTask.runTask:55` always ends with `"Invalid or expired ticket"`.
**Every real upload/download/delete fails.** This is the single fatal blocker.

**F2. Path validation is dead code → traversal guard never runs. [GREP]**
`isValidFilePath` (`FileUtil.kt:23`) has **zero call sites**. `ensureDirectoryExists`
(:17) and `calculateChecksum` (:11) likewise have **zero call sites**. The handlers write
to `ticket.filepath` directly (`FileTransferTask.kt:88, 155, 219`) with no validation.
The guard exists and is correct, but nothing calls it.

**F3. No upload-root confinement. [SRC]**
`baseUploadPath = "uploads/"` (`FileTransferTask.kt:35`) is declared and **never
referenced**. Uploads land at `ticket.filepath` with no confinement to a base root, so
no "must live under uploads/" policy is enforceable today.

**F4. Permission check missing on the transfer path. [SRC]**
`Runners.kt:107` is literally `//todo: Validate the user has write acsess, validate they
can perform whatever repo operation comes prior to this.` `requirePermission` is never
called for file transfer (it IS called at `Runners.kt:55` for portable keys — so the
helper works, it's just not applied here). `createTaskSettings` stamps
`Permissions.Write` (`FileTransferTask.kt:279`) as a *label*, never a *check*.

**F5. `FileTransferResponse` is dead code. [GREP]**
Only two references repo-wide: the import (`FileTransferTask.kt:9`) and its own
declaration (`FileTransferResponse.kt:6`). It is **never constructed**. All three handlers
serialize `LogResponse` instead. So `success/bytesTransferred/checksum/fileSize` never
reach the client.

**F6. Download computes a SHA-256 then throws it away. [SRC]**
`handleDownload` computes `val checksum = digest...` (`FileTransferTask.kt:189`) and then
the response at `:194-196` only sets `message = encodedData` — **`checksum` is never put
in the response.** The class KDoc (`:150`) promises "Base64 encoded file data in response
message with SHA-256 checksum." Checksum verification cannot work end-to-end today.

**F7. Upload payload rides in the `checksum` field → blocks real checksum verification. [SRC]**
Upload reads the file data from `request.checksum` (`FileTransferTask.kt:106`,
`Base64.decode(request.checksum)`). So on upload, `checksum` is the payload, not a hash.
There is no field left to carry a real integrity hash on upload — the contract must change
for integrity to be verifiable.

**F8. No resume/offset, and no completeness check. [SRC]**
`totalChunks` (`FileTransferRequest.kt:14`) is **never read** by `FileTransferTask`
(only `chunkIndex` is, at `:109`). Consequences: (a) a partially-uploaded file reports
`"File uploaded successfully"` (`:129`) — no final-chunk detection; (b) chunks that
arrive out of order silently corrupt with no offset validation; (c) a retried chunk 0
**deletes the file first** (`:117-120`), wiping prior progress. There are no offset/
bytesTransferred fields on the request or response to build resume on.

**F9. `Task.kt:118` tautology marks every task Hung. [SRC + PROBE A4]**
`if(status != Complete || status != Failed)` is always true, so `markTaskHung()` (`:120`)
fires unconditionally after `join`. **A successfully-completed file transfer still ends up
status=Hung.** Probe A4 confirmed a Complete task flips to Hung. This bites this very
feature.

**F10. Runners bypass `taskManager` → transfer tasks are untracked. [SRC]**
`runFileTransferTask` calls `Task.create` directly (`Runners.kt:110`), not
`taskManager.createTask` (`TaskManager.kt:123`). So the task is never added to
`runningTasks` → it's invisible to `taskGraph` and **cannot be cancelled**, and
`taskId` is never incremented for it (`taskManager.getTaskIdCounter` at `TaskManager.kt:34-38`
does not increment; only `createTask` does, at `:125`).

**F11. Duplicate shadow `FileTicket`. [SRC]**
`FileTransferResponse.kt:16-19` declares a second, empty `data class FileTicket` shadowing
`KeyStore.FileTicket`. Dead code; a name collision hazard.

**[?] F12. `endTask` cancels its own job. [SUSPECTED]**
`endTask` calls `runningJob?.cancel()` (`Task.kt:172`) — but `endTask` is invoked *from
inside* `runningJob`. Cancelling the current job at a suspension point could skip the
subsequent `resultMutex.withLock { taskResult = resultJson }` (`:175-177`). Probe D5 saw
the happy path return successfully, so this appears benign in practice (uncontended
`Mutex.lock()` doesn't suspend), but it is fragile. Label: suspected, verify before
making it a graded requirement.

---

## 4. WHAT IS CLEARLY PLANNED (intent in code/KDoc, not built)

- **Ticket-gated one-time authorization** for file ops — KDoc `Ticket.kt:57-70`: "a required
  security measure... repo and global permissions are insufficient to determine if a file
  operation was authorized. And if an operation was not explicitly authorized by a repo
  read, write, or delete request then the Ark file system can end up damaged."
- **Resolve-repo-request → issue-ticket flow.** `FileTicket` KDoc (`Ticket.kt:12-24`) says the
  filepath is "typically provided by ark after resolving a repo request, the file version
  expected, and the changelist", tying tickets to `VirtualFileSystem` / `ChangelistManifest` /
  `VersionManifest`. That domain is **schema-only** today.
- **Upload-root confinement** via `baseUploadPath` (`FileTransferTask.kt:35`).
- **Streamed I/O for files >2GB** — class KDoc (`FileTransferTask.kt:28-31`) + the 8KB loops.
- **Download returns data + SHA-256 checksum** — KDoc (`:150`).
- **Permission enforcement before file ops** — `Runners.kt:107` todo + class KDoc
  (`Runners.kt:15-21`: runners "ensure that permissions and other prerequisites are
  correctly handled").
- **Structured result type** — `FileTransferResponse` (exists, unused).
- **Client request builders** — `FileTransferApi` (exists, unused by anything).

## 5. WHAT IS NOT DONE (no code at all)

- Any call site for `issueTicket` — **the ticket-issuance flow does not exist.** [GREP]
- Repo/changelist → ticket resolution (the whole repo domain is dataclasses only).
- Path validation enforcement on the transfer path. [GREP]
- Permission enforcement on the transfer path. [SRC]
- Checksum surfaced to / verified by the client. [SRC]
- Resume/offset support (missing contract fields). [SRC]
- Upload-root confinement (`baseUploadPath` unused). [SRC]
- `FileTransferResponse` usage. [GREP]
- Task registration with `taskManager` for the transfer path. [SRC]
- Any real test of the transfer path. The only test touching it is
  `ArkRpcFixVerificationTest` probe 1 (`:34`), which only asserts the wire-format *string*
  contains `"functionName"` — it never runs a transfer.

---

## 6. AUTHORING NOTES — the dependency traps

This is the part your criterion is about. For the file-transfer task:

**FINE — the broken thing IS the task:**
- F1 (no ticket issuance), F2 (validator dead), F3 (no upload root), F4 (no permission
  check), F5 (response unused), F6 (checksum discarded), F8 (no resume/completeness).
  These are *the work*.

**DANGER — the oracle must NOT depend on these, or the task is unfair:**
1. **Do not verify via task status.** F9 (`Task.kt:118` tautology) means "status == Complete"
   is always false — every transfer reads Hung. If your success check reads task status, it
   depends on a broken thing that isn't the task. **Verify via the returned response payload
   + filesystem effects instead** (bytes on disk, one-time ticket consumption, error text).
2. **Do not require the download checksum to round-trip unless integrity is in scope.**
   F6 means the checksum never leaves the server today; making it a graded requirement is
   fine *only if* "surface integrity" is explicitly part of the task.
3. **Do not require cancellation/visibility unless you intend F10 as in-scope.** Transfer
   tasks aren't registered with `taskManager`, so "the task is cancellable / appears in the
   task list" fails for a reason unrelated to file transfer.
4. **Auth is NOT a dependency.** The runner executes offline against an in-memory KeyStore
   [PROBE D5] — you do not need the broken auth-bootstrap (`cachedKey` never initialized)
   fixed for this task to be verifiable. Good news: this task is self-contained.
5. **The test-runner IS a dependency.** The offline JUnit-Platform config must be baked into
   `environment/` (jupiter 5.11.3 + launcher 1.11.4 via flat `file()` dep; vintage or a
   JUnit5 conversion for the existing JUnit4 tests). Without it, any "add a test" instruction
   hits broken infra that isn't the task.

**Suggested scope (tight, self-contained, offline-checkable):**
"Wire ticket issuance into the transfer path (a request-ticket step that mints a one-time
`FileTicket` for a resolved file path), enforce `FileUtil.isValidFilePath` + `baseUploadPath`
confinement before every write, add the missing permission check at `Runners.kt:107`,
return a structured `FileTransferResponse` (including the SHA-256 the download already
computes), and fix upload completeness so a partial chunk sequence is not reported as
success."

**Suggested oracle:** unit tests that (a) a transfer without an issued ticket is rejected,
(b) with an issued ticket it succeeds and the ticket is consumed, (c) `../` paths are
rejected before any write, (d) a partial chunk sequence is NOT reported success, (e) the
response is a `FileTransferResponse` carrying the checksum. All run offline via the runner
against an in-memory KeyStore. Do **not** assert on task status.
