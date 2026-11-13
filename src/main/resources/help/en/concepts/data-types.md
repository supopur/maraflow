---
title: Data Types and Wire Colors
tags: [types, bool, int, real, time, string, colors]
level: reference
see: [concepts/fbd-ld-st, editor/fbd-canvas]
---

# Data Types and Wire Colors

Pins and wires in Maraflow are color-coded by data type so signal widths and conversions are immediately visible.

| Type | Width | Range / Format |
|---|---|---|
| `BOOL` | 1 bit | `0` or `1` (`FALSE` / `TRUE`) |
| `INT` | 16-bit signed | `-32768` .. `32767` |
| `DINT` | 32-bit signed | `-2147483648` .. `2147483647` |
| `REAL` | 32-bit float | IEEE 754 single-precision |
| `TIME` | 32-bit ms | Duration in milliseconds (e.g. `T#500ms`) |
| `STRING` | Byte buffer | ASCII text string |

## Implicit Conversions

When you wire two compatible numeric or boolean pins of different types, the wire splits color at its midpoint and the bytecode compiler inserts an implicit conversion opcode (reported in the compilation log).
