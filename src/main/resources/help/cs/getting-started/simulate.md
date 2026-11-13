---
title: Simulace programu
tags: [simulation, debug, scope, live, beginner]
level: beginner
see: [getting-started/first-latch, getting-started/upload]
---

# Simulace programu

Logiku v FBD, LD i ST můžete otestovat lokálně na počítači ještě před připojením k PLC.

## Spuštění a ovládání simulace

1. Klikněte na **Play** na horní liště editoru pro spuštění lokální simulace.
2. Pomocí přepínače rychlosti (`0.25x` až `4x`) můžete zpomalit rychlé časovače nebo zrychlit dlouhé prodlevy.
3. **Pause** pozastaví stav, **Stop** simulaci ukončí. Jakákoli úprava bloku nebo spoje simulaci automaticky ukončí.

## Čtení živých hodnot na plátně

- **Silné vodiče `BOOL`** označují aktivní signál (`1` / `TRUE`).
- **Výstupní boxy** `(1)`, `(42)` se zobrazují vpravo u každého výstupního pinu bloku.
- **Vizuální tlačítka** lze v živém režimu mačkat myší jako fyzická tlačítka.
- **Osciloskop (Scope)**: klikněte pravým tlačítkem na libovolný pin a zapněte záznam průběhu v čase.
