---
title: Compiling and Uploading to the PLC
tags: [compile, bytecode, upload, watch, stm32, rs485, usb, ethernet]
level: beginner
see: [getting-started/simulate, getting-started/unit-config, getting-started/rs485-and-modbus, reference/memory, reference/errors]
---

# Compiling and Uploading to the PLC

Maraflow compiles FBD, LD, and ST pages into compact `.mflw` bytecode for the STM32F407 FreeRTOS VM.

## Compile (`F7`)

Click **Compile** on the toolbar (or press `F7`). The Compilation Results window displays:

- **RAM, Flash, and EEPROM usage** (`used / max_allowed`)
- **Estimated execution time per thread** (`Thread 1..4`) and total CPU load
- **Compiler log** with warnings and diagnostics

## Transport Options

```diagram
layout lr
node IDE "Maraflow IDE" sub="MaraProt v1 Client" color=blue
node USB "USB CDC / Ethernet" sub="Always active (boot mode)" color=teal
node RS "RS-485 (USART3)" sub="Active in MaraProt mode" color=orange
edge IDE -> USB "Point-to-point"
edge IDE -> RS "Station ID 1..247"
caption "Supported upload and Watch transports"
```

| Transport | Port / Address | Notes |
| --- | --- | --- |
| **USB CDC** | `/dev/ttyACM*` or `COMx` | Default boot mode; fastest direct connection |
| **Ethernet (TCP/IP)** | IP + port `5020` | Hold `DI14 + DI15` low for 10s to toggle USB/ETH boot mode |
| **RS-485 (Serial)** | `/dev/ttyUSB*` or `COMx`, `115200` 8N1 | Requires `RS-485 Mode = MaraProt v1` in **Unit Config** |

## Upload and Watch Mode

1. Click **Upload** (or **Close and Upload** from Compilation Results).
2. Select the transport (**USB CDC**, **RS485 (Serial)**, or **Ethernet (TCP)**), port/IP, baud rate, and target **Station ID**.
3. Click **Watch** to enter live online monitoring of real PLC pin values. Any structural edit exits Watch mode.
