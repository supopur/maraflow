---
title: RS (Reset-Dominant Latch)
tags: [bool, latch, bistable, rs]
level: reference
see: [blocks/Bool.SR, getting-started/first-latch]
---

`Bool.RS` is a **reset-dominant** bistable flip-flop. Use it for safety-critical start/stop latches where a simultaneous Stop (`R=1`) and Start (`S=1`) must keep the output `Q` off.

{{auto:image}}

## Truth Table

| `S` | `R` | `Q` (next) | `nQ` (next) |
|---|---|---|---|
| `0` | `0` | Hold `Q` | Hold `nQ` |
| `1` | `0` | `1` | `0` |
| `0` | `1` | `0` | `1` |
| `1` | `1` | `0` (Reset wins) | `1` |

## Example

```fbd
block start Bool.And at 3,1 name=StartCond { X1=1 X2=1 }
block latch Bool.RS  at 15,1 name=MotorRS
wire start.Y -> latch.S
live start.Y=1 latch.Q=1
caption "StartCond sets MotorRS. When R=1 is asserted, Q drops to 0 regardless of S."
```

{{auto:pins}}

{{auto:facts}}
