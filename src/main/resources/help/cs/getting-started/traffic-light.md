---
title: Jednoduchý semafor (sekvencer)
tags: [semafor, sekvencer, stavovy-automat, casovac, citac, mux, unpackbits, st, fbd]
level: beginner
see: [getting-started/timers-and-edges, getting-started/seven-segment-driver, st/tutorial, blocks/Main.Timers.Timer]
---

# Jednoduchý semafor (sekvencer)

Semafor je klasický cyklický **stavový automat** se čtyřmi fázemi. Každý stav lze zapsat jako 3bitovou binární masku (`B2` = Červená, `B1` = Žlutá, `B0` = Zelená):

| Fáze | `B2` (Červená) | `B1` (Žlutá) | `B0` (Zelená) | Binární maska |
|---|---|---|---|---|
| `0` | `ZAP` | `VYP` | `VYP` | `0b100` |
| `1` | `ZAP` | `ZAP` | `VYP` | `0b110` |
| `2` | `VYP` | `VYP` | `ZAP` | `0b001` |
| `3` | `VYP` | `ZAP` | `VYP` | `0b010` |

## Řešení v FBD: Timer + Counter + Mux (`0b` masky) + UnpackBits

V FBD zapojte samoresetovací [Timer](help:blocks/Main.Timers.Timer) (`PhaseTick`) do čítače [Counter](help:blocks/Main.Timers.Counter) (`PV=4`), který posouvá `CV` přes stavy `0..3`. Výstup `CV` přiveďte na adresu `Adr` bloku [Mux](help:blocks/Main.Switches.Mux) s binárními konstantami `0b100`, `0b110`, `0b001`, `0b010` a rozbalte jednotlivé bity přes [UnpackBits](help:blocks/Main.Conversion.UnpackBits) (`B2` = Červená, `B1` = Žlutá, `B0` = Zelená):

```fbd
block rst  Bool.Not                   at 3,2  name=AutoReset
block tmr  Main.Timers.Timer          at 15,2 name=PhaseTick { PT=2000 }
block cnt  Main.Timers.Counter        at 28,1 name=PhaseCnt  { PV=4 }
block mux  Main.Switches.Mux          at 42,1 name=LightLut  { X0=0b100 X1=0b110 X2=0b001 X3=0b010 }
block unpk Main.Conversion.UnpackBits at 56,1 name=Decode
wire rst.Y  -> tmr.In
wire tmr.Q  -> rst.X
wire tmr.Q  -> cnt.CU
wire cnt.Q  -> cnt.R
wire cnt.CV -> mux.Adr
wire mux.Y  -> unpk.In
rect   at 69,1 size 5x11 fill=#263238 stroke=#111111
circle at 70,2 size 3x3  bind=unpk.B2 active=#E53935 inactive=#4A2020
circle at 70,5 size 3x3  bind=unpk.B1 active=#FDD835 inactive=#4A4420
circle at 70,8 size 3x3  bind=unpk.B0 active=#43A047 inactive=#1E3A20
live cnt.CV=1 mux.Y=6 unpk.B2=1 unpk.B1=1 unpk.B0=0
caption "Fáze 1 (CV=1): LightLut vybere X1=0b110 a rozsvítí současně Červenou (B2) a Žlutou (B1)."
```

## Řešení v ST: Stavový automat s proměnnou dobou fází

Pokud má každá fáze jinou délku (4 s červená, 1,5 s červená+žlutá, 4 s zelená, 1,5 s žlutá), je zápis ve Structured Text pomocí `CASE` a `TON` velmi přehledný:

```st
VAR
    Phase   : INT := 0;
    PhaseMs : INT := 4000;
END_VAR

CASE Phase OF
    0: // Červená
        DO0 := TRUE;  DO1 := FALSE; DO2 := FALSE;
        PhaseMs := 4000;
    1: // Červená + Žlutá
        DO0 := TRUE;  DO1 := TRUE;  DO2 := FALSE;
        PhaseMs := 1500;
    2: // Zelená
        DO0 := FALSE; DO1 := FALSE; DO2 := TRUE;
        PhaseMs := 4000;
    3: // Žlutá
        DO0 := FALSE; DO1 := TRUE;  DO2 := FALSE;
        PhaseMs := 1500;
    ELSE
        Phase := 0;
END_CASE;

// Časovač běží dokud Q = FALSE; po uplynutí PhaseMs přepne na další fázi
Step_TON(IN := NOT Step_TON.Q, PT := PhaseMs);
IF Step_TON.Q THEN
    Phase := (Phase + 1) MOD 4;
END_IF;
```
