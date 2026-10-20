# VGM Browser (VgmBrowser)

J2ME VGM player with real-time AY8910 register-stream to MIDI conversion and pixel-art 88-key visualization.

## Features

### Playback
- **VGM/VGZ file browser** with directory navigation (JSR 75 FileConnection)
- **Dual-chip support** (AY8910 × 2, up to 6 voices + 2 envelope channels)
- **Loop control**: 1/2/3/5/infinite loop count, sequential or shuffle playback
- **Background play**: switch to file list without stopping; "Now playing" command returns to player
- **VGZ decompression progress**: instant loading screen with real-time DEFLATE progress bar (no silent freeze)

### Real-time AY8910 → MIDI Conversion
- **7-channel mapping** per chip:
  - Ch 0/1/2: Square waves A/B/C → MIDI ch 0/1/2 (program 80 Square Lead)
  - Ch 3/4/5: Noise A/B/C → MIDI ch 3/4/5 (program 127, independent stereo pan)
  - Ch 6: Envelope/buzzer → MIDI ch 6 (program 83 Sawtooth)
- **Smart buzzer detection**: fast envelope (period < 16) auto-routes to buzzer channel with portamento
- **Synchronized unison**: slow triangle envelope triggers 2-voice (+1 octave) polyphony on square channels
- **Accurate pitch**: fixed-point log2 lookup (256 entries, ~7 cent precision), no floating-point dependency

### Live Adjustable Parameters (Settings overlay)
- **Stereo modes**: ABC / ACB / BAC / BCA / CAB / CBA / MONO
- **Octave shift**: -3 to +3 (per-channel, 12-semitone steps)
- **Noise mapping**: C4 center (C3-C5) / C4 top (C2-C4) / 88 keys spread
- **Envelope octave**: saw/tri independent octave shift (-2 to +2)
- **Unison modes**: off / +1 oct (2v) / +2 oct (3v) for saw/tri envelopes
- **Buzzer volume**: 0-127
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
Fast envelope (period < 16 samples @ 44.1kHz, i.e. > 2.76kHz) routed to dedicated buzzer channel with:
- Frequency derived from envelope period: `clock / (256 × env_period × env_shape_length)`
- MIDI portamento for pitch slides
- Independent volume control (menu adjustable 0-127)

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
- **Real device**: copy jar+jad to phone, install from "Games & Apps"

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

## Controls

### File List
- **Arrow keys**: navigate
- **5/OK**: open file/folder
- **Left softkey**: Now playing (while playing) / Open
- **Right softkey**: Up (parent folder)

### Player HUD
- **Arrow keys left/right**: previous/next track
- **Arrow keys up/down**: master volume ±10
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
