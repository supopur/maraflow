---
title: Řadič 7segmentového displeje přes Mux a UnpackBits
tags: [7-segment, mux, unpackbits, bitmaska, displej, konverze]
level: beginner
see: [blocks/Main.Switches.Mux, blocks/Main.Conversion.UnpackBits, blocks/Visual.Segment]
---

# Řadič 7segmentového displeje přes Mux a UnpackBits

Sedmisegmentový displej ([Visual.Segment](help:blocks/Visual.Segment)) má 7 samostatných segmentů (`A` až `G`) a desetinnou tečku (`DP`). Místo složitého zapojování komparátorů pro každý segment zvlášť můžete vzor každé číslice zakódovat do jediného celého čísla (bitové masky), vybrat jej blokem [Main.Switches.Mux](help:blocks/Main.Switches.Mux) a rozdělit na jednotlivé bity pomocí [Main.Conversion.UnpackBits](help:blocks/Main.Conversion.UnpackBits).

## Tabulka bitových masek (bity B0..B6 -> A..G)

Přiřaďte bit `0` (`1`) segmentu `A`, bit `1` (`2`) segmentu `B`, ..., bit `6` (`64`) segmentu `G`:

| Číslice | Rozsvícené segmenty | Binárně (`GFEDCBA`) | Dekadická maska |
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

## Zapojení Mux -> UnpackBits -> Segment

V FBD přidejte bloku `Mux` další vstupy (až po `X9`), nastavte konstanty `X0..X9` podle tabulky výše a propojte `Mux.Y` do `UnpackBits.In`. Výstupy `B0..B6` pak zapojte přímo na vstupy `A..G` bloku `Segment`:

```fbd
block sel  Visual.Vars.IntVar         at 2,2  name=DigitIdx
block mux  Main.Switches.Mux          at 14,1 name=SegLut   { X0=63 X1=6 X2=91 X3=79 }
block unpk Main.Conversion.UnpackBits at 27,1 name=Decode
block seg  Visual.Segment             at 41,1 name=Display
wire sel.Y   -> mux.Adr
wire mux.Y   -> unpk.In
wire unpk.B0 -> seg.A
wire unpk.B1 -> seg.B
wire unpk.B2 -> seg.C
wire unpk.B3 -> seg.D
wire unpk.B4 -> seg.E
wire unpk.B5 -> seg.F
wire unpk.B6 -> seg.G
live sel.Y=1 mux.Y=6 unpk.B1=1 unpk.B2=1
caption "DigitIdx=1 vybere X1=6 (binárně 0000110), což rozsvítí segmenty B a C na Display."
```

## Tip: Kompletní čítač 0–9

Klikněte pravým tlačítkem na blok `Mux`, přidejte vstupy `X2` až `X9` a vyplňte hodnoty `91, 79, 102, 109, 125, 7, 127, 111`. Napojením výstupu `CV` z bloku [Counter](help:blocks/Main.Timers.Counter) na `Mux.Adr` získáte kompletní digitální čítač `0–9`.
