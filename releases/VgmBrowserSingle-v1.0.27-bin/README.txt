VgmBrowserSingle v1.0.27 (2026-10-21)
=====================================

 - Buzzer chain aligned to the PC version: buzzer output level is raw
   buzzVol again (no envVolPct scaling) - CC11 and note velocity.
   All other changes (noise merge, vol pcts, fast-env, volFollow)
   are kept.
 - Settings record version bumped to 2: old 10-slot-era records are
   discarded on load (slot meanings changed; stale records gave buzzer
   shapes noise instruments - the "thin buzzer" culprit). First run
   after install falls back to clean defaults.

All else same as v1.0.26.
