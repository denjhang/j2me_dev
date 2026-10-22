VgmBrowser v1.0.34 - AY-3-8910/YM2149 VGM Player for Nokia J2ME (MIDP 2.0)
========================================================================

NEW IN v1.0.34
--------------
1. Max poly limit setting: 16 / 32 / 64 / 128 (default 64)
   - Global active-note ledger; overflow fires CC123 panic on all 16 channels
   - Self-heals note leaks when the phone screen dims and note-offs get lost
2. SID-voice PWM merged (from pwm branch v0.9.2, square channels only)
   - Detects fast R8-R10 rewrites (>=75-100Hz): flattens per-write events,
     emits 20ms windowed mean level, maps duty bucket to SynthLead timbre 82/80/84/81
   - Settings: Routing > "SID voice PWM" On/Off (default On)
   - Buzzer channels are excluded from PWM (independent semantics preserved)
   - Verified: Bullet Sequence / Alla vill till himmelen / Thunderdome 90-99% suppression;
     normal tracks 0% (no false positives)
3. Loop semantics fixed (libvgm-style)
   - Looping VGM: plays full + (N-1) x loop body, then auto-advances to next track
   - Non-looping VGM: repeats the whole track N times (previously played once)
   - Total time in the HUD/progress bar now equals actual playback length
     (measured pass + measured loop body), so the bar no longer overflows
   - Bad loop points (rip anomalies where loopOffset sits at the file end and the
     loop body is <2% of the track) are treated as non-looping instead of
     repeating 0.7s forever
4. Buzzer polyphony follows the unison setting: stack size = Saw/Tri unison + 1;
   the mono rule applies only to the three square channels

SETTINGS (v1.0.33+ grouped layout)
----------------------------------
  -- Playback --            Master vol / Engine / Gen size / Loop count / Play order
  -- Level balance --       Square vol / Noise vol / Envelope vol / Buzzer vol
  -- Volume behavior --     Note-on vol / Vol follow / Max poly
  -- Pitch --               Octave / Bend sens / Bend range
  -- Envelope voices --     Env saw octave / Env tri octave / Saw unison / Tri unison
  -- Routing --             Stereo / Noise map / SID voice PWM
  -- Instruments (per AY voice) --  8 rows, full GM 0-127, shows "number name"

DEFAULTS (Nokia-optimized)
--------------------------
  Gen size 2M, Square 70%, Noise 80%, Envelope 90%, Buzzer 100,
  Note-on vol CC7, Vol follow retrig, Max poly 64,
  Saw unison +2, Tri unison +2, Env-Tri 81, SID voice PWM On

VERSION GUIDANCE
----------------
- OLD versions (<= v1.0.31): best on PC KEmulator (single-path setChannelVolume)
- NEW versions (v1.0.32+): thoroughly solves Nokia real-device issues
- v1.1.x series is ARCHIVED/BROKEN (releases/_archived_failed_v1.1.x/)

Install: transfer .jar + .jad to phone, open .jad
