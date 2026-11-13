---
title: How a PLC Program Runs (Scan Cycle)
tags: [scan-cycle, threads, execution, beginner]
level: beginner
see: [getting-started/first-latch, concepts/fbd-ld-st]
---

# How a PLC Program Runs (Scan Cycle)

Unlike a desktop program that runs from `main()` and exits, a PLC executes your logic in a continuous loop called a **scan cycle**.

## The Three Phases of Every Scan

1. **Read Inputs** — The PLC samples all physical digital/analog inputs and copies them into an input image table.
2. **Execute Program** — Blocks or rungs run in deterministic order (sorted by Thread ID, then Order `O:`).
3. **Write Outputs** — Computed output values are committed to the physical outputs at the end of the cycle.

```
[ Read Inputs ] ---> [ Execute Logic (O:1, O:2, ...) ] ---> [ Write Outputs ]
       ^                                                            |
       +------------------------------------------------------------+
```

## Why Order (`T:` / `O:`) Matters

In FBD, every block displays `T:<thread>` and `O:<order>` at the bottom of its courtyard:

- **Thread (`T:`)** selects which FreeRTOS task runs the block (e.g. fast cyclic vs. slow background).
- **Order (`O:`)** defines the exact evaluation sequence within a thread.

If block `B` reads an output from block `A`, `A` should have a lower `O:` than `B`; otherwise `B` sees `A`'s value from the **previous** scan cycle (a 1-scan delay). Use **Sort Execution Order** on the toolbar to assign left-to-right order automatically.
