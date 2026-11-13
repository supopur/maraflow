---
title: Časovače a detekce hran
tags: [casovac, timer, ton, tof, tp, puls, hrana, r_trig, f_trig, singlepulse]
level: beginner
see: [getting-started/scan-cycle, getting-started/traffic-light, blocks/Main.Timers.Timer]
---

# Časovače a detekce hran

Protože PLC program běží opakovaně v každém [Scan cyklu](help:getting-started/scan-cycle), nesmí časové prodlevy blokovat vykonávání programu. Časovače a detektory hran si proto uchovávají svůj stav mezi jednotlivými cykly.

## Časovače v FBD

Ve skupině `Main/Timers` najdete základní časové bloky:

- [Main.Timers.Timer](help:blocks/Main.Timers.Timer): **Zpožděné zapnutí (`TON`)**. Pokud `In` zůstane v `1` po dobu `PT` milisekund, přejde `Q` do `1`. `ET` udává uplynulý čas v ms. Při poklesu `In` na `0` se `ET` i `Q` ihned vynulují.
- [Main.Timers.PulseGen](help:blocks/Main.Timers.PulseGen): Volně běžící generátor obdélníkového signálu s nastavitelnou dobou `THi` a `TLo` (ms).
- [Main.Timers.PulseLen](help:blocks/Main.Timers.PulseLen): **Prodloužení pulsu**. Náběžná hrana na `In` podrží `Q = 1` po dobu `Len` ms.
- [Main.Timers.SinglePulse](help:blocks/Main.Timers.SinglePulse): **Jednocyklový puls na náběžnou hranu**. Při přechodu `In` z `0 -> 1` nastaví `Q = 1` na **přesně jeden scan cyklus**.

```fbd
block btn  Visual.Vars.BoolVar        at 2,2  name=StartBtn
block tmr  Main.Timers.Timer          at 14,1 name=StartDelay { PT=2000 }
block edge Main.Timers.SinglePulse    at 27,1 name=DonePulse
wire btn.Y -> tmr.In
wire tmr.Q -> edge.In
live btn.Y=1 tmr.Q=1 edge.Q=1
caption "StartDelay čeká 2000 ms po StartBtn=1, poté DonePulse vydá 1cyklový impuls."
```

## Časovače a hrany ve Structured Text (ST)

V ST zadáváte čas `PT` u bloků `TON`, `TOF` a `TP` číselně v milisekundách:

```st
// Zpožděné zapnutí: DO0 sepne 1500 ms po přivedení DI0
Delay_TON(IN := DI0, PT := 1500);
DO0 := Delay_TON.Q;

// Detekce náběžné hrany: přičte 1 do REG_0 při každém stisku DI1
BtnEdge_R_TRIG(CLK := DI1);
IF BtnEdge_R_TRIG.Q THEN
    REG_0 := REG_0 + 1;
END_IF;
```
