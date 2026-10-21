VgmBrowserSingle v1.0.32 (2026-10-21)
=====================================

 - Sync buzzer now also engages for REPEATING envelope shapes (8-15)
   when the envelope cycle is longer than the R13 restart interval
   (envelope gets cut off -> audible pitch = restart rate).
   quest8 measured: shapes 10/14 @329Hz restarts, env cycle 3.6-186ms
   all cut; env period (4-279Hz) is only a shape parameter there.
 - Sync-buzz retriggers now fire only on actual pitch change
   (was: full 3-voice stack on every R13 write = 1.3M note-ons;
   now 6.4k for the 24-minute track).
 - Sync-buzz level aligned to PC version (raw buzzVol).

All else same as v1.0.30 (MONO default).
