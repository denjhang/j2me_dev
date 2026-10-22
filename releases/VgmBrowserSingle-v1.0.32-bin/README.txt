VgmBrowser v1.0.32 - AY-3-8910/YM2149 VGM Player for Nokia J2ME (MIDP 2.0)
========================================================================

NEW (v1.0.32)
-------------
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

Default = API + CC7 (identical to v1.0.31 behavior)

Install: transfer .jar + .jad to phone, open .jad
