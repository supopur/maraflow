---
title: Boolean Algebra and Logic Gates
tags: [boolean, logic, and, or, not, xor, nand, nor, xnor, beginner]
level: beginner
see: [getting-started/first-latch, getting-started/binary-adder, blocks/Bool.And]
---

# Boolean Algebra and Logic Gates

Digital automation logic operates on two states: `1` (`TRUE`, energized) and `0` (`FALSE`, de-energized). Every combinational circuit can be built from three fundamental operations (`AND`, `OR`, `NOT`) and their derived gates (`NAND`, `NOR`, `XOR`, `XNOR`).

## Basic Gates Truth Table

| `A` | `B` | `AND` | `OR` | `XOR` | `NAND` | `NOR` | `XNOR` |
|---|---|---|---|---|---|---|---|
| `0` | `0` | `0` | `0` | `0` | `1` | `1` | `1` |
| `0` | `1` | `0` | `1` | `1` | `1` | `0` | `0` |
| `1` | `0` | `0` | `1` | `1` | `1` | `0` | `0` |
| `1` | `1` | `1` | `1` | `0` | `0` | `0` | `1` |

- [Bool.And](help:blocks/Bool.And): Output `Y = 1` only when **all** inputs are `1`.
- [Bool.Or](help:blocks/Bool.Or): Output `Y = 1` when **at least one** input is `1`.
- [Bool.Not](help:blocks/Bool.Not): Inverts a single signal (`Y = 1` when `X = 0`).
- [Bool.Xor](help:blocks/Bool.Xor): Output `Y = 1` when inputs **differ** (essential for binary addition and parity).

## Example: Majority Vote (2-out-of-3)

A classic industrial safety pattern is **2-out-of-3 voting**: an output turns on when any two of three sensors (`A`, `B`, `C`) agree. In boolean algebra:

`Y = (A AND B) OR (B AND C) OR (A AND C)`

```fbd
block ab  Bool.And at 3,1  name=A_AND_B { X1=1 X2=1 }
block bc  Bool.And at 3,7  name=B_AND_C { X1=1 X2=0 }
block or1 Bool.Or  at 15,4 name=VoteOr
wire ab.Y -> or1.X1
wire bc.Y -> or1.X2
live ab.Y=1 bc.Y=0 or1.Y=1
caption "2-out-of-3 partial voting: if any AND pair is 1, VoteOr.Y becomes 1."
```

## De Morgan's Laws

De Morgan's laws let you simplify inverted conditions:

- `NOT (A AND B) = (NOT A) OR (NOT B)` (same as `Bool.Nand`)
- `NOT (A OR B) = (NOT A) AND (NOT B)` (same as `Bool.Nor`)

## Equivalent in Structured Text

```st
// 2-out-of-3 sensor voting using digital inputs DI0, DI1, DI2
DO0 := (DI0 AND DI1) OR (DI1 AND DI2) OR (DI0 AND DI2);
```
