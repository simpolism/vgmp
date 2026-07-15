# Next steps

Ratified plan, 2026-07-15. Companion to `BUGS-2026-07-15.md` (bug fixes tracked there; this doc is
features and polish). Items appear roughly in build order; dependencies are called out
explicitly.

## Implementation status

- [x] Checkpoint 2: items 1–5, 7, and the queue/browser portions of item 9 are implemented and build-verified.
- [ ] Checkpoint 3: items 6, 8, 10, rotation, and final polish.

## Explicitly not doing

- **Sleep timer** — no user need.
- **M3U playlist files** — wrong model for this app: playlists reference ZIP entries and SAF
  URIs that M3U can't express. Export-as-folder (below) replaces it.
- **Play/shuffle-all from a folder** — subsumed by the queue-as-workspace design.
- **Multiple root folders** — not now; single persisted tree is fine.
- **True data-level subtrack splitting** — impossible without emulator-level analysis; songs
  share code/data inside NSF/GBS/KSS files. Header patch-splitting (below) achieves the same
  player-visible result.
- **Recursive search across subdirectories** — SAF requires a content-resolver query per
  directory; a tree walk over folders with 1–10k subdirs is unacceptable. The filter box
  (below) is scoped to the loaded listing only.

## Prerequisites (from BUGS-2026-07-15.md, being handled separately)

These bug fixes gate features here; land them first:

- **BUGS-2026-07-15.md #15** (seek during fade-out ends the track) — blocks queue/position restore,
  which seeks on startup.
- **BUGS-2026-07-15.md #17** (wall-clock position drift) — position must come from the engine
  (`VgmEngine.getCurrentSample()`) or `AudioTrack.getPlaybackHeadPosition()` before we persist
  and restore positions, or restores will be visibly wrong.
- **BUGS-2026-07-15.md #18** (ZIP cache unbounded/stale) — pairs with the "clear ZIP cache" setting below.

## 1. Persistence schema change: `subtrackIndex` everywhere

Do this first and once — three features below depend on it.

- `PlaylistTrack` (playlists/PlaylistStore.kt) silently drops `TrackRef.subtrackIndex` today.
  Add the field to the data class, JSON serialization, and `toTrackRef()`/`from()` mapping.
  Default `-1` (whole file / not a subtrack) keeps old stored playlists valid.
- The queue-persistence format (item 3) should reuse this exact track serialization.
- De-dup checks in `PlaylistStore.addTrack`/`removeTrack` must include `subtrackIndex` in the
  identity comparison (currently uri + archiveEntry only).

## 2. Natural sort

Numeric-aware name comparison ("Track 2" before "Track 10"), standard behavior including
leading-zero handling.

- One shared comparator used by: `BrowserFragment.queryChildren`, `ZipArchiveStore.listArchive`,
  and any future listing (7z, export round-trip ordering).
- Playlist export/import (item 6) relies on this: exported `NN - Name.ext` prefixes must sort
  back into playlist order.

## 3. Persist queue, position, and modes across restarts

Essential. Restore must come back **paused** at the saved position — never auto-play.

- Persist: queue track list (schema from item 1), current index, position ms, shuffle mode,
  loop mode. Endless-loop and timing settings already persist via `SettingsManager`.
- Also persist the browser location when inside a ZIP: `KEY_CURRENT` only stores Document
  locations today, so dying inside a ZIP restores to the containing folder. Store the ZIP
  location (archive URI + path) alongside.
- Save triggers: queue mutation, track change, pause, service destroy; plus a periodic
  position checkpoint while playing (position-only writes should be cheap — consider a
  separate pref key from the queue blob so the 500 ms-ish checkpoint doesn't re-serialize the
  whole queue).
- Restore in service `onCreate`/first bind: rebuild queue, open current track, seek to saved
  position, stay paused. Depends on BUGS-2026-07-15.md #15/#17 (see prerequisites).
- SAF permission loss (user revoked / tree removed): drop unopenable tracks gracefully, don't
  crash or wedge startup.

## 4. Queue as workspace + unified track-list UI

The queue is the one unnamed, always-existing, mutable playlist, wired to the player. UI
implementation is being done by the team; this section documents the ratified design.

**Placement and model**
- Pinned "Queue" row at the top of the Playlists tab, always visible; tapping it with an
  empty queue just shows an empty list. Now Playing stays purely about the current track.
- Playing a named playlist **copies** it into the queue (replace). The queue is then a working
  copy — edits never touch the saved playlist.
