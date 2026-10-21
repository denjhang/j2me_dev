VgmBrowserToneRT v1.0.1 (2026-10-22) — true realtime
===================================================

v1.0.0 shipped the window engine but PlayScreen still drove the old
SMF chunk pipeline, so playback "filled" first and memory grew into
the hundreds of KB (default 512k, up to 2M). This release makes the
player actually realtime, the way ToneEngineProbe works.

What changed
------------
- PlayScreen no longer records a chunk stream. The parser is advanced
  against the wall clock and each AY register write goes straight into
  the live engine (ay.write / ay.tick). Sound starts on the first
  window (~250ms after pressing play); there is nothing to fill.
- Removed the whole SMF chunk pipeline from the player: chunk cut,
  SMF rebuild, prep-Player thread, reg pools, lazy trim. The JAR is
  ~6KB smaller and the heap stays flat.
- No shadow state machine (gen/gen2) anymore: ay itself is the full
  register state used for the keyboard visualization, so every event
  is processed once, not twice.
- Buffer setting is now just 4k / 8k (window queue depth), default 4k
  — this is for S40-class heaps. The 16k-2M SMF presets are gone.
- Window thread tolerance: if a window boundary is missed (GC pause,
  scheduler), it realigns to the current time instead of dropping the
  window. Fully silent passages park the players instead of
  re-articulating silence 4x/second.
- Pause mutes and freezes the parser; resume re-anchors the wall clock
  so it does not race to catch up.

HUD: "pos Ns rt" plus window health "win N bad M". Memory line should
stay flat while playing.

Verified on PC: window encoder byte tests 4/4, plus a 100-window
realtime feed simulation (0 bad windows, 9 bytes/window average,
event ring cleared every window). Device listening test still pending
on your E5.

Known: 250ms re-articulation seam on long notes (next tuning step),
noise channels silent (no 4th player yet).
