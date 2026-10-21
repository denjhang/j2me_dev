VgmBrowserPWM v0.9.1 (2026-10-21)
==================================

 - PWM entry threshold lowered 400Hz -> 60Hz per field direction:
   single 20ms window with >=2 writes (100Hz) enters immediately,
   two consecutive windows with >=1 write (~50-60Hz sustained) also enter.
   Measured on Alla vill till himmelen: amp-write medians are
   ch0=832Hz, ch1=14.7kHz, ch2=111Hz - all far beyond MIDI time
   resolution, confirming the aggressive threshold.
   Below ~30Hz writes are musical tremolo and stay as events.

All else same as v0.9.0 (events -53%..-83% on reference tracks).
