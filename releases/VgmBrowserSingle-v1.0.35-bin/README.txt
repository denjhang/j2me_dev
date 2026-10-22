VgmBrowserSingle v1.0.35
========================

HIGHLIGHTS
----------
- Complete v1.0.33 features recovery with visual enhancements
- Grouped settings UI with 7 bold, highlighted section headers
- Full GM 0-127 instrument selection with instrument names
- Nokia-optimized defaults (RMS v3)

NEW IN v1.0.35
--------------
- Settings UI reorganized into 7 logical sections:
  * Playback (Master vol, Engine, Gen size, Loop, Play order)
  * Level balance (Square, Noise, Envelope, Buzzer volumes)
  * Volume behavior (Note-on vol, Vol follow, Max poly)
  * Pitch (Octave, Bend sens, Bend range)
  * Envelope voices (Saw/Tri octave & unison settings)
  * Routing (Stereo, Noise map)
  * Instruments (8 programmable channels)

- Section headers are bold and bright white for clear visual distinction
- Headers are non-selectable; cursor automatically skips them

- Instrument selection expanded to full GM 0-127 range
- Display format: "number name" (e.g. "80 Lead 1 (square)")
- Complete General MIDI instrument names included

- New Nokia-optimized defaults (RMS version bump to 3):
  Gen size 2M, Square 70%, Noise 80%, Envelope 90%, Buzzer 100,
  Note-on vol CC7, Vol follow retrig, Saw unison +2, Tri unison +2

NEW IN v1.0.34
--------------
- LRU voice replacement: Instead of silencing all voices when poly limit reached,
  now intelligently replaces the least-recently-used note
- Fixes click/pop artifacts during polyphony overflow
- Empty file header safety check (prevents null pointer crashes)

NEW IN v1.0.32
--------------
- Dual volume settings (independent control):
  * Note-on vol: How to set volume on first note-on
    - API (default): setChannelVolume API, fallback CC7, velocity=127 (v1.0.31 behavior)
    - CC7: Send CC7=level before note-on, velocity=127
    - CC7+vel: CC7=level + velocity=level
    - CC7+CC11+vel: Triple guardrail (Nokia 3110c/5300 fix)
  * Vol follow: How to track live R10 changes
    - retrig: note-off + on with new level (solid on Nokia)
    - CC7: send CC7 only (KEmulator best, Nokia may ignore)

- Independent percent volume sliders for Square/Noise/Envelope groups
  (Replace old unified "AY vol" with precise per-group balance)

- 8 envelope/buzzer unison modes:
  * Saw: Off / +1 oct (2v) / +2 oct (3v)
  * Tri: Off / +1 oct (2v) / +2 oct (3v)
  (Fixes thin sine-like envelope sound, now rich & powerful)

VERSION GUIDANCE
----------------
- OLD versions (<= v1.0.31): best on PC KEmulator (single-path setChannelVolume)
- NEW versions (v1.0.32+): thoroughly solves Nokia real-device issues
- v1.1.x series is ARCHIVED/BROKEN (releases/_archived_failed_v1.1.x/)

Install: transfer .jar + .jad to phone, open .jad

CONTROLS
--------
In browser:
  Up/Down     - Navigate files
  Left/Right  - Page up/down
  5 (Fire)    - Play selected file
  0           - Open folder/drive selector
  *           - Toggle HUD/info display
  #           - Settings menu

In player (while playing):
  Left/Right  - Previous/next track
  Up/Down     - Volume up/down
  *           - Toggle HUD/info display
  #           - Settings menu
  0           - Stop playback

Settings:
  Up/Down     - Navigate options (skips section headers)
  Left/Right  - Change value
  #           - Back to player

FEATURES
--------
- AY-3-8910 VGM playback via MIDI synthesis
- 7-channel mapping: 3 squares + 3 noises + envelope/buzzer
- Configurable instrument programs (0-127 GM)
- Real-time loop count, octave shift, volume balance
- Pitch bend sensitivity and range adjustment
- Stereo panning modes (ABC, ACB, Mono, etc.)
- File browser with folder navigation
- Persistent settings (RMS)
- Progress bar with time display
- Works on Nokia S40/S60, Sony Ericsson, and other MIDP-2.0 devices

TECHNICAL
---------
- MIDP-2.0 / CLDC-1.1
- JSR-135 Mobile Media API
- JSR-75 FileConnection (optional, for file browser)
- Dual MIDI engine: MIDIControl primary, ToneControl fallback
- ProGuard optimized: ~98KB JAR

COMPATIBILITY
-------------
Tested on:
- KEmulator (nnmod) - excellent
- Nokia 6300/5300/3110c - good (use CC7 modes)
- Sony Ericsson K series - good

Known issues:
- Some Nokia devices need CC7 or CC7+CC11+vel mode for volume control
- Envelope channels may sound thin on weak synths (enable unison)
- Very old devices (<MIDP-2.0) not supported

LICENSE
-------
See source repository for license information.

CREDITS
-------
Original VGM format by Maxim
AY-3-8910 to MIDI translation engine by Denjhang
Based on yasp11 ay_to_opm.c reference implementation

LINKS
-----
Repository: https://github.com/denjhang/j2me_dev
VGM format: https://vgmrips.net/wiki/VGM_Specification
