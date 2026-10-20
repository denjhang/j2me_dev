# VGM Browser (VgmBrowser)

J2ME VGM player with real-time AY8910 register-stream to MIDI conversion and pixel-art 88-key visualization.

**Current release: v1.1.5** — <https://github.com/denjhang/j2me_dev/releases>

## Changelog (v1.1.1 – v1.1.5)

- **v1.1.1**: Nokia no-sound fix — MIDIControl obtained after `player.start()` (MeBoy order)
- **v1.1.2**: file browser fixes — silent preset-path probe (no `open connection failed -5` at startup), valid parent URLs with trailing slash (no `URL is not valid`)
- **v1.1.3**: `getControl` full class name with short-name fallback (nescube-style)
- **v1.1.4**: nescube audio model — note-on velocity carries the PSG level, CC7/CC11 sent in parallel, velocity re-trigger on level changes
- **v1.1.5**:
  - GD3/info table scrollable (**up/down keys**, 24 px steps) with scrollbar — only affects the table, never the keyboard/progress bar
  - Info table: TAG/INFO header row removed; top rows are chip/clock/VGM version
  - Master volume moved into Settings (up/down keys freed for info scrolling)
  - Bend split into two parameters: **Bend sens** (precision, x0.25–x4.00, 0.01 steps, default x1.0) and **Bend range** (range ratio, default 100%, ±5% steps)
  - Pitch bend conversion: full 64-bit fixed-point path, no integer-cent truncation (~0.02 cent resolution)
  - All settings persisted via RMS (`SettingsStore`), restored on track open
  - File list left softkey menu restored: Open / Now playing, Refresh, Square wave test, About
  - About screen: English text + CN/EN signature images; app icon (`/icon.png`) in jar
  - All UI strings English

## Features

### Playback
- **VGM/VGZ file browser** with directory navigation (JSR 75 FileConnection)
- **Dual-chip support** (AY8910 × 2, up to 6 voices + 2 envelope channels)
- **Loop control**: 1/2/3/5/infinite loop count, sequential or shuffle playback
- **Background play**: switch to file list without stopping; "Now playing" command returns to player
- **VGZ decompression progress**: the player's own progress bar doubles as the DEFLATE progress bar (right label shows "Decompressing N%"), UI stays fully responsive, minimum 300 ms animation

### Real-time AY8910 → MIDI Conversion
- **7-channel mapping** per chip:
  - Ch 0/1/2: Square waves A/B/C → MIDI ch 0/1/2 (program 80 Square Lead)
  - Ch 3/4/5: Noise A/B/C → MIDI ch 3/4/5 (program 127, independent stereo pan)
  - Ch 6: Envelope/buzzer → MIDI ch 6 (program 83 Sawtooth)
- **Smart buzzer detection**: envelope waveform frequency `clock/(16 × env_period × shape_steps)` ≥ 20 Hz routes to the dedicated buzzer channel with portamento; one-shot shapes (R13 ≤ 7) only count when re-triggered rapidly (sync buzzer); slow envelopes fall back to CC11 expression volume simulation on the square voice
- **Synchronized unison**: slow saw/triangle envelope triggers N-voice (+1 octave each) polyphony on square channels
- **Pitch bend**: RPN 0,0 bend range with menu-adjustable sensitivity (0.5×–2×, default 1.5×), 14-bit pitch wheel, 4096 steps/semitone
- **Accurate pitch**: fixed-point log2 lookup (256 entries, ~7 cent precision), no floating-point dependency

### Live Adjustable Parameters (Settings overlay)
- **Stereo modes**: ABC / ACB / BAC / BCA / CAB / CBA / MONO
- **Octave shift**: -3 to +3 (per-channel, 12-semitone steps)
- **Noise mapping**: C4 center (C3-C5) / C4 top (C2-C4) / 88 keys spread
- **Envelope octave**: saw/tri independent octave shift (-2 to +2)
- **Unison modes**: off / +1 oct (2v) / +2 oct (3v) for saw/tri envelopes
- **Buzzer volume**: 0-127 (default 100)
- **Bend sens**: x0.25–x4.00 in 0.01 steps (default x1.0), pitch-bend micro-resolution
- **Bend range**: range ratio 1–400% (default 100%), scales bend travel proportionally
- **Loop count**: 1 / 2 / 3 / 5 / infinite (default 2)
- **Play order**: sequential / shuffle
- **10 instrument slots**: per-channel GM program selection
- **Master volume**: real-time control (arrow keys)

