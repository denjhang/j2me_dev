VgmBrowserToneRT v0.9.0 (2026-10-21) — ToneControl realtime engine BRANCH
=========================================================================

Branch from VgmBrowserSingle v1.0.21. Goal: replace per-chunk SMF
players with the E5-verified realtime primitive:
  per-channel resident tone Players,
  250ms windows, stop -> deallocate -> setSequence -> start.

Current state: SMF stream engine carried over unchanged (branch point).
Realtime engine integration happens on this branch only.

See tools/tonert/ToneRTProbe.jar for the validated primitives
(ToneEngineProbe MIDlet = working prototype, E5 field-tested).
