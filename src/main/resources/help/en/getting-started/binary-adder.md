---
title: Binary Adders in FBD
tags: [binary, adder, half-adder, full-adder, xor, and, fbd]
level: beginner
see: [getting-started/boolean-algebra, blocks/Bool.Xor, blocks/Bool.And, blocks/Bool.Or]
---

# Binary Adders in FBD

Binary adders demonstrate how pure boolean gates perform arithmetic. Every multi-bit integer adder inside a CPU or PLC is built from **Half Adders** and **Full Adders**.

## Half Adder (2 Inputs: A, B)

A Half Adder adds two 1-bit numbers `A` and `B` and produces:

- `Sum = A XOR B`
- `Carry = A AND B`

| `A` | `B` | `Sum` | `Carry` |
|---|---|---|---|
| `0` | `0` | `0` | `0` |
| `0` | `1` | `1` | `0` |
| `1` | `0` | `1` | `0` |
| `1` | `1` | `0` | `1` |

```fbd
block macroin Macro.MacroIn at 2,2 name=MacroIn out=A:BOOL,B:BOOL
block macroout Macro.MacroOut at 34,1 name=MacroOut in=Sum:BOOL,C:BOOL
block xor1 Bool.Xor at 18,1 name=Xor1
block and1 Bool.And at 18,9 name=And1
wire and1.Y -> macroout.C via 25,11.50 30,11.50 30,4.50 34,4.50
wire xor1.Y -> macroout.Sum
wire macroin.A -> xor1.X1
wire macroin.B -> xor1.X2
wire macroin.A -> and1.X1 via 9,4.50 14,4.50 14,12.50 18,12.50
wire macroin.B -> and1.X2 via 9,5.50 13,5.50 13,13.50 18,13.50
```

## Full Adder (3 Inputs: A, B, Cin)

To chain multiple bits together, each bit position must also accept a carry-in `Cin` from the previous stage. A Full Adder chains two Half Adders and combines their carries with `Bool.Or`:

- `Sum = (A XOR B) XOR Cin`
- `Cout = (A AND B) OR ((A XOR B) AND Cin)`

```fbd
block macroin Macro.MacroIn at 2,2 name=MacroIn out=A:BOOL,B:BOOL,C:BOOL
block macroout Macro.MacroOut at 48,4 name=MacroOut in=C:BOOL,Sum:BOOL
block halfadder1 Macro.User.HalfAdder at 12,1 name=HalfAdder1
block halfadder2 Macro.User.HalfAdder at 25,4 name=HalfAdder2
block or1 Bool.Or at 37,1 name=Or1
wire macroin.A -> halfadder1.A
wire macroin.B -> halfadder1.B
wire halfadder1.C -> halfadder2.A via 20,4.50 27,4.50 27,7.50 25,7.50
wire macroin.C -> halfadder2.B via 9,6.50 26,6.50 26,8.50 25,8.50
wire halfadder2.Sum -> or1.X2 via 33,6.50 39,6.50 39,5.50 37,5.50
wire halfadder1.Sum -> or1.X1 via 20,3.50 39,3.50 39,4.50 37,4.50
wire or1.Y -> macroout.C via 44,3.50 46,3.50 46,6.50 48,6.50
wire halfadder2.C -> macroout.Sum
```

To build a 4-bit ripple-carry adder, wrap this Full Adder in a [Macro](help:editor/pages-and-macros) with `A`, `B`, `Cin` inputs and `Sum`, `Cout` outputs, then chain four instances together.
