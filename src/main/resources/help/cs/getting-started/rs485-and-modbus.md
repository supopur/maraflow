---
title: RS-485 a Modbus RTU
tags: [rs485, modbus, rtu, serial, wiring, unit-config]
level: intermediate
see: [getting-started/unit-config, getting-started/upload, blocks/Comm.ModbusRTU.MbRtuRead, blocks/Comm.ModbusRTU.MbRtuWrite]
---

# RS-485 a Modbus RTU

PLC **Duino - S** obsahuje poloduplexní transceiver **RS-485** s automatickým řízením směru na `USART3` (`PD8` TX / `PD9` RX). Protože RS-485 je sdílená sběrnice, v **Unit Config** se volí jeden aktivní režim portu.

```diagram
layout bus
node PC "USB / RS-485" sub="Převodník" color=gray
node PLC1 "Duino - S #1" sub="Station ID: 1" color=blue
node PLC2 "Duino - S #2" sub="Station ID: 2" color=teal
node VFD "Modbus zařízení" sub="Slave ID: 3" color=orange
caption "Sběrnice RS-485 se zakončovacími odpory 120 Ω na obou koncích"
```

## Zapojení sběrnice

- Propojte **A+ na A+** a **B- na B-** za sebou (topologie daisy-chain, vyhněte se hvězdici).
- Propojte společnou zem **GND**, pokud zařízení nemají společné napájení.
- Na oba fyzické konce sběrnice zapojte zakončovací odpor **120 Ω** mezi `A+` a `B-`.
- **Auto-Direction:** Převodník na desce přepíná směr TX/RX automaticky bez potřeby pinu DE/RE.

## Režimy RS-485

Režim a přenosovou rychlost (`9600` až `921600`, výchozí `115200`) nastavíte v **Unit Config -> RS-485 / Serial Bus Configuration**:

| Režim | Použití | Souběžné USB / Ethernet? |
| --- | --- | --- |
| **MaraProt v1** | Nahrávání programu, Watch režim a Unit Config přes RS-485 | Ano — USB CDC / TCP `5020` zůstávají aktivní |
| **Modbus RTU Master** | PLC spouští bloky `MbRtuRead` / `MbRtuWrite` pro dotazování slave stanic | Ano — pro Watch použijte USB nebo Ethernet |
| **Modbus RTU Slave** | PLC odpovídá nadřazenému Modbus Masteru (SCADA nebo jiné PLC) | Ano — pro Watch použijte USB nebo Ethernet |

```diagram
layout lr
node PC "Maraflow IDE" sub="USB CDC nebo TCP:5020" color=blue
node PLC "Duino - S PLC" sub="Station ID: 1" color=teal
node SLAVE "Vzdálené PLC / Měnič" sub="Slave ID: 2" color=orange
edge PC <-> PLC "MaraProt v1"
edge PLC <-> SLAVE "Modbus RTU (RS-485)"
caption "Doporučené zapojení: Watch přes USB/Ethernet, zatímco RS-485 běží jako Modbus RTU"
```

## Mapa registrů Modbus RTU Slave (`MCOx` / `MDIx` / `MHRx` / `MIRx`)

V režimu **Modbus RTU Slave** PLC odpovídá na své adrese **Station ID** (`1..247`) na funkční kódy `01, 02, 03, 04, 05, 06, 15, 16` podle uživatelského mapování v **Unit Config -> Modbus Slave Map** (až 64 položek):

- **`MCOx` (Modbus Coil, R/W):** Volné `BOOL` vstupní piny, `BoolVar`, `M*`, `DO*`.
- **`MDIx` (Modbus Discrete Input, R/O):** Libovolný `BOOL` výstupní pin, `DI*`, `DO*`, `BoolVar`, `M*`.
- **`MHRx` (Modbus Holding Register, R/W):** Volné `INT`/`FLOAT` vstupní piny, `IntVar`/`FloatVar`, `REG_*`, `AO*`.
- **`MIRx` (Modbus Input Register, R/O):** Libovolný `INT`/`FLOAT` výstupní pin, `AI*`, `AO*`, `IntVar`/`FloatVar`, `REG_*`.

Každý **vstupní pin** má striktně **1 zdroj (mutex)** (drát, Visual Button nebo `MCOx`/`MHRx`), který se na schématu zobrazuje jako jednostranná KiCad vlaječka (`< MCO0 |`, `< StartBtn |`). `Maraview` ovládá `xVar` proměnné a Visual Buttony a automaticky importuje `MCOx`/`MDIx`/`MHRx`/`MIRx` vazby z `.mfl` projektu.

Nenamapované adresy vrací Modbus výjimku `0x02` (Illegal Data Address).
