---
title: Timers and Edge Detection
tags: [timer, ton, tof, tp, pulse, edge, r_trig, f_trig, singlepulse]
level: beginner
see: [getting-started/scan-cycle, getting-started/traffic-light, blocks/Main.Timers.Timer]
---

# Timers and Edge Detection

Because PLC logic executes repeatedly every [Scan Cycle](help:getting-started/scan-cycle), time delays and one-shot events must preserve state across scans rather than blocking execution.

## Timers in FBD

Maraflow provides dedicated timing blocks in `Main/Timers`:

- [Main.Timers.Timer](help:blocks/Main.Timers.Timer): **On-Delay (`TON`)**. When `In` stays `1` for `PT` milliseconds, `Q` turns `1`. `ET` reports elapsed time in ms. When `In` drops to `0`, `ET` resets to `0` and `Q` turns `0`.
- [Main.Timers.PulseGen](help:blocks/Main.Timers.PulseGen): Free-running square-wave oscillator with configurable `THi` and `TLo` durations (ms).
- [Main.Timers.PulseLen](help:blocks/Main.Timers.PulseLen): **Pulse Stretcher**. A rising edge on `In` holds `Q = 1` for `Len` milliseconds.
- [Main.Timers.SinglePulse](help:blocks/Main.Timers.SinglePulse): **Rising-Edge One-Shot**. Outputs `Q = 1` for **exactly one scan cycle** when `In` transitions `0 -> 1`.

```fbd
block btn  Visual.Vars.BoolVar        at 2,2  name=StartBtn
block tmr  Main.Timers.Timer          at 14,1 name=StartDelay { PT=2000 }
block edge Main.Timers.SinglePulse    at 27,1 name=DonePulse
wire btn.Y -> tmr.In
wire tmr.Q -> edge.In
live btn.Y=1 tmr.Q=1 edge.Q=1
caption "StartDelay waits 2000 ms after StartBtn=1, then DonePulse emits a 1-scan trigger."
```

## Timers and Edges in Structured Text

In ST, `TON`, `TOF`, `TP`, `R_TRIG`, and `F_TRIG` use numeric milliseconds for `PT`:

```st
// On-delay: MotorRun turns TRUE 1500 ms after DI0 becomes TRUE
Delay_TON(IN := DI0, PT := 1500);
DO0 := Delay_TON.Q;

// Rising-edge trigger: increment CycleCount once per button press
BtnEdge_R_TRIG(CLK := DI1);
IF BtnEdge_R_TRIG.Q THEN
    REG_0 := REG_0 + 1;
END_IF;
```
