---
title: Booleova algebra a logická hradla
tags: [booleova-algebra, logika, and, or, not, xor, nand, nor, zacatecnik]
level: beginner
see: [getting-started/first-latch, getting-started/binary-adder, blocks/Bool.And]
---

# Booleova algebra a logická hradla

Digitální automatizační logika pracuje se dvěma stavy: `1` (`TRUE`, sepnuto) a `0` (`FALSE`, rozepnuto). Každý kombinační obvod lze sestavit ze tří základních operací (`AND`, `OR`, `NOT`) a odvozených hradel (`NAND`, `NOR`, `XOR`, `XNOR`).

## Pravdivostní tabulka základních hradel

| `A` | `B` | `AND` | `OR` | `XOR` | `NAND` | `NOR` | `XNOR` |
|---|---|---|---|---|---|---|---|
| `0` | `0` | `0` | `0` | `0` | `1` | `1` | `1` |
| `0` | `1` | `0` | `1` | `1` | `1` | `0` | `0` |
| `1` | `0` | `0` | `1` | `1` | `1` | `0` | `0` |
| `1` | `1` | `1` | `1` | `0` | `0` | `0` | `1` |

- [Bool.And](help:blocks/Bool.And): Výstup `Y = 1` pouze tehdy, když jsou **všechny** vstupy `1`.
- [Bool.Or](help:blocks/Bool.Or): Výstup `Y = 1`, pokud je **alespoň jeden** vstup `1`.
- [Bool.Not](help:blocks/Bool.Not): Neguje vstupní signál (`Y = 1`, když `X = 0`).
- [Bool.Xor](help:blocks/Bool.Xor): Výstup `Y = 1`, pokud se vstupy **liší** (klíčové pro binární sčítání a paritu).

## Příklad: Hlasování 2 ze 3 (Majority Vote)

Typickým bezpečnostním vzorem v průmyslu je **hlasování 2 ze 3**: výstup sepne, pokud se shodnou alespoň dva ze tří snímačů (`A`, `B`, `C`):

`Y = (A AND B) OR (B AND C) OR (A AND C)`

```fbd
block ab  Bool.And at 3,1  name=A_AND_B { X1=1 X2=1 }
block bc  Bool.And at 3,7  name=B_AND_C { X1=1 X2=0 }
block or1 Bool.Or  at 15,4 name=VoteOr
wire ab.Y -> or1.X1
wire bc.Y -> or1.X2
live ab.Y=1 bc.Y=0 or1.Y=1
caption "Částečné hlasování 2 ze 3: jakmile je libovolná dvojice AND rovna 1, VoteOr.Y = 1."
```

## De Morganovy zákony

De Morganovy zákony umožňují zjednodušit negované výrazy:

- `NOT (A AND B) = (NOT A) OR (NOT B)` (odpovídá `Bool.Nand`)
- `NOT (A OR B) = (NOT A) AND (NOT B)` (odpovídá `Bool.Nor`)

## Ekvivalent ve Structured Text (ST)

```st
// Hlasování 2 ze 3 pomocí digitálních vstupů DI0, DI1, DI2
DO0 := (DI0 AND DI1) OR (DI1 AND DI2) OR (DI0 AND DI2);
```
