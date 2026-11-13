---
title: Glossary
tags: [glossary, terms, beginner]
level: beginner
see: [getting-started/scan-cycle, concepts/fbd-ld-st]
---

# Glossary

| Term | Meaning |
|---|---|
| **Scan Cycle** | One complete Read Inputs -> Execute Program -> Write Outputs pass. |
| **Thread (`T:`)** | FreeRTOS cyclic task (Thread 1 = 1 s, Thread 2 = 100 ms, Thread 3 = 20 ms, Thread 4 = 5 ms). |
| **Order (`O:`)** | Deterministic execution sequence of blocks within a thread. |
| **Courtyard** | The central gray body of an FBD block between input and output pin columns. |
| **Constant Box `(=x)`** | Compile-time constant shown on an unconnected input pin. Double-click the pin to edit. |
| **Bus** | A bundled multi-signal connection between two blocks rendered with 45-degree KiCad-style taps. |
| **Hidden Wire** | A wire replaced by matching OCON/ICON net flags to avoid clutter across long distances. |
| **Rung** | One horizontal logic row in a Ladder Diagram (LD) from left power rail to right coil(s). |
| **Macro Page** | A reusable sub-page with defined input/output pins that can be placed as a block on another page. |
