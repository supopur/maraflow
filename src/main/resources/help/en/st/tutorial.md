---
title: Structured Text (ST) Tutorial
tags: [st, tutorial, var, if, ton, io]
level: beginner
see: [st/syntax, st/keywords, concepts/fbd-ld-st]
---

# Structured Text (ST) Tutorial

Structured Text (ST) in Maraflow is an imperative, Pascal-like language suited for state machines, bounded loops, mathematical expressions, and direct hardware I/O logic.

Unlike full desktop IEC 61131-3 environments, Maraflow ST pages do **not** wrap code in `PROGRAM ... END_PROGRAM`. An ST page consists of optional `VAR ... END_VAR` declaration blocks (synchronized bi-directionally with the Variable Declaration Table above the code editor) followed directly by executable statements.

## Example 1: Tank Level Hysteresis Controller

You can reference declared variables as well as hardware channels (`DI0`, `DO0`, `AI0`, `AO0`, `PWM0`, `M0`, `REG_0`) directly:

```st
VAR
    HighLimit  : FLOAT := 850.0;
    LowLimit   : FLOAT := 200.0;
    PumpActive : BOOL := FALSE;
END_VAR

IF AI0 >= HighLimit THEN
    PumpActive := FALSE;
ELSIF AI0 <= LowLimit THEN
    PumpActive := TRUE;
END_IF;

DO0 := PumpActive;
```

## Example 2: Using a Timer (`TON`) in ST

Function blocks such as `TON`, `TOF`, `TP`, `CTU`, `CTD`, `R_TRIG`, `F_TRIG`, `SR`, and `RS` maintain their own internal state per instance name across scan cycles. Timer preset `PT` is specified as a numeric value in **milliseconds** (no `T#` literal is needed).

```st
// Start Pump_TON when DI0 is TRUE; output DO0 turns ON after 1500 ms
Pump_TON(IN := DI0, PT := 1500, Q => DO0);

// You can also read outputs via dot notation:
// DO0 := Pump_TON.Q;
// REG_0 := Pump_TON.ET;
```

## Editor Features

- **Variable Table Sync**: Editing `VAR` / `VAR_INPUT` / `VAR_OUTPUT` / `VAR_TEMP` blocks updates the Variable Declaration Table automatically, and vice versa.
- **Autocomplete (`Ctrl+Space` / `Alt+Space`)**: Suggests declared variables, hardware I/O (`DI0`, `DO0`, `AI0`, `AO0`, `PWM0`, `M0`, `REG_0`), built-in functions, standard function blocks, and code snippets.
- **F1 Help**: Press `F1` with the cursor on any ST keyword (`VAR`, `IF`, `CASE`, `FOR`, `WHILE`, `REPEAT`, `RETURN`, `EXIT`) to jump to its reference entry in [ST Keywords Reference](help:st/keywords).
