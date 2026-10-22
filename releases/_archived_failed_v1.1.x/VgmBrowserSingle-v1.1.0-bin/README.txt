VgmBrowserSingle v1.1.0 (2026-10-21)
=====================================

 - New setting "Max poly" (16/32/64/128, default 64): global active-note
   ledger; exceeding the limit fires an all-channels CC123 panic and
   resets - self-healing guard for note leaks (e.g. the screen-dim
   moment when the phone stalls threads mid note-on)
 - Strict mono on square channels: one note per MIDI channel at a time
   (AY hardware semantics), forced note-off before any new note-on.
   Buzzer octave stacks are intentional polyphony - they count toward
   the ledger and the panic guard instead
 - New setting "Vol mode" (CC7 / CC7+vel / CC7+CC11+vel, default
   CC7+CC11+vel): multi-guarantee volume since Nokia may ignore CC7 on
   some notes - each note-on now carries CC7 (+CC11) matching its level
 - Both settings persisted (v[28]/v[29])

All else same as v1.0.31 (MONO default, buzzer aligned to PC version).
