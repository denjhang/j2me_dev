VgmBrowserSingle v1.0.19 (2026-10-21)
=====================================

 - New setting "Vol follow" (in channel balance group): how in-note
   volume changes are expressed, because Nokia ignores CC11 during
   long notes:
     retrig - note-on velocity retrigger (delta>=4, was the old behavior)
     chop   - note split: off+on with new velocity per change (accurate decays)
     CC7    - channel volume during note (some Nokia synth models honor it)
     off    - no following (fewest events)
 - Setting persisted at v[27]; default retrig.
 - Envelope-mode channels now honor sqVolPct/noiseVolPct (v1.0.18).

All else same as v1.0.18. Works perfectly on E5 (S60).
