# PR #3 — fixes and decisions for review

Review baseline: `e7576c4`. This follow-up fixes the six reported defects,
adds explicit text acknowledgment to the Android client, and keeps HTTP 503
transfers pending for retry. It does not implement the deferred Android app.

## Fixes

- Choosing the folder updates the running server after configuration is saved.
- Re-pairing clears the persisted outbox without reusing ids and revokes open
  listeners. Downloads require the id to remain pending, so an undeleted old
  payload cannot be downloaded by the new pair.
- Chunked uploads must finish their HTTP body and match the declared size,
  including an explicitly declared zero. Invalid uploads fail and clean up.
  `Content-Length` uploads retain completion after the final body byte.
- Android sends UTF-8 filenames in `X-File-Name-Utf8` using percent encoding
  with `%20` for spaces. Rust decodes before sanitizing. Legacy ASCII
  `X-File-Name` remains supported without decoding literal percent signs.
- Android queue changes reach disk before changing memory or deleting payloads.
  A failed write cannot make the client and store dequeue different items.
- `acknowledgeText(item)` lets a consumer confirm accepted text; a rejected
  acknowledgment remains an explicit error. File acknowledgments still follow
  successful download. Consumers remain responsible for duplicate suppression.
- HTTP 503 means temporarily unavailable for both text and file sends. HTTP
  401 still raises an error and preserves existing pending items.

## The five “Needs your call” entries

These entries are in the PR description, copied from local ticket resolutions.
There are no actionable GitHub review comments to resolve. The only GitHub
review reports that Copilot hit its quota. The recommendations below are drafts
for the owner, not claims of manual verification or owner approval.

| Ticket | What needs attention | Recommendation |
| --- | --- | --- |
| CLEAN-002 | A source file that disappears before its internal copy is made is silently discarded; the spec lacks that exception. Criterion 5 also left rejection policy undecided. | Keep 503 pending (implemented here); preserve the queue and surface 401 so the future app can request re-pairing. For a missing source, prefer an explicit “file no longer available” result in the future app rather than claiming delivery. Existing missing-file behavior is unchanged in this PR. |
| CLEAN-005 | A failed file creation now leaves a failed bubble rather than no bubble. Nobody observed that bubble in the app. | Keep the failed bubble: it explains why an attempted transfer did not arrive. Verify its appearance and that it cannot be dragged. |
| SYN-004 | Clipboard input and session-reset fixes were reviewed without running the window. | Keep both fixes. Verify screenshot paste, Explorer file paste/drop, and empty chat after exiting through the tray. |
| SYN-005 | The QR visibility fix and real mDNS discovery were not manually verified. | Keep the visibility fix and the new pair-isolation fixes. Verify the initial QR, rotation, and discovery from another device on the LAN. |
| SYN-006 | Native drag threshold and dropping a bubble back into the app were not manually verified. | Keep the fixes. Verify click versus drag, drag to Explorer, and that dropping back into the chat does not resend. |

### Proposed owner response

> Keep the failed-transfer bubble and the existing clipboard, session, QR, and
> drag fixes. Treat 503 as retryable and 401 as a re-pairing error while preserving
> pending data. Track missing-source reporting and the re-pairing UI with the
> deferred Android app. Manual desktop checks below remain outstanding; automated
> tests are not a substitute for having observed those interactions.

## One desktop verification pass

Use disposable files. Start the PC app once. Autostart is optional: enable
"Iniciar com o Windows" in settings if needed (SYN-030). If it already runs in
the tray, open that instance rather than launching a second one. No live-window
checks below were performed by this agent.

1. On a fresh profile, select the folder and receive a file without restarting.
   Confirm the QR is visible and mDNS can be discovered on another LAN device.
2. Paste a screenshot, paste one and then two Explorer files, and drop a file.
   Each file should create its own outgoing bubble; screenshot paste should not
   request clipboard permission.
3. Receive an image and a PDF. Check thumbnail, name, size, and progress. Interrupt
   a transfer: its bubble should show failure and leave no partial file.
4. Click a received file without moving: no drag. Drag it to Explorer: a copy
   appears. Drop it back into the chat: no new outgoing item. Remove the source
   file and try again: the bubble reports the missing file.
5. Close the window and reopen through the tray: the session remains. Exit
   through the tray and restart: the chat is empty, but pending sends survive.
6. Re-pair with items pending: the QR changes, the old listener disconnects,
   and the newly paired client receives none of the old pending items.

## Verification and independent review

Full gate passed on 2026-09-20: Rust formatting, Clippy with warnings denied,
35 Rust tests (4 configuration, 2 drag-path, 29 HTTP/server), TypeScript checking,
and Gradle check with 30 passing Kotlin tests.

Regression tests were run red before their fixes through `gate.ps1 -TestsOnly`.
They cover live folder selection, pair revocation and clearing, chunked size and
framing errors, UTF-8 filenames, enqueue/dequeue write failures, text ACKs, and
503/401 handling. The existing tests for concurrent upload completion remain.

This machine defaults to Java 26, which cannot run this Gradle version. A portable
Microsoft JDK 17 was downloaded to the temporary directory and selected with
process-local `JAVA_HOME`; system Java and repository toolchain requirements were
not changed. The Gradle 8.10 archive was downloaded with curl after Java's download
timed out, and its SHA-256 matched the official distribution checksum.

Suggested independent review prompt:

> Review the changes since e7576c4 on PR #3. Focus on pair rotation and open
> listeners, durable queue mutation failures, HTTP body framing, Unicode filename
> interoperability, and the Tauri folder-picker wiring. Check the regression tests
> and the revised CLEAN-006 criterion. Do not interpret a green gate as desktop
> UI verification. Report actionable defects without editing the code.
