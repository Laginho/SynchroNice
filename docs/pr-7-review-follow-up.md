# PR 7 — review fixes

Review baseline: `452104c`. The review ran on two axes: the repo's standards, and
the specs and tickets. This follow-up fixes what it found, commits each test red
before its fix, and records the three calls the owner made on 2026-09-27. Both
"Needs your call" races from the PR description (CLEAN-013 and CLEAN-014) were
already closed by CLEAN-016 and CLEAN-017; the review confirmed this, and
nothing changes here.

## Owner calls

- **Picked-folder file names follow the PC.** The file keeps its
  `[syn-<uuid>]` marker only while it downloads. Once the content is complete,
  it is renamed to the clean name, and the provider adds `(1)` if the name is
  taken. This replaces the line in SYN-014 criterion 3 that said "Não remover o
  marcador por rename automático"; the decision is recorded in that ticket.
- **The PC persists accepted file ids.** It keeps the last 1000 in
  `accepted-file-ids.txt`, in its config folder; ADR 0002 is updated.
- **A non-401 rejection of a Pendente keeps retrying, but visibly.** The PC
  uses 400 for a stalled upload and 500 for disk errors, so these are mostly
  temporary. The chat now shows `O PC recusou um Pendente (HTTP n); tentando de
  novo.` instead of a silent Pendente.

## Fixes (spec)

| Finding | Fix | Commits |
| --- | --- | --- |
| SAF names kept `[syn-<uuid>]` forever (SYN-014) | Rename once the content is complete. A `renaming:` receipt, saved before the rename, lets a restart finish it and ACK without another download. A provider that cannot rename keeps the marked name and still delivers. | `c896863`, `fdf00fe`, `66f9a50`, `a68e051` |
| Stuck receipt with no way out (SYN-014 c.3) | "Mudar pasta…" restarts, in the chosen folder, any receipt that never got complete content. A `writing:` receipt whose destination is gone restarts by itself. | `c896863`, `fdf00fe` |
| No resend when the PC comes back (SYN-012 c.8, SYN-016 c.3/7) | Presence coming back with Pendentes schedules delivery at once. `scheduleDelivery` replaces a worker that is waiting out its backoff (up to 5 h) and still appends behind one that is sending right now. | `8bf2b16`, `d9dd9e5` |
| Large file in the background, Android 12+ (SYN-016 c.8) | A refused foreground start no longer aborts the upload; it goes on without the notification. | `8bf2b16`, `d9dd9e5` |
| Silent retry forever (SYN-013) | See the owner call above. | `8bf2b16`, `d9dd9e5` |
| Duplicate file after a PC restart | Persisted file ids, recorded after the rename (a crash between the two duplicates, never loses). | `8921e71`, `bb385cc` |
| Rotation during a share re-queued files | The batch runs in a ViewModel; a recreated `ShareActivity` waits for it. A cancelled share is no longer reported as a failure. | `64842c3`, `d8f0e20` |
| QR parser accepted `"\u-001"` (SYN-012 c.1) | `\u` requires four hex digits. | `b7159ee`, `4d47edf` |
| Copiáveis links differed from the PC (SYN-018) | Only host and port decide, as in WHATWG `new URL()`. The JavaScript `\s` spaces (NBSP, U+3000, …) end a link. Fifteen new shared fixture cases, which the PC already passed. | `f162d2a`, `d9c94bd` |
| Camera temp photo left behind (SYN-017 c.5) | Deleted in a `finally`. | `a39a9b6` |
| Id-less direct send | `ProtocolClient.sendText`/`sendFile` send the Pendente's id, so a lost 201 no longer creates a second Item. | `9b10d18`, `0eaac0d` |
| HEAD on the outbox file flickered presence | HEAD answers the headers without entering presence. | `8921e71`, `bb385cc` |
| Early tap on a downloading image (SYN-015) | A file without a URI (still downloading, or a sent file without a preview) is not clickable. This also removes the unrequested file-name copy. | `a39a9b6` |
| Receipt cleanup could stop the listen loop (CLEAN-013 item 6) | The failure is reported, and the loop reopens `/listen`. | `a39a9b6` |
| SYN-013 c.7 tested only through dead code | The test goes through `queueText`, and `AppState.sendText` is removed. | `425adbf`, `729cb57` |

## Fixes (standards)

- A test called the internal `sharedUris`, which the spec forbids ("Nunca
  funções internas"). The filter is now tested through a real share intent,
  with the app's own URIs placed ahead of the foreign one, and the function is
  private.
- ADR 0001 (the protocol must be documented): the `server.rs` header now covers
  id deduplication, the 400 for a bad id, a stalled or mismatched upload, and
  HEAD on the outbox file.
- ADR 0002 now lists all the new persisted state: the Android settings, the
  Android Pasta, the last IP, the notification prompt, the discard flag, the
  receipt steps, and the PC's file ids.
- The file-name sanitizer is shared by the share and receive paths; the
  receive side used to keep DEL. `acknowledgeFile` takes the id, so nothing
  fakes a `FileMeta` any more.
- CLEAN-012 quoted the literal debt marker in a note saying there was no debt,
  so the foreman's grep would have listed it. The note is reworded.

## Skipped, and when to revisit

- **Splitting `MainActivity.kt`** into Par store, outbox, inbox and UI; **a
  sealed `Receipt` type**; **a sealed `Trecho`**: these are design smells, not
  defects, and would multiply the review diff. Worth doing before the next
  Android feature lands.
- **A progress notification before a 503**: fixing it needs
  `Expect: 100-continue` on the upload. It is only a brief flash, and only
  for files of 10 MB or more.
- **Expedited work on API 29–30** needs `getForegroundInfo`, which would show
  a notification for short sends there.
- **A stalled download keeps the PC "online"**. The outbox body has no write
  timeout, so a phone that stays connected without reading holds presence.
  This matches CLEAN-015's accepted limit.
- **`"port":"47820"` as a string** is still accepted: it is valid JSON of the
  wrong type, and harmless.
- Smaller smells (duplicated id checks in `post_text`/`post_file`, test setup
  duplication, the naming drift from the glossary) are left alone.

## Human debt added by these fixes

Nothing below was run on a device; each point is covered only by Robolectric or
integration tests.

- In a real folder (Downloads via SAF, a USB drive, Drive), the received file
  ends with the clean name, and a second copy of the same name becomes `(1)`.
- With the app in the background on Android 12+, a file of 10 MB or more is
  still delivered, without the notification.
- Rotating the phone during a multi-photo share queues each photo once.
- With the chat open, turning the PC off and on again makes a Pendente go out
  as soon as the header says "Na rede".

## Verification

Full gate passed on 2026-09-27 at `4fd2547`:
- PC: cargo fmt, clippy with warnings denied, and 49 Rust tests (2 unit, 6
  config, 41 HTTP/server).
- PC UI: tsc and 47 Node Copiáveis cases.
- Android: `gradle check`. The Android tests were rerun from clean afterwards:
  48 app and 58 protocol-client, none failing.

Each fix above has a test commit that failed first. For compile-time reds, the
failure was also watched with a temporary stub (SAF, PC restart). The baseline
`452104c` already failed the gate once, on
`escolher_pasta_com_servidor_no_ar_libera_upload_sem_reiniciar`: a Windows
connection reset when the 503 goes out before the body is read. `4fd2547` makes
that request's body empty. After that it passed 8 runs in a row and the final
gate; one flaky failure in three runs is not proof it is gone.
