# VGMP — Video Game Music Player for Android

VGMP is a fully offline Android player for video game music. It browses files through Android's Storage Access Framework, plays directly from user-selected folders, and stores named playlists as lightweight URI references without importing a filesystem-sized library.

## Features

- Folder-first browsing with persistent access to a selected music tree
- Virtual ZIP browsing that extracts only the selected track for playback
- Named playlists without duplicating audio files
- VGM/VGZ, GME, KSS/MSX, tracker, MIDI, Doom MUS/LMP, and PSF playback
- Per-chip volume profiles plus channel mute and solo controls
- Spectrum and per-channel visualizers
- Playback speed and loop controls where supported
- Bluetooth media controls and playlist browsing through Android Auto
- No network or broad storage permissions

## Supported formats

- VGM: `.vgm`, `.vgz`
- GME: `.nsf`, `.nsfe`, `.gbs`, `.gym`, `.hes`, `.ay`, `.sap`, `.spc`
- KSS/MSX: `.kss`, `.mgs`, `.bgm`, `.opx`, `.mpk`, `.mbm`
- Trackers: `.mod`, `.xm`, `.s3m`, `.it`, `.mptm`, `.stm`, `.far`, `.ult`, `.med`, `.mtm`, `.psm`, `.amf`, `.okt`, `.dsm`, `.dtm`, `.umx`
- MIDI: `.mid`, `.midi`, `.rmi`, `.smf`
- Doom: `.mus`, `.lmp`
- PlayStation: `.psf`, `.psf1`, `.psf2`, `.minipsf`, `.minipsf1`, `.minipsf2`

## Build requirements

- JDK 17 or newer
- Android SDK 35
- Android NDK and CMake as configured by the project
- Git with submodule support

## Setup and build

```bash
git clone --recurse-submodules https://github.com/simpolism/vgmp.git
cd vgmp
./gradlew assembleDebug
```

For an existing checkout:

```bash
git submodule sync --recursive
git submodule update --init --recursive
```

Release APKs are produced with:

```bash
./gradlew assembleRelease
```

Outputs are written beneath `app/build/outputs/apk/`.

## Native dependencies

Native playback engines live in `app/src/main/cpp`. VGMP-specific integrations for libvgm, libopenmpt, libkss, and sexypsf are committed on the `vgmp-android` branches of the corresponding `simpolism` forks. Builds therefore do not patch or modify submodule working trees.

Other engines include Game Music Emu, libADLMIDI, and libMusDoom.

## License

MIT. Native dependencies retain their respective licenses.
