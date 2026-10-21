VgmBrowserSingle v1.0.29 (2026-10-21)
=====================================

 - Fix texture louder than melody (d-fr4gmEnt style): buzzer octave
   unison stacks N voices whose combined loudness ~N x a single voice,
   drowning the single-voice melody. Per-voice velocity now scales by
   1/sqrt(N) (100%/71%/58% for 1/2/3 voices) - thickness kept, total
   loudness aligned with a single voice. Channel volume mapping itself
   verified correct (melody CC7 avg is the highest of all channels).

All else same as v1.0.28.
