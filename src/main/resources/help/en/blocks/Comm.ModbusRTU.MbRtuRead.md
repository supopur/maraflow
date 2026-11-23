---
title: Modbus RTU Read (MbRtuRead)
tags: [modbus, rtu, rs485, communication, read]
level: intermediate
see: [getting-started/rs485-and-modbus, blocks/Comm.ModbusRTU.MbRtuWrite]
---

# MbRtuRead

Reads a single Coil (`0x`), Discrete Input (`1x`), Holding Register (`4x`), or Input Register (`3x`) from a Modbus RTU slave over RS-485 (`USART3`).

> **Prerequisite:** In **Unit Config**, set **RS-485 Mode** to **Modbus RTU Master**.

## Pins

| Pin | Direction | Type | Description |
| --- | --- | --- | --- |
| `En` | Input | `BOOL` | Executes transaction while `1` (combine with `R_TRIG` or `PulseGen` for periodic polling) |
| `Val` | Output | `INT` | Register value (signed 16-bit for `3x`/`4x`, or `0`/`1` for `0x`/`1x`) |
| `Ok` | Output | `BOOL` | `1` when the last transaction succeeded |
| `Err` | Output | `INT` | `0` on OK; Modbus exception code (`1..4`) or `-1` timeout, `-2` wrong RS-485 mode, `-3` CRC error |

## Example

```fbd
block p   Main.Timers.PulseGen     at 1,1  name=PollTimer
block rd  Comm.ModbusRTU.MbRtuRead at 11,1 name=ReadReg
block ok  IO.OREF                  at 23,1 name=DO0
wire p.Q   -> rd.En
wire rd.Ok -> ok.X
caption "Poll Holding Register 100 from Slave 2"
```
