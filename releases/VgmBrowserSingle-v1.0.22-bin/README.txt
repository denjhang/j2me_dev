VgmBrowserSingle v1.0.22 (2026-10-21)
=====================================

 - Fix buzzer misdetection: "tone+envelope" channels (melody on the tone
   period, envelope only shaping amplitude - Ucka / Unfixed Reamplified
   Vibes style) were treated as buzzers because their triangle envelope
   ran above 20Hz. The tone channel got muted and the melody collapsed
   into low thumps. isBuzzer now additionally requires the tone mixer
   bit OFF (classic buzzer = tone off + env on, pitch = env frequency).

All else same as v1.0.21 (finalized defaults rev.2).
