VgmBrowser v1.1.2 - AY-3-8910/YM2149 VGM Player for Nokia J2ME (MIDP 2.0)
========================================================================

FIXED (v1.1.2)
--------------
- Vol mode logic corrected:
  * Mode 0 "API" = v1.0.28 behavior (setChannelVolume API fallback CC7, velocity=127)
  * Mode 1 "CC7" = CC7=velocity per note-on, velocity=127
  * Mode 2 "CC7+CC11+vel" = triple guarantee (CC7+CC11+velocity all set)
- Default changed to mode 0 (best compatibility, restores old sound)

FIXED (v1.1.1)
--------------
- Settings reset on track switch (persist() now called before new PlayScreen)

FEATURES (v1.1.0)
-----------------
- Max poly limit: 16/32/64/128 (default 64, CC123 panic on overflow)
- Strict mono: one note per chip per MIDI channel (square only; buzzer stacks intact)
- Vol mode setting (see v1.1.2 fix above)

Install: transfer .jar + .jad to phone, open .jad
