---
title: Compiling and Uploading to the PLC
tags: [compile, bytecode, upload, watch, stm32, beginner]
level: beginner
see: [getting-started/simulate, reference/memory, reference/errors]
---

# Compiling and Uploading to the PLC

Maraflow compiles FBD, LD, and ST pages into compact bytecode for the STM32F407 FreeRTOS VM.

## Compile (`F7`)

Click **Compile** on the toolbar (or press `F7`). The Compilation Results window displays:

- **RAM, Flash, and EEPROM usage** (`used / max_allowed`)
- **Estimated execution time per thread** (`Thread 1..4`) and total CPU load
- **Compiler log** with warnings and diagnostics

## Upload and Watch Mode

1. Click **Upload** (or **Close and Upload** from Compilation Results).
2. Select the transport (**USB CDC**, **Ethernet**, or **RS485**), port, baud rate, and target address.
3. Click **Watch** to enter live online monitoring of real PLC pin values. Any structural edit exits Watch mode.
