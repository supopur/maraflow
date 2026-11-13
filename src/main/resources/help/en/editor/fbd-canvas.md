---
title: FBD Canvas and Wiring
tags: [fbd, canvas, grid, wire, bus, hidden, constants]
level: reference
see: [concepts/data-types, editor/pages-and-macros, editor/shortcuts]
---

# FBD Canvas and Wiring

The FBD canvas uses a strict integer grid. Every block, pin, and orthogonal wire segment snaps to grid cells.

## Placing and Editing Blocks

- **Add block**: drag a leaf entry from the left Library tree and drop it onto the canvas.
- **Edit properties**: double-click a block to configure its Name, Thread ID, Order, Header Color, Description, and hardware channels.
- **Compile-time constant `(=x)`**: unconnected input pins show a white constant box. Double-click the input pin to edit the constant inline.

## Wires, Buses, and Hidden Flags

- **Create wire**: click an output pin and drag to an input pin.
- **Edit route**: right-click a wire to add waypoints, or drag orthogonal segments directly.
- **Bus bundle**: multiple wires between the same pair of blocks can be bundled into a single thick navy bus with 45-degree taps.
- **Hide connection**: right-click a wire and choose **Hide** to replace the line with matching OCON/ICON net flags.
