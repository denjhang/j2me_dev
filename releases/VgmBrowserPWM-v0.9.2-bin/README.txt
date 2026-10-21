VgmBrowserPWM v0.9.2 (2026-10-21)
==================================

 - PWM entry threshold tightened: single 20ms window >=2 writes
   (100Hz) OR two consecutive windows totaling >=3 writes (~75Hz).
   50Hz frame-rate volume updates (musical slides/crescendo, present
   in the SNDH batch: sndh44, quest8 ch0/1, Tetris ch1/2) stay as
   events - verified 0% false suppression on sndh44.
 - SNDH 2023.11.27 folder batch-scanned (17 tracks): PWM channels
   span 111Hz-22kHz; no DAC-style (all-muted + sample-rate amp)
   usage found in this set.

All else same as v0.9.1.
