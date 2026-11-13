---
title: První obvod: Start / Stop klopný obvod v FBD
tags: [fbd, latch, rs, and, beginner]
level: beginner
see: [getting-started/scan-cycle, blocks/Bool.RS, blocks/Bool.And]
---

# První obvod: Start / Stop klopný obvod v FBD

**Start / Stop paměť** (latch) udrží výstup sepnutý po krátkém impulzu Start, dokud jej nevypne signál Stop.

## Zapojení hradla AND do bloku RS

Na diagramu níže blok `EnableAnd` vyžaduje oba vstupy `X1` a `X2` rovny `1`, aby nastavil paměť `MotorLatch` (`Bool.RS`).

```fbd
block a  Bool.And  at 3,1  name=EnableAnd  { X1=1  X2=1 }
block rs Bool.RS   at 15,1 name=MotorLatch
wire a.Y -> rs.S
live a.Y=1 rs.Q=1
caption "Pokud jsou oba vstupy X1 a X2 rovny 1, EnableAnd.Y nastaví MotorLatch.S a výstup Q=1."
```

## Dominantní Reset vs. Dominantní Set

- [Bool.RS](help:blocks/Bool.RS) má **dominantní reset**: pokud jsou v jednom skenu `S=1` i `R=1`, výstup `Q` bude `0` (bezpečné pro vypnutí motoru).
- [Bool.SR](help:blocks/Bool.SR) má **dominantní set**: pokud jsou `S=1` i `R=1`, výstup `Q` bude `1`.
