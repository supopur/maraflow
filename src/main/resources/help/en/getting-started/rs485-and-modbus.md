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

## Modbus RTU Slave Register Map (`MCOx` / `MDIx` / `MHRx` / `MIRx`)

When **RS-485 Mode** is set to **Modbus RTU Slave**, the PLC responds on its configured **Station ID** (`1..247`, default `1`) to Function Codes `01, 02, 03, 04, 05, 06, 15, 16` using the custom mapping defined in **Unit Config -> Modbus Slave Map** (up to 64 entries):

| Prefix | Table | PLC Direction | Valid Targets |
| --- | --- | --- | --- |
| **`MCOx`** | `MCO - Modbus Coil` | Read / Write | Free `BOOL` **Input Pins** (`And1.X1`), `BoolVar`, `M*`, `DO*` |
| **`MDIx`** | `MDI - Modbus Discrete Input` | Read-Only | Any `BOOL` **Output Pin** (`And1.Y`), `DI*`, `DO*`, `BoolVar`, `M*` |
| **`MHRx`** | `MHR - Modbus Holding Register` | Read / Write | Free `INT`/`FLOAT` **Input Pins**, `IntVar`/`FloatVar`, `REG_*`, `AO*` |
| **`MIRx`** | `MIR - Modbus Input Register` | Read-Only | Any `INT`/`FLOAT` **Output Pin**, `AI*`, `AO*`, `IntVar`/`FloatVar`, `REG_*` |

### 1-Driver Input Pin Mutex & KiCad Driver Flags

Every **block input pin** on the FBD canvas can have **at most one driver** (a normal Wire, a **Visual Button**, or a writable **`MCOx` / `MHRx`** Modbus address). Non-wire drivers render as a single-ended KiCad net flag (`< MCO0 |`, `< StartBtn |`) on the pin; mapped output pins show a right-side flag (`| MDI0 >`, `| MIR1 >`).

### Maraview SCADA Integration

- **MaraProt (`MFL_LINK`):** `Maraview` drives `xVar` blocks (`BoolVar`, `IntVar`, `FloatVar`), `REG_*`, and **Visual Buttons** (syncing button state live), while reading any block output pin.
- **Modbus RTU (`MODBUS`):** Importing the `.mfl` file in `Maraview` automatically imports your `MCOx`/`MDIx`/`MHRx`/`MIRx` bindings.

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
