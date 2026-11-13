---
title: The Three Languages: FBD, LD, and ST
tags: [fbd, ld, st, iec61131]
level: beginner
see: [getting-started/scan-cycle, getting-started/first-latch]
---

# The Three Languages: FBD, LD, and ST

Maraflow supports three programming editors that can coexist on different pages of the same project:

| Language | Full Name | Best For |
|---|---|---|
| **FBD** | Function Block Diagram | Signal flow, math chains, timers, PID/control loops, I/O wiring |
| **LD** | Ladder Diagram | Relay-style interlocks, boolean safety chains, maintenance readability |
| **ST** | Structured Text | State machines, bounded loops, complex expressions, compact logic |

## Equivalent Logic in Structured Text

```st
VAR
    MotorOut : BOOL := FALSE;
END_VAR

// Reset-dominant start/stop latch using DI0 (Start) and DI1 (Stop)
IF DI1 THEN
    MotorOut := FALSE;
ELSIF DI0 THEN
    MotorOut := TRUE;
END_IF;

DO0 := MotorOut;
```
