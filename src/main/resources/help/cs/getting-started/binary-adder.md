---
title: Binární sčítačky v FBD
tags: [binarni, scitacka, polovicni-scitacka, uplna-scitacka, xor, and, fbd]
level: beginner
see: [getting-started/boolean-algebra, blocks/Bool.Xor, blocks/Bool.And, blocks/Bool.Or]
---

# Binární sčítačky v FBD

Binární sčítačky ukazují, jak čistě booleovská hradla provádějí aritmetiku. Každá vícebitová sčítačka v procesoru nebo PLC vzniká řetězením **polovičních** a **úplných sčítaček**.

## Poloviční sčítačka (2 vstupy: A, B)

Poloviční sčítačka (Half Adder) sečte dva 1bitové vstupy `A` a `B`:

- `Sum = A XOR B`
- `Carry = A AND B`

| `A` | `B` | `Sum` | `Carry` |
|---|---|---|---|
| `0` | `0` | `0` | `0` |
| `0` | `1` | `1` | `0` |
| `1` | `0` | `1` | `0` |
| `1` | `1` | `0` | `1` |

```fbd
block a   Visual.Vars.BoolVar at 2,2  name=BitA
block b   Visual.Vars.BoolVar at 2,8  name=BitB
block sum Bool.Xor            at 14,1 name=HalfSum
block cry Bool.And            at 14,8 name=HalfCarry
wire a.Y -> sum.X1
wire b.Y -> sum.X2
wire a.Y -> cry.X1
wire b.Y -> cry.X2
live a.Y=1 b.Y=1 sum.Y=0 cry.Y=1
caption "Poloviční sčítačka: 1 + 1 dává Sum=0 a Carry=1 (binárně 10 = dekadicky 2)."
```

## Úplná sčítačka (3 vstupy: A, B, Cin)

Pro řetězení více bitů musí každý stupeň přijímat i přenos z předchozího bitu (`Cin`). Úplná sčítačka (Full Adder) spojí dvě poloviční sčítačky a jejich přenosy sloučí pomocí `Bool.Or`:

- `Sum = (A XOR B) XOR Cin`
- `Cout = (A AND B) OR ((A XOR B) AND Cin)`

```fbd
block xor1 Bool.Xor at 3,1  name=AB_Xor   { X1=1 X2=0 }
block and1 Bool.And at 3,8  name=AB_And   { X1=1 X2=0 }
block xor2 Bool.Xor at 15,1 name=FinalSum { X2=1 }
block and2 Bool.And at 15,8 name=Cin_And  { X2=1 }
block or1  Bool.Or  at 27,4 name=Cout_Or
wire xor1.Y -> xor2.X1
wire xor1.Y -> and2.X1
wire and2.Y -> or1.X1
wire and1.Y -> or1.X2
live xor1.Y=1 xor2.Y=0 and2.Y=1 or1.Y=1
caption "Úplná sčítačka pro A=1, B=0, Cin=1: FinalSum.Y=0 a Cout_Or.Y=1."
```

Pro 4bitovou sčítačku zabalte úplnou sčítačku do [Makra](help:editor/pages-and-macros) se vstupy `A`, `B`, `Cin` a výstupy `Sum`, `Cout` a zapojte čtyři instance za sebou.
