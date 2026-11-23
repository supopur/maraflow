---
title: RS-485 & Modbus RTU
tags: [rs485, modbus, rtu, serial, wiring, unit-config]
level: intermediate
see: [getting-started/unit-config, getting-started/upload, blocks/Comm.ModbusRTU.MbRtuRead, blocks/Comm.ModbusRTU.MbRtuWrite]
---

# RS-485 & Modbus RTU

The **Duino - S** PLC includes an auto-direction half-duplex **RS-485** transceiver on `USART3` (`PD8` TX / `PD9` RX). Because RS-485 is a shared half-duplex bus, the port is configured for one active role at a time in **Unit Config**.

```diagram
layout bus
node PC "USB / RS-485" sub="Host Adapter" color=gray
node PLC1 "Duino - S #1" sub="Station ID: 1" color=blue
node PLC2 "Duino - S #2" sub="Station ID: 2" color=teal
node VFD "Modbus Device" sub="Slave ID: 3" color=orange
caption "Multi-drop RS-485 bus with 120 Ω termination resistors at both physical ends"
```

## Wiring & Electrical Rules

- Connect **A+ to A+** and **B- to B-** across all devices in a daisy chain (avoid star topologies).
- Connect **GND** (reference common) between devices when they do not share a power supply ground.
- Install **120 Ω termination resistors** across `A+` and `B-` at the two farthest physical ends of the bus.
- **Auto-Direction:** The onboard transceiver handles TX/RX switching automatically; no DE/RE control pin is needed.

## RS-485 Operating Modes

Select the mode and baud rate (`9600` to `921600`, default `115200`) in **Unit Config -> RS-485 / Serial Bus Configuration**:

| Mode | Purpose | Concurrent USB / Ethernet? |
| --- | --- | --- |
| **MaraProt v1** | Program upload, Watch mode, and Unit Config over RS-485 | Yes — USB CDC / TCP `5020` stay active |
| **Modbus RTU Master** | PLC executes `MbRtuRead` / `MbRtuWrite` blocks to poll slaves | Yes — use USB/ETH for Watch while RS-485 polls fieldbus |
| **Modbus RTU Slave** | PLC responds to an external Modbus RTU master (SCADA or another PLC) | Yes — use USB/ETH for Watch while RS-485 serves Modbus |

> **Why not MaraProt and Modbus Master on RS-485 at the same time?**  
> Half-duplex RS-485 only permits one bus master. If the PLC is polling Modbus slaves while a PC also sends MaraProt frames on the same `A+/B-` pair, their transmissions will collide. Use **USB CDC** or **Ethernet** for Maraflow Watch when RS-485 is in Modbus mode.

```diagram
layout lr
node PC "Maraflow IDE" sub="USB CDC or TCP:5020" color=blue
node PLC "Duino - S PLC" sub="Station ID: 1" color=teal
node SLAVE "Remote PLC / Drive" sub="Slave ID: 2" color=orange
edge PC <-> PLC "MaraProt v1"
edge PLC <-> SLAVE "Modbus RTU (RS-485)"
caption "Recommended setup: Maraflow Watch over USB/Ethernet while RS-485 runs Modbus RTU"
```

## Modbus RTU Slave Register Map

When **RS-485 Mode** is set to **Modbus RTU Slave**, the PLC responds on its configured **Station ID** (`1..247`, default `1`) to Function Codes `01, 02, 03, 04, 05, 06, 15, 16` using the custom mapping defined in **Unit Config -> Modbus Slave Map** (up to 64 entries):

- **Modbus Table:** `Coil (0x)`, `Discrete Input (1x)`, `Holding Register (4x)`, or `Input Register (3x)`.
- **Address (`0..65535`):** Any Modbus address you choose for your SCADA or master PLC.
- **PLC Target:** Map to hardware I/O (`DI0..DI27`, `DO0..DO27`, `AI0..AI3`, `AO0..AO1`), a project **Virtual Register** (`REG_0`, etc.), or a raw VM slot (`BOOL:N`, `INT:N`, `FLOAT:N`).
- **Read-Only:** Optional flag to block Modbus writes to a mapped target.

Unmapped addresses return Modbus Exception `0x02` (Illegal Data Address).

## Example: Reading a Remote Slave (`MbRtuRead`)

```fbd
block p    Main.Timers.PulseGen     at 1,1  name=PollTimer
block rd   Comm.ModbusRTU.MbRtuRead at 11,1 name=ReadSlave2
block led  IO.OREF                  at 23,1 name=DO2
wire p.Q   -> rd.En
wire rd.Ok -> led.X
caption "Poll Holding Register 100 from Modbus Slave 2 on each pulse"
```
