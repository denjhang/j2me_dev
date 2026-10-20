VgmBrowserSingle v1.0.28 (2026-10-21)
=====================================

 - Fix settings reverting to defaults on every track switch:
   persist() hard-coded record version 1 while load() now requires 2
   (bumped in v1.0.27), so every saved record was rejected on the next
   load. Record version now comes from the same constant on both sides.

All else same as v1.0.27 (best-compatibility sound chain).
