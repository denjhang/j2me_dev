VgmBrowserPWM v0.9.3 (2026-10-21)
==================================

 - Sync buzzer extended to REPEATING envelope shapes (8-15) when the
   envelope cycle exceeds the R13 restart interval (envelope cut off ->
   audible pitch = restart rate). quest8: shapes 10/14 @329Hz restarts,
   env cycle 3.6-186ms, all cut - pitch now tracks the restart rate.
 - Retrigger throttled to actual pitch changes (was 1.3M note-ons on
   the 24-min track, now 6.4k).
 - Per architecture decision this stays in the SNDH/PWM branch:
   mainline (Nokia target) keeps the conservative one-shot-shape rule.

All else same as v0.9.2.
