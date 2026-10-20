VgmBrowserSingle v1.0.25 (2026-10-21)
=====================================

 - Fix "fast envelope" channels nearly inaudible (Radiohead Creep style):
   tone+env channels with envelope wave rate >= 12Hz are perceptually a
   steady-average tremolo; step-by-step simulation could not keep up
   (64-step catch-up guard) and collapsed the level near zero.
   They now use a fixed average level (96/127), and per-frame volume
   pushes are skipped for them (also kills the event storm).
   Verified headless: CC11 avg 83, CC7 avg 92-93, no near-zero events.

All else same as v1.0.24.