### Pixel-Art 88-Key Visualization (v16-style, layered rendering)
- **Three horizontal piano strips** (one per voice), each showing:
  - 52 white keys + 36 black key notches (A0–C8 range)
  - Active note highlights with **v16-style level shading** (45% base color + 55% white × level; smooth follower integrator)
  - Noise keys (v16 formula: `clock/(512×period)` places R6 noise in keyboard range)
  - Envelope keys (at the buzzer fundamental, lights on the source channel's strip)
  - **T/N/E gate lamps** (right side): Tone/Noise/Envelope gate bits, lit only when actually sounding (not just register-on), level-shaded
- **Dual-chip coloring**: chip1 = green/blue/red, chip2 = purple/yellow/cyan, noise = slate/rose, envelope = orange/amber
- **Baked static layers**: keyboard base (keys/notches/tags/off-state chars) rendered once per track into offscreen Images; per-frame only active highlights + lit chars (~20 fillRects vs. ~300 before)
- **50ms frame budget** (20fps target); marquee wall-clock driven (25px/s travel, 1.5s hold at ends)

### UI/UX
- **Title marquee**: long titles scroll left-right ping-pong (wall-clock driven, independent of repaint rate)
- **Full GD3 tag table**: all 9 text fields (localized + English pairs for title/game/artist, plus date/ripper/notes) with word-wrap, displayed below the keyboard strips
- **Deterministic softkeys** (KEmulator layout rules):
  - File list: left = Now playing (while playing) / Open (idle), right = Up
  - Player HUD: left = Settings, right = List
  - Settings overlay: left = Back (swaps on enter/exit), right = List
- **English UI** throughout (no tofu boxes on KEmulator bitmap fonts)
- **Sorted file list**: directories first → .vgz → .vgm, alphabetical within each group

## Technical Details

### Pitch Calculation
- Formula: `note = 69 + 12 × log2(freq / 440)`
- Clock: AY8910 runs at 1.75MHz (or header-specified clock)
- Frequency: `clock / (16 × period)` where period = TP_A/B/C register (12-bit)
- Fixed-point log2 lookup table (256 entries, Python precomputed) for CLDC 1.1 compatibility (no `Math.log`)

### Buzzer Detection
Envelope shape classes: saw/alternate (R13 ∈ {8,9,11,12,13,15}) = 32 steps, triangle ({10,14}) = 64 steps, one-shot (≤ 7) = no continuous waveform. Envelope waveform frequency = `clock / (16 × env_period × shape_steps)`:
- **≥ 20 Hz** → buzzer branch: dedicated MIDI channel, frequency from the envelope itself (32/64-step difference is an intrinsic octave), portamento for slides, note-off before retrigger (fixes stuck notes on shared buzzer channels)
- **One-shot retriggered rapidly** → sync buzzer, same routing
- **Slow** → software envelope simulation: CC11 expression follows the emu2149 envelope state machine on the square voice

## Nokia Device Compatibility

Verified installable on Nokia S40/S60 (E5-class). Packaging requirements handled by `build.sh`:
- **ProGuard `-microedition`** preverification (raw javac 1.4 classes are rejected by real CLDC devices)
- JAD/manifest attributes:
  - `Nokia-MIDlet-On-Screen-Keypad: no` — no on-screen keypad on touch devices
  - `Nokia-MIDlet-No-Exit: true` — no exit confirmation dialog
  - `MIDlet-Splash-Screen-Image: suppress` — suppress vendor splash for faster startup
  - `MIDlet-Permissions: javax.microedition.io.Connector.file.read` only (write is Opt — fewer install warnings)
- JAD is UTF-8 without BOM, `MIDlet-Jar-Size` refreshed on every build

Reference projects that informed this: fplay (Nokia JAD attributes), pluotsorbet (Symbian MIDP behavior, e.g. `Nokia-MIDlet-bg-server` if background-play issues appear on real devices).

### Validation References
1. **libvgm/emu2149.c**: `clk/(freq×16)` (authoritative chip emulation)
2. **vgm2mid AY8910.bas**: `(ClockAY/16)/FNum` (Valley Bell 2013)
3. **yasp11 ay_to_opm.c**: `clock/(16×period)` (user latest)

## Build

```bash
./build.sh tools/vgm-browser
```

Output: `VgmBrowser.jar` / `VgmBrowser.jad`

## Run

- **KEmulator**: `./run-kemulator.sh tools/vgm-browser/VgmBrowser.jar`
- **One-click (Windows)**: `releases/VgmBrowser-vX.Y.Z-bin/Start-VgmBrowser.bat` — launches the latest jar with the bundled JDK8 + KEmulator
- **Real device**: copy jar+jad to phone, install from "Games & Apps" (no signing needed)

## Requirements

- CLDC 1.1 / MIDP 2.0
- JSR 135 (MMAPI) - MIDI playback
- JSR 75 (FileConnection) - file browsing

## Default Settings (Field-tuned)

| Parameter | Value |
|---|---|
| Noise map | C4 top (C2-C4), 3 modes switchable |
| Envelope timbres | Saw=81 / Decay=87 / Tri=82 / Alt=80 |
| Saw unison | +1 oct (2 voices) |
| Tri unison | +1 oct (2 voices) |
| Buzzer volume | 100 |
| Square timbre | 80 Square Lead |
| Bend sens | x1.0 (0.01 steps, x0.25–x4.00) |
| Bend range | 100% (ratio, ±5% steps) |
| Loop count | 2 |
| Play order | sequential |
| Volume model | CC7=127 fixed + CC11 expression (PSG dynamics) |

## Controls

### File List
- **Arrow keys**: navigate
- **5/OK**: open file/folder
- **Left softkey**: Now playing (while playing) / Open
- **Right softkey**: Up (parent folder)

### Player HUD
- **Arrow keys left/right**: previous/next track
- **Arrow keys up/down**: scroll GD3/info table (master volume is in Settings)
- **5**: play/pause
- **#**: settings overlay
- **Left softkey**: Settings
- **Right softkey**: List (return to file browser)

### Settings Overlay
- **Arrow keys up/down**: move cursor
- **Arrow keys left/right**: adjust value
- **#**: back to HUD
- **Left softkey**: Back
- **Right softkey**: List

## Performance

- **Layered rendering**: static content (keyboard base, GD3 table) baked into offscreen Images once per track; per-frame only dynamic highlights (~20 fillRects)
- **Target**: 20 FPS on MIDP 2.0 devices (50ms frame budget)
- **Tested**: 19 FPS on KEmulator (196% speed)

## License

Refer to libvgm (GPLv2/LGPLv2.1) and vgm2mid original licenses.
