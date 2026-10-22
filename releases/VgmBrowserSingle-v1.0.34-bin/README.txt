VgmBrowser v1.0.34 - AY-3-8910/YM2149 VGM Player for Nokia J2ME (MIDP 2.0)
========================================================================

NEW IN v1.0.34
--------------
- Progress bar calculation fixed for looped tracks (libvgm-style dynamic length)
- Before: progress bar would exceed 100% on second loop iteration
- After: total length = totalSamples + loopPlays × loopSamples (grows with each loop)
- Loop playback itself unchanged (remains seamless)

NEW IN v1.0.33
--------------
- Grouped settings screen with section headers:
  Playback / Level balance / Volume behavior / Pitch / Envelope voices / Routing / Instruments
  (headers not selectable; cursor skips them)
- Instruments: full GM 0-127 range selectable (left/right), shows "number name"
- New Nokia-optimized defaults (RMS version bump to 3):
  Gen size 2M, Square 70%, Noise 80%, Envelope 90%, Buzzer 100,
  Note-on vol CC7, Vol follow retrig, Saw unison +2, Tri unison +2, Env-Tri 81

NEW IN v1.0.32
--------------
- Dual volume settings (independent control):
  * Note-on vol: How to set volume on first note-on
    - API (default): setChannelVolume API, fallback CC7, velocity=127 (v1.0.31 behavior)
    - CC7: Send CC7=level before note-on, velocity=127
    - CC7+vel: CC7=level + velocity=level
    - CC7+CC11+vel: Triple guarantee (CC7+CC11+velocity all set)
  * Vol follow: How to update volume during note
    - retrig: Retrigger note with new velocity
    - chop: Split note (note-off + note-on)
    - CC7 (default): Send CC7 message
    - off: No follow
- Buzzer isolation: Buzzer always uses Buzzer vol setting, unaffected by Note-on vol/Vol follow
- Max poly limit: 16 / 32 / 64 / 128 (default 64)
  - Global active-note ledger; overflow fires CC123 panic on all 16 channels
  - Self-heals note leaks when the phone screen dims and note-offs get lost

SETTINGS
--------
  -- Playback --             Loop / Gen size / Master vol
  -- Level balance --        Square vol / Noise vol / Envelope vol / Buzzer vol
  -- Volume behavior --      Note-on vol / Vol follow / Max poly
  -- Pitch --                Octave / Bend sens / Bend range
  -- Envelope voices --      Env saw octave / Env tri octave / Saw unison / Tri unison
  -- Routing --              Stereo / Noise map
  -- Instruments (per AY voice) --  8 rows, full GM 0-127, shows "number name"

DEFAULTS (Nokia-optimized)
--------------------------
  Gen size 2M, Square 70%, Noise 80%, Envelope 90%, Buzzer 100,
  Note-on vol CC7, Vol follow retrig, Max poly 64,
  Saw unison +2, Tri unison +2, Env-Tri 81

VERSION GUIDANCE
----------------
- OLD versions (<= v1.0.31): best on PC KEmulator (single-path setChannelVolume)
- NEW versions (v1.0.32+): thoroughly solves Nokia real-device issues
- v1.1.x series is ARCHIVED/BROKEN (releases/_archived_failed_v1.1.x/)

Install: transfer .jar + .jad to phone, open .jad
