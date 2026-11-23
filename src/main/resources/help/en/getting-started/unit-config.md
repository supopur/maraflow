---
title: Unit & Hardware Configuration
tags: [unit-config, station-id, ethernet, dhcp, rs485, estop, boot]
level: beginner
see: [getting-started/upload, getting-started/rs485-and-modbus]
---

# Unit & Hardware Configuration

Open **Unit Config** from the toolbar to configure identity, networking, RS-485, status LEDs, boot behavior, and E-Stop rules. When connected to a live PLC, clicking **Write to Unit** saves the settings into the onboard `BL24C256A` EEPROM.

```diagram
layout td
node UI "Unit Config Dialog" sub="Maraflow IDE" color=blue
node PROT "OP_UNIT_CONFIG_SET" sub="MaraProt v1 (USB / ETH / RS-485)" color=teal
node EEP "BL24C256A EEPROM" sub="Persisted across reboots" color=green
edge UI -> PROT "50-byte config frame"
edge PROT -> EEP "CRC-32 verified write"
caption "Unit Config persistence flow"
```

## Configuration Sections

- **Hardware & Identity:**
  - `Unit Name`: Hostname / label (up to 15 chars, default `duinos`).
  - `Station ID`: Unit address (`1..247`, default `1`) used by both MaraProt over RS-485 and Modbus RTU Slave mode.
- **Network / IP Configuration:**
  - `IP Mode`: `DHCP` or `Static IP` (default Static `192.168.1.77/24`, MaraProt TCP port `5020`).
  - **Hardware USB / Ethernet Toggle:** Hold `DI14` and `DI15` low simultaneously for **10 seconds** to toggle boot transport between USB CDC and Ethernet LwIP.
- **RS-485 / Serial Bus Configuration:**
  - `RS-485 Mode`: `MaraProt v1`, `Modbus RTU Master`, or `Modbus RTU Slave`.
  - `RS-485 Baud Rate`: `9600` to `921600` baud (default `115200`).
- **Status Outputs, Boot & Security:**
  - Map `RUN`, `FAULT`, and `E-STOP` indicators to `DO0..DO15`.
  - `Boot Mode`: `Auto-Run saved program on boot` or `Boot to STOP`.
  - `Access Password`: Protects uploads and config writes with SHA-256 challenge-response + stream encryption.
