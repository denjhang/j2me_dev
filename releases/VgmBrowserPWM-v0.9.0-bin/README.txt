VgmBrowserPWM v0.9.0 (2026-10-21) — SID-voice PWM branch
========================================================

Branch from VgmBrowserSingle v1.0.28. Adds YM2149 software-PWM
(SID-voice, Atari ST style) detection and smoothing:

 - Detection: per-channel 20ms rolling window on R8-R10 writes;
   >=400Hz enters PWM mode (hysteresis: 2 idle windows to exit)
 - Smoothing: per-write amp events dropped in PWM mode; windowed
   mean (0-15 -> 0-127) emitted every 20ms through the normal
   level chain (CC11 + volFollow CC7)
 - Duty-cycle -> timbre: mean buckets 0-3/4-7/8-11/12-15 map to
   SynthLead patches 82/80/84/81 (dark-thin -> full-bright),
   note restarted on patch change; slot square patch restored on exit
 - Event reduction vs mainline (whole-song):
     Bullet Sequence 1,693,178 -> 282,486  (-83%)
     Alla vill        560,795 -> 266,444  (-53%)
     Thunderdome    1,396,458 -> 525,992  (-62%)

Research notes: docs/SID_VOICE_PWM.md. Mainline (v1.0.28) unchanged.
