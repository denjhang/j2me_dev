VgmBrowser v1.1.1 - AY-3-8910/YM2149 VGM Player for Nokia J2ME (MIDP 2.0)
========================================================================

FIXED
-----
- Settings reset on track switch (volMode/polyLimit not persisted before new PlayScreen)

FEATURES (v1.1.0)
-----------------
- Max poly limit: 16/32/64/128 (default 64, panic on overflow)
- Strict mono: one note per chip per MIDI channel (square channels only; buzzer stacks intact)
- Vol mode: CC7 / CC7+vel / CC7+CC11+vel (default triple guarantee for Nokia CC7-ignore quirk)

Install: transfer .jar + .jad to phone, open .jad
