VgmBrowserSingle v1.0.16 (2026-10-21)
=====================================

 - Level gauge now caps at watermark: fills 0 -> max, stays pinned at
   max during playback, only resets on track switch (was sawtooth per chunk)
 - Square vol now audible: synth ignores CC11, velocity retrigger
   threshold lowered 16 -> 4 so 10% steps trigger
 - Envelope vol now actually scales output (was wired to nothing):
   buzzer/envelope level = buzzVol * envVolPct
 - Noise vol range extended to 300%

All else same as v1.0.15. Works perfectly on E5 (S60).
