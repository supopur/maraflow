---
title: Ladder Diagram (LD) Basics
tags: [ld, ladder, rung, contact, coil, branch]
level: beginner
see: [concepts/fbd-ld-st, getting-started/scan-cycle]
---

# Ladder Diagram (LD) Basics

Ladder Diagram models relay logic as horizontal **rungs** evaluated top-to-bottom on each scan cycle, from the left power rail (`L+`) to the right return rail (`L-`).

## Contacts and Coils

| Element | Symbol | Behavior |
|---|---|---|
| Normally Open (NO) | `-\| \|-` | Conducts power when operand is `1` |
| Normally Closed (NC) | `-\|/\|-` | Conducts power when operand is `0` |
| Rising Edge Contact | `-\|P\|-` | Pulses for one scan on `0 -> 1` transition |
| Normal Coil | `-( )-` | Writes rung power state to operand |
| Set Coil | `-(S)-` | Latches operand to `1` when energized |
| Reset Coil | `-(R)-` | Resets operand to `0` when energized |

## Parallel Branches (Seal-In Latch)

Series contacts implement logical **AND**. Parallel branches implement logical **OR**. A classic motor seal-in circuit latches `DO0` around the momentary `DI0` start button while `DI1` (stop, NC) remains closed:

```ld
rung "Start / Stop Seal-In Latch"
  no DI0 -> nc DI1 -> coil DO0
  branch 0..0: no DO0
live DI0=1 DI1=0 DO0=1
caption "Power flows through DI0 (or holding contact DO0) and NC contact DI1 to energize DO0."
```

## Set and Reset Coils

For explicit latching across separate rungs, use `-(S)-` and `-(R)-` coils:

```ld
rung "Latch Fault"
  pos DI2 -> set M0
rung "Clear Fault"
  no DI3 -> reset M0
caption "Rung 1 sets M0 on a rising edge of DI2; Rung 2 resets M0 when DI3 is 1."
```
