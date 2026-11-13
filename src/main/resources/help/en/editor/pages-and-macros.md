---
title: Pages and Macro Functions
tags: [pages, macros, hierarchy, tabs]
level: reference
see: [editor/fbd-canvas, getting-started/scan-cycle]
---

# Pages and Macro Functions

A Maraflow project consists of one or more pages shown in the horizontal page bar below the toolbar.

## Top-Level Pages vs. Macro Pages

- **Top-level page**: executes directly on the PLC scan cycle in FBD, LD, or ST.
- **Macro / Function page**: defines reusable logic with input/output interface pins. Once created, it appears under `Macros` in the Library tree and can be instantiated on any FBD page.

Double-click a macro block instance or use its context menu to inspect the underlying macro page.
