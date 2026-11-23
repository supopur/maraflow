---
title: Modbus RTU Write (MbRtuWrite)
tags: [modbus, rtu, rs485, communication, write]
level: intermediate
see: [getting-started/rs485-and-modbus, blocks/Comm.ModbusRTU.MbRtuRead]
---

# MbRtuWrite

Writes a single Coil (`0x`, FC `05`) or Holding Register (`4x`, FC `06`) to a Modbus RTU slave over RS-485 (`USART3`).

> **Prerequisite:** In **Unit Config**, set **RS-485 Mode** to **Modbus RTU Master**.

## Pins

| Pin | Direction | Type | Description |
| --- | --- | --- | --- |
| `En` | Input | `BOOL` | Triggers write when `1` (use `R_TRIG` to write once on an event) |
| `Val` | Input | `INT` | Value to write (`0`/`!=0` for Coils, 16-bit integer for Holding Registers) |
| `Ok` | Output | `BOOL` | `1` when the slave acknowledged the write |
| `Err` | Output | `INT` | `0` on OK; Modbus exception code (`1..4`) or `-1` timeout, `-2` wrong RS-485 mode, `-3` CRC error |

## Example

```fbd
block di  IO.IREF                   at 1,1  name=DI0
block val Visual.Vars.IntVar        at 1,6  name=Setpoint
block wr  Comm.ModbusRTU.MbRtuWrite at 12,1 name=WriteReg
block ok  IO.OREF                   at 24,1 name=DO1
wire di.Y    -> wr.En
wire val.Y   -> wr.Val
wire wr.Ok   -> ok.X
caption "Write IntVar to Holding Register 100 on Slave 2 when DI0 is active"
```
