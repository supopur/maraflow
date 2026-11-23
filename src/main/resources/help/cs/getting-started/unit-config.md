---
title: Konfigurace jednotky (Unit Config)
tags: [unit-config, station-id, ethernet, dhcp, rs485, estop, boot]
level: beginner
see: [getting-started/upload, getting-started/rs485-and-modbus]
---

# Konfigurace jednotky (Unit Config)

Dialog **Unit Config** na hlavní liště slouží k nastavení adresy PLC, sítě Ethernet, sběrnice RS-485, stavových výstupů, chování po startu a E-Stop pravidel. Při připojení k PLC tlačítko **Write to Unit** uloží nastavení do paměti EEPROM (`BL24C256A`).

```diagram
layout td
node UI "Unit Config Dialog" sub="Maraflow IDE" color=blue
node PROT "OP_UNIT_CONFIG_SET" sub="MaraProt v1 (USB / ETH / RS-485)" color=teal
node EEP "BL24C256A EEPROM" sub="Uloženo trvale" color=green
edge UI -> PROT "50B konfigurační rámec"
edge PROT -> EEP "Zápis s CRC-32"
caption "Uložení konfigurace do EEPROM v PLC"
```

## Přehled nastavení

- **Hardware & Identity:** `Unit Name` (výchozí `duinos`) a `Station ID` (`1..247`, výchozí `1`) pro RS-485 MaraProt i Modbus RTU Slave.
- **Network / IP Configuration:** `DHCP` nebo `Static IP` (výchozí `192.168.1.77/24`, port `5020`). Podržením `DI14` + `DI15` po dobu **10 sekund** přepnete režim USB CDC / Ethernet.
- **RS-485 / Serial Bus Configuration:** Režim (`MaraProt v1`, `Modbus RTU Master`, `Modbus RTU Slave`) a rychlost (`9600 .. 921600`, výchozí `115200`).
- **Status Outputs & Security:** Mapování `RUN`/`FAULT`/`E-STOP` na `DO0..DO15`, režim po zapnutí (`Auto-Run` / `Boot to STOP`) a přístupové heslo.
