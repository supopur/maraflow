---
title: ST Keywords Reference
tags: [st, keywords, reference, var, if, for, while, repeat, case, return, exit]
level: reference
see: [st/syntax, st/tutorial]
---

# ST Keywords Reference

All ST keywords in Maraflow are case-insensitive.

## VAR

Declares local persistent variables retained across scan cycles. Terminated by `END_VAR`.

```st
VAR
    Counter : INT := 0;
    Active  : BOOL := FALSE;
END_VAR
```

## VAR_INPUT

Declares input variables (exposed as input pins when the ST page is used as a Macro). Terminated by `END_VAR`.

## VAR_OUTPUT

Declares output variables (exposed as output pins when the ST page is used as a Macro). Terminated by `END_VAR`.

## VAR_TEMP

Declares temporary variables scoped to the page. Terminated by `END_VAR`.

## IF

Conditional branch (`IF cond THEN ... ELSIF cond2 THEN ... ELSE ... END_IF;`). Also accepts `ELSEIF` and `ENDIF` aliases.

## CASE

Multi-way integer branch (`CASE expr OF 0: ... 1, 2: ... 3..5: ... ELSE ... END_CASE;`). Also accepts `ENDCASE`.

## FOR

Counted loop (`FOR i := 0 TO 10 BY 1 DO ... END_FOR;`). Also accepts `ENDFOR`. Bounded to at most 1000 iterations per scan cycle to preserve deterministic scan timing.

## WHILE

Pre-condition loop (`WHILE cond DO ... END_WHILE;`). Also accepts `ENDWHILE`. Bounded to 1000 iterations per scan cycle.

## REPEAT

Post-condition loop (`REPEAT ... UNTIL cond END_REPEAT;`). Also accepts `ENDREPEAT`. Bounded to 1000 iterations per scan cycle.

## RETURN

Exits the current ST page immediately for the remainder of the current scan cycle.

## EXIT

Breaks out of the innermost `FOR`, `WHILE`, or `REPEAT` loop.
