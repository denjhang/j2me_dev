VgmBrowserSingle v1.0.30 (2026-10-21)
=====================================

 - Reverted v1.0.29 buzzer voice-gain compensation (wrong diagnosis).
 - Default Stereo mode = MONO for Nokia: phone mono speaker attenuates
   hard-panned side channels, burying the melody (square channels).
   Square volume/CC7 mapping verified correct (per-channel CC7 averages
   track the hardware amplitude registers). Persisted settings still
   override - clear settings once after install.

All else same as v1.0.28.
