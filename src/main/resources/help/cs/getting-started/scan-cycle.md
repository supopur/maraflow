---
title: Jak běží PLC program (cyklus skenu)
tags: [scan-cycle, threads, execution, beginner]
level: beginner
see: [getting-started/first-latch, concepts/fbd-ld-st]
---

# Jak běží PLC program (cyklus skenu)

Na rozdíl od běžného programu, který proběhne jednou a skončí, běží PLC logika v nekonečné smyčce nazývané **cyklus skenu** (scan cycle).

## Tři fáze každého skenu

1. **Čtení vstupů** — PLC načte fyzické digitální a analogové vstupy do vstupního obrazu.
2. **Vykonání programu** — Bloky nebo příčky se vyhodnotí v přesném pořadí (podle vlákna `T:` a pořadí `O:`).
3. **Zápis výstupů** — Vypočítané hodnoty se na konci cyklu zapíšou na fyzické výstupy.

```
[ Čtení vstupů ] ---> [ Výpočet logiky (O:1, O:2, ...) ] ---> [ Zápis výstupů ]
        ^                                                             |
        +-------------------------------------------------------------+
```

## Proč záleží na pořadí (`T:` / `O:`)

Každý FBD blok zobrazuje ve spodní části `T:<vlákno>` a `O:<pořadí>`:

- **Vlákno (`T:`)** určuje úlohu FreeRTOS, ve které se blok vyhodnocuje.
- **Pořadí (`O:`)** určuje přesnou posloupnost vyhodnocení v rámci vlákna.

Pokud blok `B` čte výstup bloku `A`, měl by mít `A` nižší `O:` než `B`; jinak `B` uvidí hodnotu z **předchozího** skenu (zpoždění o 1 cyklus). Nástrojem **Sort Execution Order** na liště přiřadíte pořadí zleva doprava automaticky.
