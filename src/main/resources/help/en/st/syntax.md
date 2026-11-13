---
title: ST Syntax Reference
tags: [st, syntax, operators, comments, expressions, types, functions]
level: reference
see: [st/tutorial, st/keywords, concepts/data-types]
---

# ST Syntax Reference

## Page Structure

A Maraflow ST page contains optional variable declaration blocks at the top (`VAR`, `VAR_INPUT`, `VAR_OUTPUT`, `VAR_TEMP` terminated by `END_VAR`), followed directly by statements ending with `;`. There is no `PROGRAM ... END_PROGRAM` wrapper.

In ST Macros, `VAR_INPUT` and `VAR_OUTPUT` define the macro block's input and output pins.

## Comments

Three comment styles are supported:

- Single-line: `// comment to end of line`
- IEC block comment: `(* block comment *)`
- C-style block comment: `/* block comment */`

## Data Types and Literals

| Type | Aliases | Description |
|---|---|---|
| `BOOL` | — | Boolean (`TRUE` / `FALSE` or `1` / `0`) |
| `INT` | — | 32-bit signed integer (default for unrecognized type names) |
| `LONG` | `LINT`, `DINT` | 64-bit signed integer |
| `FLOAT` | `REAL`, `LREAL` | Floating-point number |

Numeric literals support underscores and base prefixes:

- Decimal: `42`, `3.1415`, `1_000`
- Hexadecimal: `16#FF`, `16#1A_2B`
- Binary: `2#1010_0110`
- Octal: `8#77`

## Hardware I/O and Memory Operands

| Operand | Type | Description |
|---|---|---|
| `DI0`, `DI1`, ... | `BOOL` | Digital input channels |
| `DO0`, `DO1`, ... | `BOOL` | Digital output channels |
| `AI0`, `AI1`, ... | `FLOAT` | Analog input channels |
| `AO0`, `AO1`, ... | `FLOAT` | Analog output channels |
| `PWM0`, `PWM1`, ... | `FLOAT` | PWM output duty cycle (`0.0` to `100.0`) |
| `M0`, `M1`, ... | `BOOL` | Internal boolean memory markers |
| `REG_0`, `REG_1`, ... | `INT` / `FLOAT` | Retentive 32-bit registers |

## Operators

| Operator | Meaning |
|---|---|
| `:=` | Assignment (`x := 10;`) |
| `=>` | Output parameter binding (`TON(IN := DI0, PT := 500, Q => DO0);`) |
| `=`, `<>`, `<`, `<=`, `>`, `>=` | Comparison |
| `+`, `-`, `*`, `/`, `**`, `MOD` | Arithmetic (`**` is exponentiation) |
| `AND` (or `&`), `OR`, `XOR`, `NOT` | Boolean / logic |

## Built-in Functions

- **Math**: `ABS`, `SQRT`, `LN`, `LOG`, `EXP`, `SIN`, `COS`, `TAN`, `ASIN`, `ACOS`, `ATAN`, `ROUND`, `TRUNC`, `MIN`, `MAX`, `LIMIT`, `CLAMP`, `ADD`, `SUB`, `MUL`, `DIV`, `MOD`
- **Selection & Logic**: `SEL(G, IN0, IN1)`, `MUX(K, IN0, IN1, ...)`, `AND`, `OR`, `XOR`, `NOT`, `NAND`, `NOR`, `XNOR`, `EQ`, `NE`, `LT`, `LE`, `GT`, `GE`
- **Pulse & Random**: `SINGLEPULSE(IN)` / `ONESHOT(IN)`, `RANDOM([TRIG, ]MIN, MAX)` / `RND([TRIG, ]MIN, MAX)`

## Standard Function Blocks

| FB | Inputs | Outputs | Notes |
|---|---|---|---|
| `TON` (or `TIMER`) | `IN`, `PT` (ms) | `Q`, `ET` (ms) | On-delay timer |
| `TOF` | `IN`, `PT` (ms) | `Q`, `ET` (ms) | Off-delay timer |
| `TP` | `IN`, `PT` (ms) | `Q`, `ET` (ms) | Fixed-width pulse timer |
| `CTU` | `CU`, `RESET` (or `R`), `PV` | `Q`, `CV` | Count-up counter |
| `CTD` | `CD`, `LOAD`, `PV` | `Q`, `CV` | Count-down counter |
| `CTUD` / `COUNTER` / `CNTR` | `CU`, `CD`, `RESET`, `LOAD`, `PV` | `Q`, `CV` | Up/down counter |
| `R_TRIG` / `SINGLEPULSE` / `ONESHOT` | `CLK` | `Q` | Rising-edge detector (1 scan cycle) |
| `F_TRIG` | `CLK` | `Q` | Falling-edge detector (1 scan cycle) |
| `SR` | `S` (or `S1`), `R` | `Q1` | Set-dominant latch |
| `RS` | `S`, `R` (or `R1`) | `Q1` | Reset-dominant latch |

## Control Flow Example

```st
CASE State OF
    0:
        Ready := TRUE;
        Running := FALSE;
    1, 2:
        Ready := FALSE;
        Running := TRUE;
    ELSE
        Fault := TRUE;
END_CASE;
```
