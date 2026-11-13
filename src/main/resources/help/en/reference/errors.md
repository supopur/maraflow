---
title: Compiler Errors and Diagnostics
tags: [errors, diagnostics, compiler, warnings, vm]
level: reference
see: [getting-started/upload, reference/memory]
---

# Compiler Errors and Diagnostics

Common diagnostics reported by the bytecode compiler and STM32F407 VM.

## E001 - RAM Budget Exceeded

Total variable and block instance RAM exceeds the target limit. Reduce unused `STRING` buffers or large arrays.

## E002 - Flash Budget Exceeded

Compiled bytecode plus constant ROM exceeds available Flash. Split or simplify unused macro instances.

## E003 - Thread Execution Budget Exceeded

Estimated execution time for a thread (`Thread 1..4`) exceeds its cycle period. Move non-critical blocks to a slower thread (e.g. `T:1` or `T:2`).

## W001 - Implicit Type Conversion

A wire connects two different numeric types (for example `INT` to `REAL`). The compiler inserts a cast opcode automatically.

## W002 - Order Inversion (1-Scan Delay)

A downstream block has a lower `O:` value than its upstream source block. Run **Sort Execution Order** to fix.
