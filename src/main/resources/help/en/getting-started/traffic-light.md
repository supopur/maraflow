---
title: Simple Traffic Light Sequencer
tags: [traffic-light, sequencer, state-machine, timer, counter, mux, unpackbits, st, fbd]
level: beginner
see: [getting-started/timers-and-edges, getting-started/seven-segment-driver, st/tutorial, blocks/Main.Timers.Timer]
---

# Simple Traffic Light Sequencer

A traffic light is a classic cyclic **state machine** with four phases. Each phase can be encoded as a 3-bit binary mask (`B2` = Red, `B1` = Yellow, `B0` = Green):

| Phase | `B2` (Red) | `B1` (Yellow) | `B0` (Green) | Binary Mask |
|---|---|---|---|---|
| `0` | `ON` | `OFF` | `OFF` | `0b100` |
| `1` | `ON` | `ON` | `OFF` | `0b110` |
| `2` | `OFF` | `OFF` | `ON` | `0b001` |
| `3` | `OFF` | `ON` | `OFF` | `0b010` |

## FBD Approach: Timer + Counter + Mux (`0b` Masks) + UnpackBits

In FBD, wire a self-resetting [Timer](help:blocks/Main.Timers.Timer) (`PhaseTick`) into a [Counter](help:blocks/Main.Timers.Counter) (`PV=4`) to step `CV` through `0..3`. Feed `CV` into [Mux](help:blocks/Main.Switches.Mux) with `0b` binary constants (`0b100`, `0b110`, `0b001`, `0b010`), then decode the 3 bits with [UnpackBits](help:blocks/Main.Conversion.UnpackBits) (`B2` = Red, `B1` = Yellow, `B0` = Green) to drive the indicator lights:

```fbd
block rst  Bool.Not                   at 3,2  name=AutoReset
block tmr  Main.Timers.Timer          at 15,2 name=PhaseTick { PT=2000 }
block cnt  Main.Timers.Counter        at 28,1 name=PhaseCnt  { PV=4 }
block mux  Main.Switches.Mux          at 42,1 name=LightLut  { X0=0b100 X1=0b110 X2=0b001 X3=0b010 }
block unpk Main.Conversion.UnpackBits at 56,1 name=Decode
wire rst.Y  -> tmr.In
wire tmr.Q  -> rst.X
wire tmr.Q  -> cnt.CU
wire cnt.Q  -> cnt.R
wire cnt.CV -> mux.Adr
wire mux.Y  -> unpk.In
rect   at 69,1 size 5x11 fill=#263238 stroke=#111111
circle at 70,2 size 3x3  bind=unpk.B2 active=#E53935 inactive=#4A2020
circle at 70,5 size 3x3  bind=unpk.B1 active=#FDD835 inactive=#4A4420
circle at 70,8 size 3x3  bind=unpk.B0 active=#43A047 inactive=#1E3A20
live cnt.CV=1 mux.Y=6 unpk.B2=1 unpk.B1=1 unpk.B0=0
caption "Phase 1 (CV=1): LightLut selects X1=0b110, lighting Red (B2) and Yellow (B1) simultaneously."
```

## ST Approach: Variable-Duration State Machine

When each phase needs a different duration (4 s Red, 1.5 s Red+Yellow, 4 s Green, 1.5 s Yellow), Structured Text `CASE` + `TON` is concise and easy to read:

```st
VAR
    Phase   : INT := 0;
    PhaseMs : INT := 4000;
END_VAR

CASE Phase OF
    0: // Red
        DO0 := TRUE;  DO1 := FALSE; DO2 := FALSE;
        PhaseMs := 4000;
    1: // Red + Yellow
        DO0 := TRUE;  DO1 := TRUE;  DO2 := FALSE;
        PhaseMs := 1500;
    2: // Green
        DO0 := FALSE; DO1 := FALSE; DO2 := TRUE;
        PhaseMs := 4000;
    3: // Yellow
        DO0 := FALSE; DO1 := TRUE;  DO2 := FALSE;
        PhaseMs := 1500;
    ELSE
        Phase := 0;
END_CASE;

// Run timer while its output is FALSE; advance phase when PT expires
Step_TON(IN := NOT Step_TON.Q, PT := PhaseMs);
IF Step_TON.Q THEN
    Phase := (Phase + 1) MOD 4;
END_IF;
```