- "Save as playlist…" action on the queue view promotes the current queue to a named playlist.
- **Queue-replace undo:** any action that replaces the queue (tapping a browser track, playing
  a playlist) first snapshots the current queue contents + index. A snackbar offers "Undo",
  which restores the snapshot (the restored current track restarts from the beginning).
  One level deep, session-scoped — this protects an in-progress working queue from being
  accidentally blown away; it is not a general history.

**Unified track-list component** (used by both queue view and playlist detail)
- Drag-to-reorder, remove, tap-to-play-from-here.
- Currently-playing track highlighted (queue view especially — it's the "you are here" view).
- Queue view live-updates as playback advances.

**Service work**
- `PlaybackQueue` is replace-only today. Add mutation APIs: `add` (append), `insertNext`,
  `removeAt`, `move(from, to)` — all index-safe around the currently-playing item (removing
  the current track should advance playback or stop if last; removals/moves before the current
  index must adjust it).
- Expose the queue contents reactively (a `StateFlow<List<TrackRef>>` alongside
  `playbackInfo`) so the Playlists tab and queue view can observe it.

**Entry points**
- Long-press context menu (see item 9) gains "Add to queue" in the browser and playlist views.

## 5. Subtrack browsing (NSF / GBS / KSS / etc.)

Regression from the upstream fork — dropping this was not intentional. Multi-track files
should act like folders, reusing the ZIP-as-virtual-folder pattern.

- Browser: multi-track files render as expandable entries; expanding shows "Track 1..N"
  children (`TrackRef` with `subtrackIndex` set). NSFe carries real per-track titles — use
  them; plain NSF/GBS get numbered entries.
- **Expand on tap only.** Detecting track count requires opening the file, which for SAF URIs
  means materializing it first. Never eager-scan a folder listing.
- Engine plumbing already exists and is unused by the UI: `nGetTrackCount`, `nSetTrack`,
  `nIsMultiTrack`, `nGetKssTrackRange`, and `TrackRef.subtrackIndex` flows through
  `startTrackWithFocus` already.
- Subtrack entries must be addable to playlists and the queue (depends on item 1).
- Track keys used for UI state (`NowPlayingFragment` notes/channel keys) already include
  `subtrackIndex` — no change needed there.

## 6. Playlist export as folders

Export = copy actual file bytes into a user-picked SAF folder. The folder *is* the playlist:
re-importing is just browsing it, and natural sort (item 2) restores the order. No playlist
file format.

**Naming**
- `NN - DisplayName.ext` (index prefix, zero-padded to the playlist size's width).
- Subtrack entries: `NN - DisplayName (track M).ext` — the track is named even though the
  shipped file is the full container.

**Subtracks: patch-split**
- For NSF, GBS, and KSS, patch the copied file's header so it reports a single song starting
  at the selected subtrack (NSF: byte 0x06 total songs → 1, byte 0x07 starting song → M;
  GBS: bytes 0x04/0x05 equivalently; KSS: KSSX extended header first/last track fields).
  Result plays as a single-song file in any player — the exported folder is playable as-is on
  a linear listen. Verify exact offsets and checksum/edge cases per format during
  implementation.
- Formats where patching isn't straightforward (SAP, AY, NSFe's chunked format): plain copy,
  keep the "(track M)" filename note, accept lossiness. NSFe chunk rewriting is a possible
  follow-up.

**Artwork**
- Each track's resolved artwork is copied next to it with a matching base name
  (`NN - DisplayName.png`) — the existing `selectArtwork` convention resolves it on re-browse
  with zero new code.
- If every track in the playlist resolves to the same image, write a single `cover.ext`
  instead (the other convention the resolver already understands).
- ZIP-sourced tracks and artwork are extracted (the playback path already knows how).

## 7. Browser filter box

Name filter over the **current folder's already-loaded listing only** — never recursive
(see "not doing").

- **Tap semantics (ratified):** tapping a track while a filter is active queues only the
  visible filtered tracks, starting at the tapped one (with no filter, current behavior
  stands: all playable siblings). The queue is a snapshot at tap time — clearing the filter
  afterward does not retroactively change the queue. Accidental replacement of a working
  queue is covered by the queue-replace undo (item 4).
- Filtering 10k in-memory names is sub-millisecond; the care points are debouncing input and
  using ListAdapter/DiffUtil instead of `notifyDataSetChanged()` for large lists.
- No new memory cost: the browser already loads the full listing into memory.
- Perf note (separate, optional): for 1–10k-entry folders the initial SAF cursor load itself
  is the slow part. If it feels bad in practice, stream entries into the adapter as the cursor
  is read instead of collecting the full list first.

## 8. 7z archive support

Committed, but sized honestly: joshw.info — the largest offline VGM archive source —
distributes almost everything as `.7z`, so this maps the existing ZIP-as-folder UX onto the
majority of real-world rips.

- Requires bundling an LZMA decoder (7-Zip's C SDK is the usual choice; evaluate vs. a
  Kotlin/JNI binding).
- 7z solid archives don't support cheap per-entry random access like ZIP — extracting one
  entry may require decompressing the block containing it. The existing local-cache approach
  (`ZipArchiveStore.localArchive`) already copies the archive locally; consider caching
  extracted entries (or whole solid blocks) rather than re-decompressing per play.
- Same interface as `ZipArchiveStore` (list / copyEntry / withEntryInputStream) so the browser
  and playback paths stay agnostic; unify behind an `ArchiveStore` abstraction.

## 9. UX polish batch (all ratified)

1. **Touch feedback on list rows** — browser/playlist rows are bare TextViews: add
   `selectableItemBackground` ripple (long-press currently gives no feedback at all), and
   convert raw-px padding (`setPadding(20, 24, ...)`) to dp.
2. **Unified long-press context menu** — replace the direct "add to playlist" long-press with
   a menu (bottom sheet or dialog) shared across browser, playlist, and queue rows, showing
   contextually valid actions: Add to queue, Add to playlist, Remove (playlist/queue), etc.
3. **Currently-playing indicator** in browser, playlist detail, and queue lists.
4. **Mini player**: add a thin progress line; idle state should show nothing instead of
   "Unknown Game" (it currently always renders `displayGame`); disable prev/play/next when the
   queue is empty (they silently no-op today).
5. **Toast stacking** — cycling timing/loop/shuffle queues one toast per tap. Cancel the
   previous toast before showing the next, or switch to Snackbar (also: custom toast views via
   `toast.view` are deprecated since API 30).
6. **Notes expansion affordance** — the tappable notes text has no visual hint; add a chevron
   or "more…" indicator.
7. **Unlock portrait** — the manifest hard-locks `screenOrientation="portrait"`; this was not
   an intentional scope cut. Remove the lock and make sure the main screens survive rotation
   (fragment state, analyzer overlay state) and are usable in landscape.

## 10. Settings addition

- **"Clear archive cache" button** — manual escape hatch pairing with the cache-invalidation
  fix (BUGS-2026-07-15.md #18). Shows current cache size, deletes `cacheDir/zip-archives`.
  Extends to cover the 7z cache once item 8 lands.
- **Automatic cache cap (ratified):** fixed 512 MiB LRU cap on the combined ZIP/7z cache;
  archives needed for current playback are pinned from eviction. No user-facing cap setting —
  the Clear button is the only manual control.

## Adjudicated decisions (2026-07-15, codex Q&A round)

- **Delivery:** all bugs + all NEXT_STEPS sections, in three phone-testable checkpoints:
  (1) native/Kotlin correctness sweep + playback stabilization — must include bugs #15 and
  #17, which gate checkpoint 2; (2) opens with the `subtrackIndex` schema change (item 1),
  then persistence, queue workspace, subtracks, browser/playlist UX; (3) export, 7z,
  rotation, final cleanup.
- **Paused notification:** no notification before a track is loaded; on pause,
  `stopForeground(STOP_FOREGROUND_DETACH)` so controls persist indefinitely for
  Bluetooth/lock-screen resume. Couplings: bug #22 (`MediaButtonReceiver.handleIntent` in
  `onStartCommand`) and queue/position persistence (item 3) must land with this, or
  resume-after-the-OS-kills-the-detached-service has holes.
- **Previous button:** conventional behavior — restart the current track when > 3 s in
  (using engine-derived position, post-#17), otherwise move backward; shuffle Previous
  follows a session-scoped listening-history stack (not persisted).
- **Export duplicates:** duplicate playlist entries stay duplicated (a playlist is an ordered
  sequence, not a set); numeric prefixes prevent filename collisions; each duplicate gets its
  own artwork copy so the base-name matching convention holds.
- **Docs as spec:** these date-stamped docs are committed to the repo as the versioned
  specification; codex updates status/checkboxes inline as work lands.

## Deferred / later

- NSFe patch-split export (chunk rewriting).
- Multiple root folders in the browser.
- Streaming directory loads for very large folders (see item 7 perf note) — only if needed.
