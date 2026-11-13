---
title: Simulating Your Program
tags: [simulation, debug, scope, live, beginner]
level: beginner
see: [getting-started/first-latch, getting-started/upload]
---

# Simulating Your Program

You can test FBD, LD, and ST logic locally on your workstation before connecting hardware.

## Starting and Controlling Simulation

1. Click **Play** on the main editor toolbar to start the local simulation engine.
2. Adjust the **Speed Multiplier** dropdown (`0.25x` to `4x`) to slow down fast timers or speed up long delays.
3. Click **Pause** to freeze state, or **Stop** to halt simulation. Editing any block or wire automatically exits simulation.

## Reading Live State on the Canvas

- **Thick `BOOL` wires** indicate an active (`1` / `TRUE`) signal.
- **Output value boxes** `(1)`, `(42)` appear on the right side of every block output pin.
- **Visual buttons** become clickable in live mode to simulate physical pushbuttons.
- **Oscilloscope (Scope)**: right-click any pin and toggle Scope recording to plot values over scan cycles.
