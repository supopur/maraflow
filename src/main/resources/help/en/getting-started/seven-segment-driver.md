---
title: 7-Segment Display Driver with Mux and UnpackBits
tags: [7-segment, mux, unpackbits, bitmask, display, conversion]
level: beginner
see: [blocks/Main.Switches.Mux, blocks/Main.Conversion.UnpackBits, blocks/Visual.Segment]
---

# 7-Segment Display Driver with Mux and UnpackBits

A 7-segment display ([Visual.Segment](help:blocks/Visual.Segment)) has 7 individual LED segments (`A` through `G`) plus a decimal point (`DP`). Instead of wiring separate comparators for every segment, you can encode each digit's pattern as a single integer bitmask, select the active digit with [Main.Switches.Mux](help:blocks/Main.Switches.Mux), and split it into boolean pins with [Main.Conversion.UnpackBits](help:blocks/Main.Conversion.UnpackBits).

## Segment Bitmask Table (Bits B0..B6 -> A..G)

Assign bit `0` (`1`) to `A`, bit `1` (`2`) to `B`, ..., bit `6` (`64`) to `G`:

| Digit | Segments Lit | Binary (`GFEDCBA`) | Decimal Bitmask |
|---|---|---|---|
| `0` | `A,B,C,D,E,F` | `0111111` | `63` |
| `1` | `B,C` | `0000110` | `6` |
| `2` | `A,B,D,E,G` | `1011011` | `91` |
| `3` | `A,B,C,D,G` | `1001111` | `79` |
| `4` | `B,C,F,G` | `1100110` | `102` |
| `5` | `A,C,D,F,G` | `1101101` | `109` |
| `6` | `A,C,D,E,F,G` | `1111101` | `125` |
| `7` | `A,B,C` | `0000111` | `7` |
| `8` | `A,B,C,D,E,F,G` | `1111111` | `127` |
| `9` | `A,B,C,D,F,G` | `1101111` | `111` |

## Wiring Mux -> UnpackBits -> Segment

In FBD, add extra input pins to `Mux` (up to `X9`) via its block properties or right-click menu, set each `X0..X9` constant to the decimal bitmask above, and connect `Mux.Y` into `UnpackBits.In`. Then wire `B0..B6` to `Segment` pins `A..G`:

```fbd
block intvar1 Visual.Vars.IntVar at 2,3 name=Int2Code
block mux1 Main.Switches.Mux at 11,2 name=Decoder { X0=63 X1=6 X2=91 X3=79 X4=102 X5=109 X6=125 X7=7 X8=127 X9=111 }
block unpk81 Main.Conversion.UnpackBits at 21,1 name=Code2Seg
block segment1 Visual.Segment at 31,1 name=Segment1
wire intvar1.Y -> mux1.Adr
wire mux1.Y -> unpk81.In
wire unpk81.B0 -> segment1.A
wire unpk81.B1 -> segment1.B
wire unpk81.B2 -> segment1.C
wire unpk81.B3 -> segment1.D
wire unpk81.B4 -> segment1.E
wire unpk81.B5 -> segment1.F
wire unpk81.B6 -> segment1.G
wire unpk81.B7 -> segment1.DP
```