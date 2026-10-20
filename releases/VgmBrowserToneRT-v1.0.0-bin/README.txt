VgmBrowserToneRT v1.0.0 (2026-10-21) — ToneRT window engine
============================================================

First integrated realtime release, based on VgmBrowserSingle v1.0.28
(best-compatibility sound chain: buzzer aligned to PC version, raw
buzzVol, fast-env avg level, settings v2).

The wish-polling tone fallback is replaced by the E5-verified
ToneEngineProbe pipeline, now driven by real VGM data:

 - Producer: Ay2Midi register events -> per-channel (time, note,
   volume) event ring, deduped at record time (48 events/channel/
   window cap, <256B budget).
 - Window thread: every 250ms RLE-encodes each channel into a
   ToneControl sequence (TEMPO 127 / RESOLUTION 127, 68 units per
   window, SET_VOLUME pairs for envelope levels), then switches all
   three resident Players via the T6 law:
     stop -> deallocate -> setSequence -> start
 - Buzzer: in ToneRT mode the buzzer voice is routed to its source
   tone channel, pitch = envelope frequency (handoff §8), level =
   raw buzzVol (PC-aligned, unscaled). Sync buzzer (fast R13 retrigger)
   routed the same way to the first envelope channel.
 - Zero buffering: no SMF, no Player-per-chunk, ~100B of sequence
   data per window instead of megabyte-scale chunk memory.

HUD shows "ToneRT win N bad M" (windows switched OK / failed).

Verified: byte-level unit tests for the window encoder
(tools/tonert/test/WinEncTest.java, 4/4 pass). Device test pending:
E5/S40 acceptance steps 1-2 per docs/TONERT_HANDOFF.md §6.

Known limitations (acceptance step 3+): per-window re-articulation
seam audible on sustained notes; noise channels silent (no 4th
player yet); pitch bend quantized to semitones.
