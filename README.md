# Notyourflix (NY)

Netflix-style Android app for your own videos. English + Arabic (RTL), profiles with optional PIN lock,
Home / Search / Settings tabs, upload (movie or series), resume watching, custom intros and sound FX.

## Build the APK
Push this folder to a GitHub repo (files at the repo root). The workflow in `.github/workflows/build.yml`
builds it; download the APK from the run's **Artifacts** (`Notyourflix-APK`).

## Remote / controller controls
- D-pad: move focus. OK / A: select. Back / B: back.
- L1 / R1: previous / next tab.
- Player: OK = play/pause, Left/Right = -10s/+10s, Up/Down = show controls, Next/Prev media keys = episodes.
- Also works on Android TV (Leanback launcher entry included).
