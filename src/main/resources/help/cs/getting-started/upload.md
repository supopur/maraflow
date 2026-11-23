---
title: Kompilace a nahrání do PLC
tags: [compile, bytecode, upload, watch, stm32, rs485, usb, ethernet]
level: beginner
see: [getting-started/simulate, getting-started/unit-config, getting-started/rs485-and-modbus, reference/memory, reference/errors]
---

# Kompilace a nahrání do PLC

Maraflow překládá stránky FBD, LD a ST do kompaktního bajtkódu `.mflw` pro STM32F407 FreeRTOS VM.

## Kompilace (`F7`)

Klikněte na **Compile** na liště (nebo stiskněte `F7`). Okno výsledků kompilace zobrazí:

- **Využití RAM, Flash a EEPROM** (`použito / maximum`)
- **Odhadovanou dobu běhu vláken** (`Thread 1..4`) a celkové zatížení CPU
- **Výpis překladače** včetně varování a diagnostiky

## Možnosti připojení

```diagram
layout lr
node IDE "Maraflow IDE" sub="MaraProt v1 Klient" color=blue
node USB "USB CDC / Ethernet" sub="Vždy aktivní" color=teal
node RS "RS-485 (USART3)" sub="Aktivní v režimu MaraProt" color=orange
edge IDE -> USB "Point-to-point"
edge IDE -> RS "Station ID 1..247"
caption "Podporované přenosové kanály pro Upload a Watch"
```

| Rozhraní | Port / Adresa | Poznámka |
| --- | --- | --- |
| **USB CDC** | `/dev/ttyACM*` nebo `COMx` | Výchozí režim po zapnutí; nejrychlejší přímé spojení |
| **Ethernet (TCP/IP)** | IP + port `5020` | Podržením `DI14 + DI15` po dobu 10 s přepnete USB/ETH režim |
| **RS-485 (Serial)** | `/dev/ttyUSB*` nebo `COMx`, `115200` 8N1 | Vyžaduje `RS-485 Mode = MaraProt v1` v **Unit Config** |

## Nahrání a režim Watch

1. Klikněte na **Upload** (nebo **Close and Upload** z výsledků kompilace).
2. Vyberte rozhraní (**USB CDC**, **RS485 (Serial)** nebo **Ethernet (TCP)**), port/IP, přenosovou rychlost a adresu **Station ID**.
3. Kliknutím na **Watch** přejdete do živého sledování reálných hodnot z PLC. Úprava schématu režim Watch ukončí.
