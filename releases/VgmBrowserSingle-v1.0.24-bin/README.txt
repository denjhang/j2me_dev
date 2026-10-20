VgmBrowserSingle v1.0.24 (2026-10-21)
=====================================

 - Fix frozen keyboard visualization on Ucka (and any song where the
   pending chunk is cut while the current chunk replays): a single
   register pool was shared, so draining the pending chunk overwrote
   the array the player was still replaying. Late timestamps injected
   ahead of the replay cursor froze the scan loop while audio (playing
   independently) continued. Current chunk and pending chunk now use
   separate pools.
 - Verified headless on PC (DbUcka harness): full produce+replay of
   Ucka = 224ms, worst frame 2ms, no exceptions.

All else same as v1.0.23 (512k default, buzzer misdetection fix).
