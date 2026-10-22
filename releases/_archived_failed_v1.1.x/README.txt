ARCHIVED: Failed v1.1.x Experimental Releases
==============================================

These versions have buggy volume control logic and are superseded by v1.0.32.

v1.1.0 (2026-10-21) - FAILED
- Attempted: volMode setting (CC7/CC7+vel/CC7+CC11+vel)
- Bug: All three modes executed same logic, always sent CC7
- Bug: Mode 0 used vel=100 constant, flattened dynamics

v1.1.1 (2026-10-21) - FAILED
- Fixed: Settings reset on track switch (persist() before switchTrack)
- Still broken: volMode logic unchanged from v1.1.0

v1.1.2 (2026-10-21) - FAILED
- Attempted: Fix volMode three branches
- Bug: Still confusing, modes interfered with each other
- User reported: "调整后只是强制三个同时"

RESOLUTION
----------
v1.1.x series abandoned and stashed (git stash).
Rolled back to v1.0.31, redesigned as v1.0.32 with dual independent settings:
- Note-on vol (how to set volume on first note-on)
- Vol follow (how to update volume during note)

USE v1.0.32 INSTEAD.
