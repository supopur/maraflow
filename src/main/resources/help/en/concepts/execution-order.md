---
title: Threads and Execution Order
tags: [threads, order, scan, determinism, freertos]
level: reference
see: [getting-started/scan-cycle, editor/fbd-canvas]
---

# Threads and Execution Order

Every block belongs to a **Thread (`T:`)** and has an integer **Order (`O:`)** inside that thread.

## Thread Periods

| Thread | Period | Typical Use |
|---|---|---|
| `T:1` | 1000 ms (1 s) | Slow thermal loops, logging, counters |
| `T:2` | 100 ms | General machine control (default) |
| `T:3` | 20 ms | Fast actuators, debouncing |
| `T:4` | 5 ms | High-speed interlocks, fast I/O |

## Avoiding 1-Scan Delays

Blocks execute in ascending `O:` order. If block `A` feeds block `B`, ensure `A.O < B.O`. Use **Sort Execution Order** on the editor toolbar to automatically assign left-to-right topological order.
