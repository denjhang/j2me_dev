VgmBrowserSingle v1.0.26 (2026-10-21)
=====================================

 - REVERTED the v1.0.22 buzzer mixer condition: hardware reality is that
   square+buzzer mixed (tone bit on + envelope on) switches timbre and
   must still be treated as buzzer (multi-waveform + octave stacking).
   isBuzzer is back to: envMode && envWaveHz >= 20.
 - Kept: DEFAULT_PROG slot realignment (4-7 = saw 81 / decay 87 /
   tri 82 / alt 80), fast-envelope average level (v1.0.25).

All else same as v1.0.25.
