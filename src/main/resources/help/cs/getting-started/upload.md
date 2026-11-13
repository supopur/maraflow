---
title: Kompilace a nahrání do PLC
tags: [compile, bytecode, upload, watch, stm32, beginner]
level: beginner
see: [getting-started/simulate, reference/memory, reference/errors]
---

# Kompilace a nahrání do PLC

Maraflow překládá stránky FBD, LD a ST do kompaktního bajtkódu pro STM32F407 FreeRTOS VM.

## Kompilace (`F7`)

Klikněte na **Compile** na liště (nebo stiskněte `F7`). Okno výsledků kompilace zobrazí:

- **Využití RAM, Flash a EEPROM** (`použito / maximum`)
- **Odhadovanou dobu běhu vláken** (`Thread 1..4`) a celkové zatížení CPU
- **Výpis překladače** včetně varování a diagnostiky

## Nahrání a režim Watch

1. Klikněte na **Upload** (nebo **Close and Upload** z výsledků kompilace).
2. Vyberte rozhraní (**USB CDC**, **Ethernet** nebo **RS485**), port, přenosovou rychlost a adresu.
3. Kliknutím na **Watch** přejdete do živého sledování reálných hodnot z PLC. Úprava schématu režim Watch ukončí.
