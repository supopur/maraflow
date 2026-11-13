---
title: First Circuit: Start / Stop Latch in FBD
tags: [fbd, latch, rs, and, beginner]
level: beginner
see: [getting-started/scan-cycle, blocks/Bool.RS, blocks/Bool.And]
---

# First Circuit: Start / Stop Latch in FBD

A **Start / Stop latch** keeps an output energized after a momentary start pulse until a stop signal resets it.

## Wiring an AND Gate into an RS Latch

In the diagram below, `And1` requires both `X1` and `X2` to be `1` before setting `Latch` (`Bool.RS`).

```fbd
block a  Bool.And  at 3,1  name=EnableAnd  { X1=1  X2=1 }
block rs Bool.RS   at 15,1 name=MotorLatch
wire a.Y -> rs.S
live a.Y=1 rs.Q=1
caption "When both X1 and X2 are 1, EnableAnd.Y drives MotorLatch.S and sets Q=1."
```

## Dominant Reset vs. Dominant Set

- [Bool.RS](help:blocks/Bool.RS) is **reset-dominant**: if both `S=1` and `R=1` in the same scan, `Q` becomes `0` (safe for motor stop circuits).
- [Bool.SR](help:blocks/Bool.SR) is **set-dominant**: if both `S=1` and `R=1`, `Q` becomes `1`.
