VgmBrowser v1.1.4 (bin)

nescube-compatible audio engine (channel count unchanged: 7 per chip, 16 MIDI channels total):
- Init order: createPlayer -> realize -> prefetch -> getControl(full class name,
  short-name fallback) -> start  (covers MeBoy and nescube orders)
- Volume model: note-on velocity now carries the PSG level (nescube style),
  CC7/CC11 still sent in parallel for devices that support them
- Velocity re-trigger on register-driven level changes (>= 16 delta)
- Program changes per channel kept as before (10 instrument slots in Settings)
