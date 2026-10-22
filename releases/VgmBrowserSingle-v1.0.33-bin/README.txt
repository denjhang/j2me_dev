VgmBrowser v1.0.33 - AY-3-8910/YM2149 VGM Player for Nokia J2ME (MIDP 2.0)
========================================================================

NEW IN v1.0.33
--------------
- Grouped settings screen with section headers:
  Playback / Level balance / Volume behavior / Pitch / Envelope voices / Routing / Instruments
  (headers not selectable; cursor skips them)
- Instruments: full GM 0-127 range selectable (left/right), shows "number name"
- New Nokia-optimized defaults (RMS version bump to 3):
  Gen size 2M, Square 70%, Noise 80%, Envelope 90%, Buzzer 100,
  Note-on vol CC7, Vol follow retrig, Saw unison +2, Tri unison +2, Env-Tri 81

VOLUME SETTINGS (v1.0.32+)
--------------------------
- Note-on vol: API (old KM behavior) / CC7 / CC7+vel / CC7+CC11+vel
- Vol follow: retrig / chop / CC7 / off
- Buzzer is isolated: always uses Buzzer vol (CC11), unaffected by the above two.

VERSION GUIDANCE
----------------
- OLD versions (<= v1.0.31): best on PC KEmulator (single-path setChannelVolume, velocity 127)
- NEW versions (v1.0.32+): thoroughly solves Nokia real-device issues.
- v1.1.x series is ARCHIVED/BROKEN (releases/_archived_failed_v1.1.x/) - do not use.

Install: transfer .jar + .jad to phone, open .jad
