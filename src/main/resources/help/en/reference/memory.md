---
title: Target Memory and VM Model
tags: [memory, ram, flash, eeprom, stm32f407, freertos]
level: reference
see: [reference/errors, concepts/execution-order]
---

# Target Memory and VM Model

Maraflow targets an **STM32F407 (168 MHz)** running a **FreeRTOS bytecode VM**.

## Memory Regions

- **RAM**: Runtime block state, pin values, and ST `VAR` storage.
- **Flash (ROM)**: Compiled bytecode instructions and constant tables.
- **EEPROM (64 KiB)**: Persistent retentive parameters across power cycles.
