# Maraflow PLC Programming IDE — Comprehensive Technical Specification

---

## 0. Original User Prompt (Verbatim)

```text
We need to design a win 95/7 crossover FUNCTIONAL NOT BEUTIFUL PLC Programming software
Inspiration is attached in pictures.
What we need:
1. A main editor window split into sections/windows (only maybe done with jdesktoppane)
2. A window/section on the left with all the blocks, this will be a tree hiearchy. 
 Categories split into:
   - Bool
   - Math (self explanatory)
   - I/O (inputs outputs)
   - Communication Further into: (Modbus RTU, Modbus TCP, REST, DS18B20, I2C, SPI)
   - Main Further into: (Comparators, Timers, Switches.....)
   - Visual (in editor visualisation (scada for the poor) - mainly for 7 segment, displaying values...
3. On the most top of the window: File, Edit, View, Project, Communication, Options, Window, Help
4. The title bar will show this: {project_name} ({project_path}) - Maraflow V{version}
5. Main editor window for FBD (for now only FBD is supported but other IEC languages will be implemented)
 This editor window will have a quick tool bar on top of the editor window with:
  Tools (cursor, graphical line, graphical square (plus custom ngon, hexagon, other shapes... all in this one button, selectable just like in photoshop - hold tool icon to show a submenu where you can select the desired tool which then is set as the main tool of the tool button/icon), graphical circle/oval (multiple tools in one, selection work just like in photoshop - same as the square tool), Text tool, Visual button tool, after the tools will be a splitter, after that we will have the undo and redo, these buttons have a dropdown which shows the last 50 redo/undo operations stored allowing the user to quickly fallback to said operation (just like in tools like illustrator), after that another splitter, after that splitter we will have simulation tools - play, pause, halt/stop, sim speed multiplier (dropdown), after that another splitter, after said splitter we will have the button for sorting blocks graphically, and for sorting block execution order (left to right), another splitter after that, after this splitter we will have hardware plc options: compile program (into bytecode) - opens a new modal window (separate from main) which shows stuff like memory usage, flash usage, estimated execution times per thread, total cpu usage (estimated) these values will be shown as in x/max_allowed and next to a progress bar using x as the progress and max allowed as the max value. after compile will be the upload button - this uploads the compiled bytecode program into our PLC, pressing this button opens a "Communication" modal window in which the user selects the upload method (Ethernet, RS485, USB...) Baud rates, ports, addresses, passwords... (more on this window later) - after this window is exited this flow doesnt need to do anything else, after the upload will be the "Watch" button - this watches the plc's real variables and block outputs... - clicking this button opens the communication window mentioned earlier, after the comm window opens with a connection success we will transition the editor into a watch mode (no local simulation, editing any block exits simulation... after this button will be the unit/PLC config button - which opens a config window - TO BE DISCLOSED. This is all for the top bar
 Under the top bar will be a page list (horizontal) - pages can either be a macro/function (set of blocks with inputs and outputs) which can be placed into a main project page. Or a Top level project page (which actually gets executed on it's own)
This is for the top things, The main thing is the actual block view. The block view background is consitsted of dots (showing the placement grid of our workspace (when placing blocks these dots will perfectly align with the edges of blocks - this is achieved by blocks having pre-defined sizes of headers/names/block info the cyan/tiel thing of blocks in reference photos) and by having a pre-defined block I/O pin sizes.
 Users can zoom in/out in the block view, when a project doesnt fit on the users screen scrollbars will show up.
 Placing blocks is done by dragging and dropping the most lowest hiearchy of a tree entry for example (do not use this exact schema): Logic/Bool/And - And is the lowest hiearchy here - thus it's a block. Dragging and dropping doesnt show you the block until you release your lmb over the block view - use a system similar to file drag and dropping
 Moving blocks in the block view is not free, We have a strict x y int (not float) placement system - aligning with our grid system, thus moving blocks moves them on the grid, not freely
 Blocks are inter-connected using "cables/wires" there are 2 types of wires (just like in kicad) bus and standart wire/signal. Busses consist of multiple signals and are only used for a visual organization and in no way do they affect the simulation/compilation output/result. These wires are colored based on the data type they are carrying, Connecting wires to different data type pins on both sides is allowed since we can perform conversions. This will be illustrated with one side (by lenght) of the wire being a dfiferent color of the other (with both sides having their respective color/data type) - these are purely visual and should in no way be passed to the simulation engine which only cares about: And1.Y -> Add1.X1
 The tricky part about this is how to route said wires, the user will not manually place points such as in kicad, but instead will hover over the pin with their mose (nothing shows up, im only telling you this so you can imagine better) after that he presses his LMB and starts to hold it and drag his cursor over to the desired end connection (during this dragging action the wire is shown as a line with point0 being the starting pin and point1 being the current cursor position - watch for perfomance we do not need 700FPS) after that the user drags the cursor over to the desired end pin and hovers over it releasing his mouse - which triggers a connection of the cable, this connection will use a shortest route algorythm and will in no way try to avoid colissions.
 Since we do not check for colissions we need a way for the user to be able to edit the connection. This will be done by allowing the user to quickly add points (either right clicking the connection and a context menu shows up with options such as add point, remove connection, hide connection (this makes it so the connection isnt shown, but instead labels are shown on the pins of both blocks (that will indicate input/output direction (same as in kicad - a triangle pointing either into the block (on the side of the connection) or out of the block - On the opposite site of the connection (attached in attachments a screenshot from kicad hiearchical pins) Anyways back to the re-defining connections. Users can add the points as i mentioned, and move the edges of wires (like in blender) - not the points/vertex
Pins of blocks have this format/design scheme:
 - Pins on the left side of blocks are inputs
 - Pins on the right are outputs
 - Pins are color coded to represent the data type (same color scheme as wires)
 - Pins show their name with a max len of 3 chars (to comply with our grid, thus pre defined width of pins) - show full name on hover in a tooltip (is it what it's called?)
 - Unconnected pins have a container with a solid white background, 1px black outline and a label inside it with this content: (=x) with x being the *Pre-defined value*. Pre defined values are compile time constants.
 - Output pins do not show this container when editing/stopped. But show them when a simulation is running or when monitoring a PLC. And they show the value of the output (pretty obvious).
 - Connected input pins do not show this container under any circumstance
 - Double-clicking the input pin (not the value container) changes the label inside the container to a textbox (which will be focused) so the user can set the compile time constant
Block design scheme:
 - Dark grey background
 - Input pins are stored in a vertical container with 0 padding, on the left side of the block
 - Same for output pins (but on the right)
 - A "header" is shown on each block, this is a teal color background with a text on the top (block type (and, or, xor) and it's name (And1, And2, CustomName)
 - Blocks have a 1 px black outline around the block courtyard, around the pin's containers (not individual pins - also keep in mind this pin's container always stretches all the way from the bottom of the block header to the bottom of the block, even tho when the container is not filled) headers also have the same outline. But keep in mind the total outside outline will be 1 px, do not add up the outline of the header and the total block outline.. do not add up the header outline with the pin container outline.... Use padding 0... use reference photos for block designs
 - Double clicking a block opens a modal window with a table inside it, first column of the table (Property) shows all the properties (Name, ThreadId, Order, Color - (color of the header), Description - (hover over a block and it shows a tooltip with the description. And also under this table (or maybe next to it since we have more horizontal space on a normal monitor) will be block specifics, for example for Input IO blocks we will have a list of selection boxes with pin ids....
Under all of that at the pottom will be a Ok and Cancel button (with icons on the left so: {ICON} {Text}. Use a green checkmark and a red X/cross icon - ICON NOT EMOJI



Other design notes:
- THERE WILL BE 0 EMOJIS IN THIS CODEBASE AND FRONTEND
- We are aiming for a plainly utilistic style - fancyness isn't a thing here. This program is for PLC engineers, not tech bros.
- Do not tell the user how to wipe their ass, the indended user is a tech literate PLC programmer, never suggest what a user can do - he knows it already
```

---

## 1. System Overview & Core Rules

### 1.1 Aesthetic & UX Principles
1. **Utilitarian Win95 / Win7 Crossover UI:** Crisp 1px borders, zero border-radius (`arc = 0`), high-density controls, classic beveled/gradient panel headers, and angled dotted tree connectors (`JTree.lineStyle = "Angled"`).
2. **Strict Zero-Emoji Policy:** No Unicode emojis anywhere in the source code, comments, logs, or UI. All icons must be rendered programmatically via custom `javax.swing.Icon` vector painters (`Graphics2D`) or loaded from raster/vector icon resources.
3. **No Hand-Holding:** No onboarding popups, hints, or "getting started" overlays. Error dialogs are direct and technical.

### 1.2 Technology Stack & Packaging
- **Language & Runtime:** Java 27 (`maven.compiler.source = 27`, `maven.compiler.target = 27`), utilizing modern Java 27 performance features (virtual threads for async simulation/serial I/O, primitive patterns, value/compact object headers, Vector/FFM APIs where applicable).
- **UI Framework:** Swing with customized `FlatLightLaf` configured for sharp corners (`Button.arc = 0`, `Component.arc = 0`, `TextComponent.arc = 0`, `ScrollBar.width = 14`).
- **Native Binary Packaging:** Compiled into self-contained platform-specific binaries (`jpackage` / `jlink` runtime image or GraalVM native image) so target engineering machines do not need a pre-installed JRE.
- **Project File Extension:** `.mfl` (Maraflow Project File).

### 1.3 Target PLC Hardware Architecture
- **MCU:** STM32F407 (ARM Cortex-M4F @ 168 MHz, 192 KiB SRAM, 1 MiB Flash).
- **OS & Runtime:** FreeRTOS running a deterministic custom Bytecode Virtual Machine (VM).
- **External Non-Volatile Memory:** 64 KiB External EEPROM (via I2C/SPI) for retentive parameters, constants, and menu/program metadata.
- **Hardware Profiles:** Each project specifies a target PLC Model (e.g., `Maraflow-F407-Std`, `Maraflow-F407-Mini`, `Maraflow-F407-IO64`). The IDE knows the exact count and capabilities of `DI`, `DO`, `AI`, `AO`, `PWM`, `Timers`, and `DAC` channels per profile. Uploading to a mismatched target model triggers a warning dialog with an option to proceed anyway.

---

## 2. Main Window Architecture & Dockable Workspace

### 2.1 Application Title Bar
- **Saved Project:** `{project_name} ({project_path}) - Maraflow V{version}`
  - Example: `boiler_ctrl (/home/mat/projects/boiler_ctrl.mfl) - Maraflow V1.0.0`
- **Unsaved / New Project:** `Unsaved* - Maraflow V{version}`
- **Modified Saved Project:** `{project_name}* ({project_path}) - Maraflow V{version}`

### 2.2 Workspace Layout
- **Single Active Project:** Only one project is open at a time, containing multiple top-level pages and macro pages.
- **No Bottom Status Bar:** The workspace extends flush to the bottom border of the main `JFrame`.
- **Dockable Panel System:**
  - The workspace uses a custom dockable split-pane container (`DockingManager`) with Win7-style panel headers (gradient title bar, title label on the left, `16x16` `[X]` close button on the right).
  - **Left Dock:** `Library` (Block Tree Hierarchy).
  - **Center Dock (Main Editor Area):** `Project - {filename}` panel containing:
    1. Editor Quick Toolbar (top).
    2. Horizontal Page Tab Bar (directly under toolbar).
    3. FBD Canvas inside a `JScrollPane` (scrollbars appear automatically when content exceeds viewport).
  - **Bottom / Right Optional Dockable Panels (Toggled via `Window` menu):**
    - `PLC Log` (compiler output, runtime diagnostics, communication frames).
    - `Scope` (real-time multi-channel oscilloscope plotting live output pins during Simulation or Watch mode).
    - `Watch Table` (tabular view of pinned variables and force/override controls).

---

## 3. Top Menu Bar & Shortcuts

The menu bar sits at the very top of the `JFrame` (`File`, `Edit`, `View`, `Project`, `Communication`, `Options`, `Window`, `Help`). Note: `Ctrl` maps to `Cmd` on macOS via `Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx()`.

### 3.1 `File`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `New Project` | `Ctrl+N` | Prompts to save if dirty, initializes a blank `.mfl` project |
| `Open Project...` | `Ctrl+O` | Opens `.mfl` file chooser |
| `Recent Projects` | *(Submenu)* | Lists last 10 `.mfl` file paths |
| *Separator* | | |
| `Save` | `Ctrl+S` | Saves current project to `.mfl` |
| `Save As...` | `Ctrl+Shift+S` | Saves current project to a new `.mfl` path |
| *Separator* | | |
| `Import Page / Macro...` | *(None)* | Selects an external `.mfl` file, lists its Pages & Macros in a modal picker, and imports the chosen page/macro into the active project |
| `Export to PDF...` | *(None)* | Renders current page or all pages to vector PDF |
| *Separator* | | |
| `Close Project` | *(None)* | Closes current project and returns to an empty workspace |
| `Exit` | `Alt+F4` / `Ctrl+Q` | Prompts if unsaved changes exist, then terminates |

### 3.2 `Edit`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `Undo` | `Ctrl+Z` | Reverts last operation |
| `Redo` | `Ctrl+Y` / `Ctrl+Shift+Z` | Re-applies last undone operation |
| *Separator* | | |
| `Cut` | `Ctrl+X` | Cuts selected blocks, decorative shapes, and internal wires |
| `Copy` | `Ctrl+C` | Copies selected blocks, decorative shapes, and internal wires |
| `Paste` | `Ctrl+V` | Pastes clipboard contents offset by `(2, 2)` grid units |
| `Duplicate` | `Ctrl+D` | Immediately clones selection offset by `(2, 2)` grid units |
| `Delete` | `BackSpace` / `Delete` | Deletes selected blocks, shapes, or selected wire |
| *Separator* | | |
| `Select All` | `Ctrl+A` | Selects all blocks and decorative shapes on active page |
| `Find Block...` | `Ctrl+F` | Opens quick block search modal by Name/Type/Order |

### 3.3 `View`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `Zoom In` | *(None)* | Steps zoom level up |
| `Zoom Out` | *(None)* | Steps zoom level down |
| `Zoom 100%` | *(None)* | Resets zoom to 1:1 (`100%`) |
| `Fit to Screen` | *(None)* | Scales and centers viewport on bounding box of all page items |
| *Separator* | | |
| `Show Grid` | *(None)* | Checkbox toggle for dotted placement grid |
| `Show Execution Info` | *(None)* | Checkbox toggle for `T: x` / `O: y` overlay on blocks |

### 3.4 `Project`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `Compile Program` | `F7` | Compiles project to STM32F407 VM bytecode and opens `Compilation Results` modal |
| *Separator* | | |
| `Sort Blocks Graphically` | *(None)* | Auto-arranges blocks into topological columns on the grid |
| `Sort Execution Order` | *(None)* | Recalculates `Order` (`10, 20, 30...`) using current sort mode |
| *Separator* | | |
| `Add Page...` | *(None)* | Appends a new top-level page |
| `Add Macro...` | *(None)* | Appends a new macro page (name prefixed with `*`) |
| *Separator* | | |
| `PLC / Unit Configuration...` | *(None)* | Opens the `Unit / PLC Config` modal |

### 3.5 `Communication`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `Communication Settings...` | *(None)* | Opens the `Communication` modal |
| `Upload to PLC...` | *(None)* | Opens `Communication` modal and uploads compiled bytecode |
| `Upload and Watch...` | *(None)* | Uploads bytecode and transitions directly into `Watch` mode |
| `Watch PLC` | *(None)* | Connects via `Communication` modal and starts live monitoring |
| `Disconnect` | *(None)* | Terminates active `Watch` session |
| *Separator* | | |
| `Read Program from PLC...` | *(None)* | Downloads bytecode/project image from PLC if stored |
| `Synchronize PLC RTC` | *(None)* | Writes host PC UTC/local timestamp to PLC hardware RTC |

### 3.6 `Options`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `Settings...` | `Ctrl+,` | Opens global IDE preferences (default comm port, grid colors, auto-save, backup paths) |

### 3.7 `Window`
| Item | Shortcut | Action |
| :--- | :--- | :--- |
| `Library` | *(None)* | Checkbox toggle to show/hide the left `Library` dock panel |
| `PLC Log` | *(None)* | Checkbox toggle to show/hide the `PLC Log` dock panel |
| `Scope` | *(None)* | Checkbox toggle to show/hide the `Scope` oscilloscope dock panel |
| `Watch Table` | *(None)* | Checkbox toggle to show/hide the `Watch Table` dock panel |
| *Separator* | | |
| `Reset Window Layout` | *(None)* | Restores default dock positions and sizes |

### 3.8 `Help`
- Placeholder menu (`About Maraflow`).

---

## 4. Editor Quick Toolbar & Interactive Tools

Located directly at the top of the main editor panel, left-to-right:

```
[Cursor] [Line] [Shape v] [Circle v] [Text] [Button] | [Undo |v] [Redo |v] | [Play] [Pause] [Stop] [SimSpeed v] | [SortGraph] [SortOrder |v] | [Compile] [Upload] [Watch] [PLCConfig] | Thread: [1 (1s) v]
```

### 4.1 Group 1: Canvas & Decorative Tools
All tool buttons belong to a mutually exclusive `ButtonGroup`.
1. **Cursor Tool (`Select`):**
   - Click block/shape to select (`Ctrl/Cmd + Click` to toggle multi-selection).
   - Drag on empty canvas to draw a **Selection Rectangle (Marquee)**. The marquee selects **blocks and decorative shapes only** (wires are not selected by the marquee, though internal wires between selected blocks are preserved when moving, copying, pasting, or duplicating).
   - Drag selected blocks/shapes to move them on the integer grid.
   - Drag from any pin to another pin to route a wire.
   - Drag any middle segment (edge) of an existing wire to adjust its orthogonal routing.
2. **Graphical Line Tool:**
   - Click and drag on canvas (snapped to grid) to place a decorative annotation line.
3. **Graphical Square / Polygon Flyout Button:**
   - Displays the currently active polygon tool icon with a small black `4x4` right-triangle in the bottom-right corner.
   - **Flyout Activation:** Holding LMB for `250ms` or clicking RMB opens a vertical popup menu containing:
     - `Rectangle`
     - `Rounded Rectangle`
     - `Triangle`
     - `Pentagon`
     - `Hexagon`
     - `Octagon`
     - `Custom N-Gon...` (prompts or uses property setting for $N \in [3, 32]$)
   - Selecting an item sets it as the active icon/tool on the button.
4. **Graphical Circle / Oval Flyout Button:**
   - Displays a bottom-right flyout corner triangle (`250ms` LMB hold or RMB click).
   - Sub-tools:
     - `Circle`
     - `Ellipse / Oval`
     - `Arc`
     - `Pie / Sector`
5. **Text Tool (`T`):**
   - Click on grid to place a decorative text label. Double-click existing text, line, or shape to open a `Graphic Element Properties` modal (Stroke Color, Fill Color, Stroke Width, Line Style `[Solid, Dashed, Dotted]`, Font Size, Text Alignment, Z-Order `[Bring to Front, Send to Back]`).
6. **Visual Button Tool:**
   - Places an interactive push/toggle button widget on the canvas (snapped to grid).
   - Double-clicking the Visual Button opens its properties dialog:
     - **Label:** Button text displayed on the widget.
     - **Mode:** `Momentary (Push)` (outputs `1` while LMB held, `0` on release) or `Toggle (Switch)` (flips `0`/`1` on click).
     - **Target Input Pin Selector:** A searchable list of all unconnected or overridable block input pins in the current page formatted as `BlockName.PinName` with a live filter search bar at the top.
   - Note: The Visual Button drives the bound `BlockName.PinName` during Local Simulation and PLC Watch mode.

*--- Toolbar Splitter (`|`) ---*

### 4.2 Group 2: History (Undo / Redo)
7. **Undo Split-Button:**
   - Main button undoes the latest action (`Ctrl+Z`).
   - Right dropdown arrow opens a popup list of up to **50 stored undo operations** (newest at top, e.g., `"Move Block And1"`, `"Connect 8xDI1.DI0 -> And1.X1"`, `"Set Constant And1.X2 = 1"`).
   - Hovering over row $k$ highlights rows $1 \dots k$ simultaneously and displays a footer summary `"Undo k operations"`. Clicking reverts all $k$ states atomically.
8. **Redo Split-Button:**
   - Main button redoes the latest undone action (`Ctrl+Y`).
   - Right dropdown arrow shows up to **50 stored redo operations** with identical range-selection behavior.

*--- Toolbar Splitter (`|`) ---*

### 4.3 Group 3: Local Simulation Controls
9. **Play (`>`):** Starts or resumes local asynchronous simulation.
10. **Pause (`||`):** Freezes all simulation thread timers and preserves current pin/register states for inspection.
11. **Halt / Stop (`[]`):** Stops simulation, resets all runtime states/timers/registers to initial values, and hides output pin runtime value containers.
12. **Sim Speed Multiplier Dropdown:**
    - Global clock multiplier affecting all thread intervals (`1s`, `100ms`, etc.) and timer blocks (`TON`, `PulseGen`, etc.):
    - Options: `0.01x`, `0.1x`, `0.25x`, `0.5x`, `1x (Real-time)`, `2x`, `5x`, `10x`, `60x (1m/s)`, `3600x (1h/s)`, `Max (Uncapped)`.

*--- Toolbar Splitter (`|`) ---*

### 4.4 Group 4: Block & Execution Sorting
13. **Sort Blocks Graphically:**
    - Computes the directed acyclic graph (feedback back-edges ignored via Tarjan/DFS cycle detection) and assigns each block to a horizontal rank/column.
    - Positions blocks cleanly onto grid-aligned columns from left to right with uniform horizontal spacing (`6 * G`) and vertical spacing (`3 * G`), then re-routes connected wires.
14. **Sort Block Execution Order (Split-Button):**
    - Clicking main button assigns `Order` values in increments of **10** (`10, 20, 30, 40...`) partitioned per `ThreadId`.
    - Dropdown arrow allows choosing the sort algorithm:
      - `Left-to-Right (X then Y Coordinate)` — sorts blocks strictly by grid $X$ ascending, then grid $Y$ ascending.
      - `Dataflow (Topological then X, Y)` — sorts blocks so signal producers always execute before signal consumers within the same scan cycle, using grid $(X, Y)$ as the tie-breaker.

*--- Toolbar Splitter (`|`) ---*

### 4.5 Group 5: Hardware PLC Operations
15. **Compile Program:** Runs the FBD-to-Bytecode compiler and opens the `Compilation Results` modal window.
16. **Upload:** Opens the `Communication` modal window to transmit compiled bytecode to the STM32F407 PLC.
17. **Watch:** Opens the `Communication` modal window; upon successful handshake, transitions the editor into live **Watch Mode** (displays live output pin values from the physical PLC; any structural/property edit immediately terminates Watch Mode and returns to edit mode).
18. **Unit / PLC Config:** Opens the `Unit / PLC Configuration` modal window.

*--- Toolbar Splitter (`|`) ---*

### 4.6 Group 6: Default Thread Selector
19. **`Thread:` Dropdown:**
    - Sets the default `ThreadId` assigned to newly placed blocks on the canvas.
    - Fixed RTOS Thread Definitions on the STM32F407:
      - `1 (1000ms - Slow)`
      - `2 (100ms - Normal)`
      - `3 (20ms - Fast)`
      - `4 (5ms - High-Speed Guaranteed)`
      - `5 (When Idle - Best Effort / Unpromised)`

---

## 5. Page List & Macro System

### 5.1 Horizontal Page Tab Bar
- Positioned immediately below the Editor Quick Toolbar.
- **Top-Level Pages:** Executed autonomously by the PLC runtime (e.g., `Page 1`, `PID Loop`, `Modbus IO`). Rendered in standard plain font.
- **Macro Pages:** User-defined reusable composite function blocks.
  - Distinguished by a leading asterisk `*` in their name (e.g., `*MotorStarter`, `*Debounce4x`) and rendered in *italic* font in the tab bar.
  - Automatically registered under the `Macro` category in the left `Library` tree (flat list, no deeper hierarchy, displaying the macro name without the leading `*` or with it consistently).
- **Tab Interactions:**
  - **Double-Click Tab:** Inline rename (or opens rename prompt). Prefixing a name with `*` converts it to a Macro; removing `*` converts it to a Top-Level Page (if not instantiated elsewhere).
  - **Right-Click Tab Context Menu:** `New Page`, `New Macro`, `Rename`, `Duplicate`, `Move Left`, `Move Right`, `Delete`.
  - **`[+]` Button at End of Tab Bar:** Quick-create new page/macro.

### 5.2 Macro Pin Definition & Nesting
- Inside a Macro page (`*MacroName`), the user places special I/O boundary blocks from `Library -> Macro`:
  - `MacroIn`: Has 0 left pins and 1 right output pin. Configurable properties: `PinName` (max 3 chars displayed on parent block, full name in tooltip) and `DataType` (`BOOL`, `INT`, `LONG`, `FLOAT`).
  - `MacroOut`: Has 1 left input pin and 0 right pins. Configurable properties: `PinName` and `DataType`.
- Alternatively, multiple inputs/outputs can be added/reordered in the Macro's properties table.
- **Instantiation:** Dragging a Macro from `Library -> Macro` onto any page creates a block whose header shows the Macro type on line 1 and instance name (`MacroName1`) on line 2, with left/right pins matching its `MacroIn` and `MacroOut` definitions.
- **Nesting & Compiler Expansion:** Macros can be placed inside other Macros (circular dependencies are blocked by the compiler). During compilation, macros are flattened/inlined into expanded modules (as shown in the reference log: `"Total 5 modules, 5 expanded modules"`).
- **Live Inspection:** Double-clicking a Macro block instance during Simulation or Watch mode opens that specific instance's internal view showing live pin values for that instance.

---

## 6. Block View Canvas, Grid System, & Block Geometry

### 6.1 Grid & Coordinate Math
- **Strict Integer Grid System:** Every block position $(X_g, Y_g)$, block dimension $(W_g, H_g)$, pin position, and wire vertex is stored in integer grid units—never floating-point pixel coordinates.
- **Base Grid Unit ($G$):** At `100%` zoom, $1\text{ grid unit } G = 10\text{ px}$.
- **Grid Rendering:**
  - Canvas background: Solid White (`#FFFFFF`).
  - At every integer grid intersection $(x \cdot G, y \cdot G)$, a `1x1` pixel dot is drawn in light grey (`#CCCCCC`).
- **Zoom & Pan:**
  - **Zoom Shortcut:** `Ctrl/Cmd + Mouse Wheel` (zooms anchored at cursor grid coordinates).
  - **Discrete Zoom Levels:** `50%` ($G=5\text{px}$), `70%` ($G=7\text{px}$), `80%` ($G=8\text{px}$), `100%` ($G=10\text{px}$), `120%` ($G=12\text{px}$), `150%` ($G=15\text{px}$), `200%` ($G=20\text{px}$), `300%` ($G=30\text{px}$). Using exact integer pixel multiples per grid unit guarantees zero sub-pixel blurring and 100% crisp 1px borders at every zoom level.
  - **Pan:** Hold Middle Mouse Button (`MMB`) and drag, or use the horizontal/vertical scrollbars.

### 6.2 Exact Block Dimensions (in Grid Units $G$)
To ensure block edges and pins align with grid dots on all four sides:
- **Header Height:** $2G$ (`20px` at 100% zoom).
  - Line 1 (Top centered): Block Type (e.g., `And`, `8x DI`, `Segment`).
  - Line 2 (Bottom centered): Block Instance Name (e.g., `And1`, `8xDI1`, `Segment 1`).
- **Pin Dimensions:**
  - **Pin Height:** $1G$ (`10px` at 100% zoom).
  - **Pin Container Width:** $2G$ (`20px` at 100% zoom)—accommodates up to 3 characters (`En`, `X1`, `DI0`, `OUT`) in crisp `8pt`/`9pt` sans-serif/monospace font.
  - **Pin Connection Anchor:** Located on the outer vertical edge of the pin at integer grid Y-coordinates (either on the grid line or half-grid consistently so straight horizontal wires between aligned pins have zero bends).Specifically, if the block top is at grid $Y_b$, header bottom is at $Y_b + 2G$, and pin $i$ ($i = 0, 1, \dots$) spans vertically from $Y_b + (2 + i)G$ to $Y_b + (3 + i)G$. Wires attach at the bottom or top grid line of the pin row $(Y_b + (2 + i)G)$ or center consistently so horizontal wires align with grid dots.
- **Block Body Width:**
  - Left Pin Container: $2G$ (`20px`).
  - Central Courtyard: $3G$ (`30px`) for standard logic/math blocks; wider integer multiples of $G$ (e.g., $5G$ or $6G$) for `Segment` and `Value` blocks.
  - Right Pin Container: $2G$ (`20px`).
  - Total Standard Block Width: $2G + 3G + 2G = 7G$ (`70px` at 100% zoom).
- **Block Total Height:**
  - Let $N_{\text{pins}} = \max(N_{\text{in}}, N_{\text{out}}, 2)$.
  - Total Height $H = (2 + N_{\text{pins}}) \cdot G$.
  - Both left and right pin containers stretch vertically from the bottom of the header ($Y_b + 2G$) all the way to the bottom of the block ($Y_b + H$), even when a container has fewer pins than the opposite side.

### 6.3 Block Outline & Visual Style (Zero Double-Borders)
- **Colors (from Reference Screenshots):**
  - Default Header Background (`clDefault`): Teal `#008080` with black or white crisp text (`#000000` / `#FFFFFF` matching screenshot contrast).
  - Block Courtyard & Empty Pin Container Background: Classic Win95/Odigo Grey `#C0C0C0`.
  - Outline Color: Solid Black `#000000` (`1px` stroke).
  - Selected Block Highlight: `1px` dotted/dashed marquee or inverted selection border (`#0055E5`).
- **Single-Pixel Shared Borders:**
  - Outer block boundary: `1px` black rectangle `(x, y, W, H)`.
  - Header bottom divider: single `1px` horizontal black line at `y + 2G` from `x` to `x + W`.
  - Left pin container inner divider: single `1px` vertical black line at `x + 2G` from `y + 2G` to `y + H`.
  - Right pin container inner divider: single `1px` vertical black line at `x + W - 2G` from `y + 2G` to `y + H`.
  - Individual pins inside the pin container do **not** have full 4-sided boxes that double up; they fill their `(2G x 1G)` slot inside the container with their pastel data-type color and a `1px` separator line where needed.
- **Courtyard Metadata Overlay (`T:` and `O:`):**
  - Inside the central grey courtyard at the bottom, two compact lines of text are rendered:
    - `T: {ThreadId}` (e.g., `T: 2`)
    - `O: {Order}` (e.g., `O: 20`)
- **Hover Tooltips:**
  - Hovering over a block displays its `Description` property (if non-empty).
  - Hovering over any pin displays its full unfiltered name and data type (e.g., `SlaveAddress (INT)` when the 3-char pin label shows `Slv`).

### 6.4 Drag-and-Drop Block Placement
- Only leaf nodes (lowest hierarchy entries) in the `Library` tree are draggable.
- Dragging a leaf node uses a standard file-like drag cursor without rendering a ghost block preview during the drag.
- Releasing LMB over the FBD canvas instantiates the block snapped to the nearest integer grid coordinate $(X_g, Y_g)$, assigns it the currently selected toolbar `ThreadId`, assigns `Order = maxOrderInThread + 10`, and records an Undo entry.

---

## 7. Pins, `En` Pin Semantics, Data Types, & Constants

### 7.1 Universal Enable (`En`) Input Pin
- Every executable block features an `En` (`BOOL`) input pin as its first (top-left) input pin (or configurable in properties, defaulting to `(=1)` when unconnected).
- **Execution Gating:**
  - For standard combinational/math/logic blocks: if `En == 0`, the block is skipped during the scan cycle and its outputs retain their previous values.
  - For edge-triggered memory/communication blocks (`WriteReg`, `ReadReg`, `ModbusWrite`, `RESTReq`): a rising edge (`0 -> 1`) or high level on `En` triggers the read/write operation.

### 7.2 Data Types & Color Coding
Only 4 primitive data types exist in Maraflow:

| Data Type | Bit Width & C Type on STM32 | Wire & Active Color | Pastel Pin Background Color | Default Constant |
| :--- | :--- | :--- | :--- | :--- |
| `BOOL` | 1-bit (`uint8_t` 0/1) | Red `#D92626` (Wire width **4x** when `1`, **1x** when `0`) | Pastel Red `#FADBD8` | `(=0)` (`(=1)` for `En`) |
| `INT` | 32-bit signed (`int32_t`) | Black `#1A1A1A` | Light Grey/White `#EAECEE` | `(=0)` |
| `LONG` | 64-bit signed (`int64_t` / `long long`) | Purple `#7D3C98` | Pastel Purple `#EBDEF0` | `(=0)` |
| `FLOAT` | 32-bit IEEE 754 (`float`) | Green `#1E8449` | Pastel Green `#D4EFDF` | `(=0)` |

### 7.3 Automatic Type Conversion Rules & Restrictions
Connecting an output pin of type $T_{\text{src}}$ to an input pin of type $T_{\text{dst}}$ ($T_{\text{src}} \neq T_{\text{dst}}$) is allowed and inserts an implicit VM cast instruction:

| Source $\rightarrow$ Target | `BOOL` | `INT` (`int32_t`) | `LONG` (`int64_t`) | `FLOAT` (`float`) |
| :--- | :--- | :--- | :--- | :--- |
| **`BOOL`** | Identity | `false -> 0`, `true -> 1` | `false -> 0L`, `true -> 1L` | `false -> 0.0f`, `true -> 1.0f` |
| **`INT`** | `0 -> false`, `!= 0 -> true` | Identity | Sign-extend 32-bit to 64-bit | Exact/nearest `float` cast |
| **`LONG`** | `0L -> false`, `!= 0L -> true` | Truncate to lower 32 bits (compiler warning logged) | Identity | Cast to `float` |
| **`FLOAT`** | `0.0f` or `NaN -> false`, else `true` | Truncate toward zero (`(int32_t)val`), saturating on overflow | Truncate toward zero (`(int64_t)val`), saturating on overflow | Identity |

**Safety Restrictions:**
- Connecting an output pin to another output pin, or an input pin to another input pin, is forbidden.
- Connecting more than one wire to a single input pin is forbidden (replacing or rejecting on drop).
- `NaN` or `Inf` produced by `FLOAT` division-by-zero is clamped/guarded in the VM so it never crashes the PLC task.

### 7.4 Unconnected Input Constant Container `(=x)`
- **Visibility:** Shown **only** on unconnected input pins. Immediately hidden as soon as a wire or hidden connection is attached to that input pin.
- **Geometry:** Attached flush to the left edge of the input pin (`x - W_const`, width $\ge 2G$, height $1G$), white background (`#FFFFFF`), `1px` solid black outline (`#000000`), centered text `(=x)`.
- **Editing Workflow:**
  - **Double-clicking the input pin itself** (the `2G x 1G` pin slot inside the block, **not** the `(=x)` box) replaces the `(=x)` label with a focused inline `JTextField`.
  - **Supported Input Formats:**
    - Decimal (`42`, `-15`, `3.14` for `FLOAT`)
    - Hexadecimal (`0x2A`, `0xFF00`) for `INT` / `LONG`
    - Binary (`0b1010`) for `INT` / `LONG` / `BOOL`
  - **Validation Rules:**
    - `BOOL`: Numeric values are clamped to `[0, 1]` (e.g., typing `5` clamps to `1`, `0` stays `0`); non-numeric text shows an error dialog and reverts.
    - `INT` / `LONG` / `FLOAT`: Invalid syntax (e.g., typing `abc`) triggers an error message box and reverts to the previous constant.
  - Pressing `Enter` or clicking outside commits the value (recording an Undo entry); pressing `Escape` cancels.

### 7.5 Live Output Value Container (Simulation & Watch Mode)
- **Visibility:** Hidden during normal editing. Displayed attached flush to the right of every output pin during **Local Simulation** and **PLC Watch Mode**.
- **Style:** White background (`#FFFFFF`), `1px` black border, height $1G$, width dynamically sized in multiples of $G$ to fit the text.
- **Formatting:**
  - `BOOL`: `0` or `1`
  - `INT` / `LONG`: Decimal integer string
  - `FLOAT`: Formatted to at most **2 decimal places** (e.g., `12.34`); hovering over the output value container shows a tooltip with the full single-precision floating-point representation.

---

## 8. Wires, Busses, Routing Algorithm, & Edge Editing

### 8.1 Wire Connection Workflow
1. User hovers cursor over any source pin (no popup clutter) and presses LMB.
2. While holding LMB and dragging toward the target pin, a straight rubber-band preview line is drawn from `point0` (source pin anchor) to `point1` (current cursor position). Repaints during dragging are throttled to `60 FPS` (`~16ms` frame pacing) to guarantee low CPU overhead.
3. Releasing LMB over a valid opposite-direction pin completes the connection and computes the initial route using the **Orthogonal Shortest-Route Algorithm**.

### 8.2 Orthogonal Shortest-Route Algorithm (Collision-Agnostic)
Given Output Pin at $(x_1, y_1)$ (exiting right) and Input Pin at $(x_2, y_2)$ (entering from left):
- **Case 1 ($x_2 > x_1$ and $y_1 == y_2$):**
  - 1 horizontal segment: `[(x1, y1), (x2, y2)]`.
- **Case 2 ($x_2 > x_1$ and $y_1 \neq y_2$):**
  - 3-segment Z-route using midpoint $x_m = \lfloor (x_1 + x_2) / (2G) \rfloor \cdot G$:
  - `[(x1, y1), (xm, y1), (xm, y2), (x2, y2)]`.
- **Case 3 ($x_2 \le x_1$, target is left of or aligned with source):**
  - 5-segment C/S-route stepping out by $1G$ from both pins and routing via midpoint $y_m = \lfloor (y_1 + y_2) / (2G) \rfloor \cdot G$:
  - `[(x1, y1), (x1 + G, y1), (x1 + G, ym), (x2 - G, ym), (x2 - G, y2), (x2, y2)]`.

### 8.3 Split-Color Wire Rendering & Active `BOOL` Highlighting
- Let the orthogonal polyline consist of segments $S_1, S_2, \dots, S_k$ with total Manhattan length $L = \sum_{i=1}^k \|S_i\|$.
- **Same Data Type ($T_{\text{src}} == T_{\text{dst}}$):** Entire polyline length $L$ is drawn in $T_{\text{src}}$'s color.
- **Different Data Types ($T_{\text{src}} \neq T_{\text{dst}}$):**
  - From distance $0$ to $L/2$ along the polyline (starting from the output pin), the wire is drawn in $T_{\text{src}}$'s color.
  - From distance $L/2$ to $L$ (ending at the input pin), the wire is drawn in $T_{\text{dst}}$'s color.
- **Active `BOOL` Width:** During Simulation or Watch Mode, any `BOOL` wire portion carrying `true` (`1`) is rendered at **4x stroke width** (`4px` scaled by zoom); when `false` (`0`), it renders at `1px`.
- **Fan-Out Junction Dots:** When multiple wires branch from the same output pin and diverge at a vertex, a filled circular/square junction dot (`4x4` px) is drawn at the branch point.
- **Simulation / Compilation Independence:** Wire geometry, colors, and busses are strictly visual metadata; the simulation engine and compiler only consume the logical netlist edge `(SourceBlock.OutPin -> TargetBlock.InPin)`.

### 8.4 Blender-Style Edge Manipulation & Wire Context Menu
- **Moving Edges (Segments), Not Vertices:**
  - Hovering over a horizontal wire segment shows a vertical resize cursor (`N_RESIZE_CURSOR`); dragging it moves the entire horizontal segment up/down on the grid, lengthening/shortening its two neighboring vertical segments.
  - Hovering over a vertical wire segment shows a horizontal resize cursor (`E_RESIZE_CURSOR`); dragging it moves the entire vertical segment left/right on the grid, lengthening/shortening its two neighboring horizontal segments.
  - If the user drags the first segment attached directly to a pin vertically (or last segment), an orthogonal stub is automatically inserted so the pin attachment remains strictly horizontal.
- **Right-Click Context Menu on Wire:**
  - `Add Point`: Splits the clicked segment at the cursor's grid position by inserting a zero-length perpendicular bridge segment, dividing the segment into two independent halves that can now be dragged apart like a step/jog.
  - `Rename Net...`: Opens a small input dialog to set a custom net name (default auto-generated name is `{SourceBlock}.{SourcePin}`, e.g., `And1.Y`).
  - `Hide Connection`: Converts the drawn wire into KiCad-style hierarchical pin flags on both pins.
  - `Remove Connection`: Deletes the connection.

### 8.5 Hidden Connections (KiCad Hierarchical Pin Flags)
- When a connection is hidden, no wire polyline is drawn across the canvas.
- Instead, compact pentagon/triangle flag tags (`1px` black outline, pastel data-type fill, containing the net name e.g. `And1.Y` or user-defined net name) are attached directly outside the connected pins:
  - **On the Source Output Pin (Right side of source block):** Flag arrow points **out of** the block (to the right: `[ net_name >`).
  - **On the Target Input Pin (Left side of target block):** Flag arrow points **into** the block (to the right, into the pin: `[ net_name >|`).
- Right-clicking either flag opens the wire context menu (`Show Connection`, `Rename Net...`, `Remove Connection`).

### 8.6 Busses
- **Purpose:** Purely visual bundling of multiple standard signal wires across long canvas spans; zero impact on simulation or compilation.
- **Routing & Automatic Tap Entries:**
  - A Bus is drawn as a thick (`3px` navy blue `#003399`) orthogonal line routed identically to standard wires (supports edge dragging and `Add Point`).
  - When a standard wire is routed onto or associated with a Bus, Maraflow automatically renders a `1G x 1G` **45-degree diagonal bus entry tick** where the individual colored signal wire merges into the Bus near the source block and emerges from the Bus near the destination block.

---

## 9. Complete Block Library Hierarchy & Catalog

The left `Library` panel displays a `JTree` with angled dotted hierarchy lines. Only leaf nodes can be dragged onto the canvas.

```text
Library
├── Bool
│   ├── And
│   ├── Nand
│   ├── Or
│   ├── Nor
│   ├── Xor
│   ├── Xnor
│   ├── Not
│   ├── RS
│   └── SR
├── Math
│   ├── Add
│   ├── Sub
│   ├── Mul
│   ├── Div
│   ├── Mod
│   ├── Abs
│   ├── Neg
│   ├── Min
│   ├── Max
│   ├── Clamp
│   ├── Scale
│   ├── Sqrt
│   ├── Pow
│   ├── Sin
│   ├── Cos
│   └── PID
├── I/O
│   ├── DI (Single Digital Input)
│   ├── DO (Single Digital Output)
│   ├── 8x DI (Octal Digital Input Bank)
│   ├── 8x DO (Octal Digital Output Bank)
│   ├── AI (Analog Input)
│   ├── AO (Analog Output / DAC)
│   ├── PWM (PWM Generator Output)
│   └── Encoder (Hardware Quadrature Counter)
├── Communication
│   ├── Modbus RTU
│   │   ├── MbRtuRead
│   │   └── MbRtuWrite
│   ├── Modbus TCP
│   │   ├── MbTcpRead
│   │   └── MbTcpWrite
│   ├── REST
│   │   ├── RestGet
│   │   └── RestPost
│   ├── DS18B20
│   │   └── DS18B20Read
│   ├── I2C
│   │   ├── I2CRead
│   │   └── I2CWrite
│   └── SPI
│       └── SPITransfer
├── Main
│   ├── Arrays
│   │   ├── ArrayRead
│   │   └── ArrayWrite
│   ├── Comparators
│   │   ├── EQ (==)
│   │   ├── NE (!=)
│   │   ├── GT (>)
│   │   ├── GE (>=)
│   │   ├── LT (<)
│   │   ├── LE (<=)
│   │   ├── InRange
│   │   └── Hysteresis
│   ├── Conversion
│   │   ├── PackBits (8x BOOL -> INT)
│   │   └── UnpackBits (INT -> 8x BOOL)
│   ├── Execution
│   │   ├── ReadReg (Virtual Register Read)
│   │   └── WriteReg (Virtual Register Write)
│   ├── Filters
│   │   ├── MovingAvg
│   │   ├── LowPass
│   │   └── Debounce
│   ├── Switches
│   │   ├── Sel (2-to-1 Binary Switch)
│   │   ├── Mux (N-to-1 Indexed Multiplexer)
│   │   └── Demux (1-to-N Indexed Demultiplexer)
│   └── Timers
│       ├── Timer (TON / TOF / TP)
│       ├── PulseGen (Astable Blinker / Clock)
│       ├── PulseLen (Monostable Pulse Stretcher)
│       ├── SinglePulse (Rising / Falling Edge One-Shot)
│       ├── Counter (CTU / CTD / CTUD)
│       ├── DateTime (Hardware RTC Reader)
│       ├── DecodeDate (Unix Timestamp -> Y/M/D/h/m/s)
│       └── EncodeDate (Y/M/D/h/m/s -> Unix Timestamp)
├── Macro
│   ├── MacroIn (Available when editing a *Macro page)
│   ├── MacroOut (Available when editing a *Macro page)
│   └── [User Created Macros: *MacroName...]
└── Visual
    ├── Segment (7-Segment Display)
    ├── Value (Numeric Live Display Box)
    └── Vars
        ├── BoolVar
        ├── IntVar
        ├── LongVar
        └── FloatVar
```

### 9.1 Detailed Block Specifications

#### A. `Bool` Category
- **`And`, `Nand`, `Or`, `Nor`, `Xor`, `Xnor`:**
  - Inputs: `En` (`BOOL`, default `1`), `X1..Xn` (`BOOL`, configurable input count $n \in [2, 16]$ in Module Properties, default $n=2$).
  - Outputs: `Y` (`BOOL`).
- **`Not`:**
  - Inputs: `En` (`BOOL`), `X` (`BOOL`).
  - Outputs: `Y` (`BOOL`, `!X`).
- **`RS` (Reset-Dominant Flip-Flop) & `SR` (Set-Dominant Flip-Flop):**
  - Inputs: `En` (`BOOL`), `S` (`BOOL`), `R` (`BOOL`).
  - Outputs: `Q` (`BOOL`), `nQ` (`BOOL`).

#### B. `Math` Category
All math blocks support configurable primary computation data type (`INT`, `LONG`, or `FLOAT`) in Module Properties.
- **`Add`, `Sub`, `Mul`:** Configurable input count $n \in [2, 8]$ (`En`, `X1..Xn` $\rightarrow$ `Y`).
- **`Div`, `Mod`:** Inputs: `En`, `A`, `B`. Outputs: `Y`, `Err` (`BOOL`, `1` if `B == 0`).
- **`Abs`, `Neg`, `Sqrt`, `Sin`, `Cos`:** Inputs: `En`, `X` $\rightarrow$ Output: `Y`.
- **`Min`, `Max`:** Configurable input count $n \in [2, 8]$ (`En`, `X1..Xn` $\rightarrow$ `Y`).
- **`Clamp`:** Inputs: `En`, `In`, `Min`, `Max` $\rightarrow$ Output: `Y`.
- **`Scale` (Linear Map):** Inputs: `En`, `In`, `Ilo`, `Ihi`, `Olo`, `Ohi` $\rightarrow$ Output: `Y`.
- **`PID`:** Inputs: `En`, `PV` (`FLOAT`), `SP` (`FLOAT`), `Kp`, `Ki`, `Kd`, `Rst` (`BOOL`) $\rightarrow$ Output: `Out` (`FLOAT`).

#### C. `I/O` Category (Target-Platform Aware)
Note: Per specification, hardware I/O blocks (`DI`, `DO`, `8x DI`, `8x DO`, `AI`, `AO`, `PWM`, `Encoder`) are structured around the project's **Target Platform Profile** (which defines the available physical pins, ADC channels, DACs, and hardware timers). In the initial phase, their UI representations and property selectors are present while hardware-specific bindings remain stubbed until the Target Platform definitions are finalized.
- **`DI`:** Input: `En` (`BOOL`). Output: `Y` (`BOOL`). Property: Hardware DI Pin dropdown.
- **`DO`:** Inputs: `En` (`BOOL`), `X` (`BOOL`). Output: `Y` (`BOOL`). Property: Hardware DO Pin dropdown.
- **`8x DI`:** Input: `En` (`BOOL`, default `1`). Outputs: `DI0..DI7` (`BOOL`). Property: 8 pin-mapping dropdowns.
- **`8x DO`:** Inputs: `En` (`BOOL`), `DO0..DO7` (`BOOL`). Property: 8 pin-mapping dropdowns.
- **`AI`:** Input: `En` (`BOOL`). Output: `Val` (`INT` or `FLOAT`). Property: ADC Channel & scaling mode.
- **`AO`:** Inputs: `En` (`BOOL`), `Val` (`INT` or `FLOAT`). Property: DAC Channel.
- **`PWM`:** Inputs: `En` (`BOOL`), `Frq` (`INT`), `Dty` (`FLOAT` 0..100%). Property: PWM Timer Channel.
- **`Encoder`:** Inputs: `En` (`BOOL`), `Rst` (`BOOL`). Outputs: `Cnt` (`LONG`), `Vel` (`INT`). Property: Hardware Encoder Timer ID.

#### D. `Communication` Category (High-Level Device Transactions)
Master bus parameters (UART baud rate, Ethernet IP/MAC, I2C bus speed, SPI clock) are configured globally in the `Unit / PLC Config`. Communication blocks only specify the remote target and register/payload:
- **`MbRtuRead` / `MbTcpRead`:**
  - Inputs: `En` (`BOOL`, rising-edge or periodic trigger).
  - Outputs: `Val` (configurable data type), `Ok` (`BOOL`), `Err` (`INT`).
  - Block Properties: Slave ID (`1..247`), IP/Port (for TCP), Register Type (`Coil`, `Discrete Input`, `Holding Register`, `Input Register`), Register Address (`0..65535`), Polling Mode (`On En Rising Edge` or `Periodic ms`).
- **`MbRtuWrite` / `MbTcpWrite`:**
  - Inputs: `En` (`BOOL`, rising edge triggers write), `Val` (configurable type).
  - Outputs: `Ok` (`BOOL`), `Err` (`INT`).
  - Block Properties: Slave ID, IP/Port (for TCP), Register Type (`Coil`, `Holding Register`), Register Address.
- **`RestGet` / `RestPost`:**
  - Inputs: `En` (`BOOL`, rising edge triggers request), `Val` (for `RestPost` payload).
  - Outputs: `Out` (`INT`/`FLOAT` extracted from JSON path), `Sts` (`INT` HTTP status code), `Rdy` (`BOOL`).
  - Block Properties: URL Endpoint string, JSON Key/Path string, Timeout (ms).
- **`DS18B20Read`:**
  - Input: `En` (`BOOL`).
  - Outputs: `Tmp` (`FLOAT`, °C), `Ok` (`BOOL`).
  - Block Properties: 1-Wire Bus Pin, Sensor Index or 64-bit ROM Hex Address, Resolution (`9..12` bit).
- **`I2CRead` / `I2CWrite`:**
  - Inputs: `En` (`BOOL`, rising edge), `Val` (for Write).
  - Outputs: `Val` (for Read), `Ok` (`BOOL`).
  - Block Properties: 7-bit I2C Device Address (`0x00..0x7F`), Register Address, Data Format (`8-bit`, `16-bit BE`, `16-bit LE`, `32-bit`).
- **`SPITransfer`:**
  - Inputs: `En` (`BOOL`, rising edge), `Tx` (`INT`).
  - Outputs: `Rx` (`INT`), `Ok` (`BOOL`).
  - Block Properties: CS Pin ID, Transfer Bit Width (`8`, `16`, `32`).

#### E. `Main` Category
- **`Comparators` (`EQ`, `NE`, `GT`, `GE`, `LT`, `LE`):**
  - Inputs: `En` (`BOOL`), `X1`, `X2` (configurable type). Output: `Y` (`BOOL`).
- **`InRange`:** Inputs: `En`, `In`, `Min`, `Max` $\rightarrow$ Output: `Y` (`BOOL`, `Min <= In <= Max`).
- **`Hysteresis`:** Inputs: `En`, `In`, `Lo`, `Hi` $\rightarrow$ Output: `Y` (`BOOL`).
- **`Execution -> WriteReg` (Virtual Register Write):**
  - Inputs: `En` (`BOOL` — **rising edge `0 -> 1` triggers write**), `In` (data type matches selected virtual register).
  - Output: `Out` (echoes register value).
  - Block Properties: Virtual Register Selection / Creation (`RegName`, Data Type, Retentive in EEPROM `[ true / false ]`, Initial Value).
- **`Execution -> ReadReg` (Virtual Register Read):**
  - Input: `En` (`BOOL` — **rising edge `0 -> 1` or continuous `1` configurable, defaults to rising edge per spec**).
  - Output: `Out` (value latched from the selected virtual register).
  - Block Properties: Virtual Register Selection (`RegName`).
- **`Switches -> Mux`:**
  - Inputs: `En` (`BOOL`), `Adr` (`INT` — selects input index `0..N-1`), `X0..X{N-1}` (configurable input count $N \in [2, 16]$ and data type in Module Properties).
  - Output: `Y` (outputs `X[clamp(Adr, 0, N-1)]`).
- **`Switches -> Demux`:**
  - Inputs: `En` (`BOOL`), `Adr` (`INT`), `In`.
  - Outputs: `Y0..Y{N-1}` (configurable output count $N \in [2, 16]$).
- **`Timers -> Timer`:**
  - Inputs: `En` (`BOOL`), `In` (`BOOL`), `PT` (`INT`, preset time in ms).
  - Outputs: `Q` (`BOOL`), `ET` (`INT`, elapsed time in ms).
  - Block Properties: Mode (`TON` On-Delay, `TOF` Off-Delay, `TP` Pulse).
- **`Timers -> PulseGen`:**
  - Inputs: `En` (`BOOL`), `THi` (`INT`, ms), `TLo` (`INT`, ms). Output: `Q` (`BOOL`).
- **`Timers -> PulseLen`:**
  - Inputs: `En` (`BOOL`), `In` (`BOOL`), `Len` (`INT`, ms). Output: `Q` (`BOOL`).
- **`Timers -> SinglePulse`:**
  - Inputs: `En` (`BOOL`), `In` (`BOOL`). Output: `Q` (`BOOL`, high for exactly 1 scan cycle of the block's thread on Rising/Falling/Both edge).
- **`Timers -> DateTime`, `DecodeDate`, `EncodeDate`:**
  - Reads/converts Unix epoch seconds (`LONG`) to/from `Yr`, `Mon`, `Day`, `Hr`, `Min`, `Sec`, `DoW` (`INT`).

#### F. `Visual` & `Visual -> Vars` Category
- **`Segment` (7-Segment Display):**
  - Inputs: `A`, `B`, `C`, `D`, `E`, `F`, `G`, `DP` (`BOOL`).
  - Outputs: None.
  - Courtyard Visual: Renders an interactive 7-segment + decimal point LED digit inside the block body (`36x54` px white inset panel at 100% zoom; active segments illuminate in dark charcoal/red `#333333` or configurable LED color, inactive segments show light outline).
- **`Value` (Live Readout Box):**
  - Inputs: `In` (`ANY` — adapts to connected data type or configured type).
  - Outputs: None.
  - Courtyard Visual: White inset box inside the block body showing the formatted live numerical value during Simulation and Watch Mode.
- **`Vars -> BoolVar`, `IntVar`, `LongVar`, `FloatVar`:**
  - **Pins:** **0 inputs**, **1 output** (`Y` of respective data type `BOOL`, `INT`, `LONG`, or `FLOAT`).
  - **Purpose:** Compiled into the PLC's runtime parameter/RAM table and exposed in the IDE so the engineer can inject/modify values live during both **Local Simulation** and **PLC Watch Mode** (without exiting Watch Mode!).
  - **Block Properties (`InputType`):**
    - `Switch (Toggle A / B)`: Configured with two preset values `ValA` and `ValB` (e.g., `0` and `1` for `BoolVar`, or `0` and `100` for `IntVar`).
    - `Slider (Min .. Max)`: Configured with `Min`, `Max`, and `Step` (e.g., `0` to `100`).
    - `Direct Input`: Numeric keypad/textbox popup.
  - **Double-Click Interaction:**
    - In **Edit Mode**: Double-clicking opens `Module Properties` as normal (or `Ctrl + Double-Click` / right-click `Properties`).
    - In **Simulation or Watch Mode** (or when double-clicking the value area of the `xVar` block):
      - If `Switch`: Immediately toggles the output between `ValA` and `ValB` (and transmits the parameter write packet if in Watch Mode).
      - If `Slider`: Opens a compact floating slider popover anchored to the block (`[Min <---[===]---> Max]`). Moving/closing the slider commits the value to the simulation engine or physical PLC.

---

## 10. Modal Windows Specification

### 10.1 `Module Properties` Modal (Double-Clicking Any Block)
- **Window Title:** `Module properties`
- **Layout:** Horizontal split (left: Standard Properties Table; right: Block-Specific Configuration Panel) when block-specific settings exist, or single table when none exist, with bottom action bar.
- **Left Panel — Standard Properties Table (`JTable`, 2 columns: `Property` | `Value`):**
  1. `Name`: Editable `String` (must be unique on page, alphanumeric + underscore, max 16 chars).
  2. `ThreadId`: Dropdown selector displaying `{id} ({execution_period})`:
     - `1 (1s)`
     - `2 (100ms)`
     - `3 (20ms)`
     - `4 (5ms)`
     - `5 (When Idle)`
  3. `Order`: Non-negative integer input (`>= 0`, e.g., `20`).
  4. `Color`: Dropdown of predefined header colors:
     - `clDefault` (`#008080` Teal)
     - `clNavy` (`#003366`)
     - `clOlive` (`#556B2F`)
     - `clMaroon` (`#800000`)
     - `clPurple` (`#4B0082`)
     - `clSlate` (`#4A5568`)
     - `clGold` (`#B7791F`)
  5. `Description`: Editable `String` (shown in tooltip when hovering over the block on canvas).
- **Right Panel — Block-Specific Settings:**
  - Dynamically populated based on block class:
    - Multi-input blocks (`And`, `Or`, `Add`, `Mux`): Spinner for `Input Count (2..16)`.
    - Polymorphic blocks (`Add`, `EQ`, `Mux`): Dropdown for `Data Type (BOOL, INT, LONG, FLOAT)`.
    - `I/O` blocks (`8x DI`, `DI`, `DO`): Hardware pin selection dropdowns (`PA0`, `PA1`, ..., `DI_CH0..7`).
    - `xVar` blocks: `Input Mode (Switch / Slider)`, `Min / ValA`, `Max / ValB`, `Default Value`.
    - `Communication` blocks: Slave ID, Register Address, Function Code, etc.
- **Bottom Button Bar:**
  - `[ {Green Checkmark Vector Icon} OK ]`
  - `[ {Red Cross Vector Icon} Cancel ]`

### 10.2 `Compilation Results` Modal
- **Window Title:** `Compilation Results`
- **Top Section — Resource Usage Progress Bars (`x / max_allowed`):**
  - Each metric renders as: `[Right-Aligned Label:] [Win7 Green Progress Bar] [x/max_allowed]`:
    1. `RAM Usage (Bytes):` `[======          ] 128/32768`
    2. `Flash / Program (Bytes):` `[===             ] 480/65536`
    3. `EEPROM Usage (Bytes):` `[=               ] 64/65536`
    4. `Thread 1 (1s) Est. Time:` `[=               ] 45/1000000 us`
    5. `Thread 2 (100ms) Est. Time:` `[==              ] 120/100000 us`
    6. `Thread 3 (20ms) Est. Time:` `[===             ] 85/20000 us`
    7. `Thread 4 (5ms) Est. Time:` `[====            ] 310/5000 us`
    8. `Total Est. CPU Usage:` `[==              ] 8/100 %`
- **Middle-Right Section — RAM Breakdown Pie Chart:**
  - Custom `Graphics2D` pie chart breaking down allocated RAM by data type:
    - `BOOL` (Red `#D92626`)
    - `INT` (Charcoal `#333333`)
    - `LONG` (Purple `#7D3C98`)
    - `FLOAT` (Green `#1E8449`)
    - `VM Overhead / Buffers` (Teal `#008080`)
  - Includes a compact legend showing byte count and percentage per type.
- **Lower Section — `Log:` Read-Only Monospace Text Area:**
  - Displays step-by-step compilation info, type-cast notices, macro expansion counts, and any errors.
- **Bottom Button Bar:**
  - `[ {Icon} Close ]`
  - `[ {Icon} Close and Upload ]`
  - `[ {Icon} Close, Upload, Watch ]`

### 10.3 `Communication` Modal
- **Window Title:** `Communication Settings`
- **Upload / Connection Method Selector (Tabbed or Dropdown):**
  1. **USB (Virtual COM / CDC):**
     - `Serial Port`: Dropdown of detected system ports (`/dev/ttyACM0`, `/dev/ttyUSB0`, `COM3`, etc.) + `[Refresh]` button.
     - `Baud Rate`: Dropdown (`9600`, `19200`, `38400`, `57600`, `115200` [Default], `230400`, `460800`, `921600`).
     - `PLC Address`: Integer (`1..254`, default `1`).
     - `Password`: Masked password field.
  2. **RS485 (Half-Duplex Serial):**
     - `Serial Port`, `Baud Rate` (default `115200`), `Data Bits` (`8`), `Parity` (`None`, `Even`, `Odd`), `Stop Bits` (`1`, `2`), `Station Address` (`1..247`), `Response Timeout (ms)`, `Password`.
  3. **Ethernet (TCP/IP):**
     - `IP Address`: IPv4 input (e.g., `192.168.1.50`).
     - `TCP Port`: Integer (default `5020`).
     - `Unit ID`: Integer (default `1`).
     - `Password`: Masked password field.
- **Bottom Controls:**
  - Connection status indicator + Transfer Progress Bar.
  - `[ {Checkmark Icon} Connect / Upload ]` and `[ {Cross Icon} Cancel ]`.

### 10.4 `Unit / PLC Config` Modal (Initial Stub)
- **Window Title:** `PLC / Unit Configuration`
- Displays:
  - `Target Platform Model`: Dropdown (`STM32F407-Maraflow-Std`, `STM32F407-Maraflow-IO64`, `STM32F407-Maraflow-Mini`).
  - Read-only summary of Fixed RTOS Threads (`Thread 1: 1000ms`, `Thread 2: 100ms`, `Thread 3: 20ms`, `Thread 4: 5ms`, `Thread 5: Idle`).
  - Placeholder tabs for `Onboard I/O`, `RS485 / Modbus Master Config`, `Ethernet Config`, `I2C / SPI Bus Config`.
  - `[ {Checkmark Icon} OK ]` and `[ {Cross Icon} Cancel ]`.

---

## 11. Simulation Engine, STM32F407 Bytecode VM, & Watch Protocol

### 11.1 Asynchronous Multi-Threaded Simulation Engine
- Uses Java 27 Virtual Threads / `ScheduledExecutorService` to run 5 independent execution loops corresponding to Threads `1..5`:
  - `Thread 1`: Base period $1000\text{ ms} / M_{\text{sim}}$
  - `Thread 2`: Base period $100\text{ ms} / M_{\text{sim}}$
  - `Thread 3`: Base period $20\text{ ms} / M_{\text{sim}}$
  - `Thread 4`: Base period $5\text{ ms} / M_{\text{sim}}$
  - `Thread 5 (Idle)`: Runs continuously in the background with a cooperative yield (`1ms` virtual tick scaled by $M_{\text{sim}}$).
- **Execution Semantics Within a Thread:**
  - All blocks assigned to `ThreadId` $k$ are sorted in ascending order of their `Order` property (`O:`).
  - For each block:
    1. Read input values from connected source output slots (applying any type conversion) or from compile-time constants `(=x)`.
    2. Check `En` pin: if `false` (`0`), skip evaluation (or clear edge detector state).
    3. Execute block logic and write results to the block's output slots.
  - **$z^{-1}$ Feedback Loops:** Because blocks execute sequentially by `Order`, any wire routed from a higher-`Order` block back to a lower-`Order` block naturally reads the value computed during the previous scan cycle ($Q^{-1}$ / $z^{-1}$ delay).
- **Editing Terminates Simulation:** Any structural or property edit on the canvas (moving/deleting/adding a block, changing a wire, editing a constant) immediately stops the simulation (`Halt/Stop`), except interacting with `Visual Button` widgets or `xVar` blocks.

### 11.2 STM32F407 Bytecode VM Specification
- **Binary Image Header (32 Bytes):**
  - Magic (`0x4D 0x46 0x4C 0x57` = `"MFLW"`), Format Version (`uint16`), Target Model ID (`uint16`), CRC32 (`uint32`), RAM Table Sizes (`numBool`, `numInt`, `numLong`, `numFloat`), EEPROM Parameter Count (`uint16`), Thread Entry Offsets (`uint32[5]`).
- **Instruction Set Architecture (Register/Slot-Indexed VM):**
  - Every block output pin and constant is assigned a flat 16-bit slot index in its respective type table (`BOOL_RAM[]`, `INT_RAM[]`, `LONG_RAM[]`, `FLOAT_RAM[]`).
  - Instructions are compact 32-bit or 64-bit aligned words: `[Opcode: 8b] [Flags/Count: 8b] [DstSlot: 16b] [SrcSlot1: 16b] [SrcSlot2: 16b ...]`.
  - Opcodes include:
    - Type casts: `CAST_B2I`, `CAST_I2B`, `CAST_I2L`, `CAST_L2I`, `CAST_I2F`, `CAST_F2I`, etc.
    - Logic: `OP_AND`, `OP_OR`, `OP_XOR`, `OP_NOT`, `OP_RS`, `OP_SR`.
    - Math: `OP_ADD_I/L/F`, `OP_SUB_I/L/F`, `OP_MUL_I/L/F`, `OP_DIV_I/L/F`, `OP_MOD`, `OP_PID`.
    - Comparators & Switches: `OP_CMP_EQ/NE/GT/GE/LT/LE`, `OP_MUX`, `OP_DEMUX`.
    - Timers & Registers: `OP_TIMER_TON/TOF/TP`, `OP_PULSE_GEN`, `OP_REG_READ`, `OP_REG_WRITE`.
    - I/O & Comm: `OP_IO_DI`, `OP_IO_DO`, `OP_MB_READ`, `OP_MB_WRITE`, `OP_DS18B20`, `OP_I2C`, `OP_SPI`.
- **Cycle / Execution Time Estimator:**
  - Each opcode has a calibrated microsecond cost weight on STM32F407 @ 168 MHz (e.g., `OP_AND = 0.08 us`, `OP_DIV_F = 0.25 us`, `OP_PID = 1.8 us`, `OP_MB_READ = 2.5 us` async queue).
  - Total estimated thread execution time $T_{\text{est}}(k) = \sum_{b \in \text{Thread } k} \text{Cost}(b)$, and total CPU usage is:
    $$\text{CPU\%} = \sum_{k=1}^{4} \frac{T_{\text{est}}(k)}{\text{Period}(k)} \times 100\%$$

### 11.3 Smart Bandwidth-Aware Watch Polling Protocol (`115200` Baud)
At `115200` 8N1, theoretical raw throughput is `11,520 bytes/sec` (`~11.5 bytes/ms`). To monitor all 5 threads smoothly without saturating the UART/RS485 bus:
1. **Viewport & Active Page Subscription:** The IDE sends a `WATCH_SUBSCRIBE` frame listing only the slot IDs visible on the currently open page (plus any channels pinned in `Scope` / `Watch Table`).
2. **Tiered Thread Synchronization:**
   - **Thread 1 (`1000ms`):** Polled exactly once per second (`1 Hz`) synchronized to its scan completion flag.
   - **Thread 2 (`100ms`):** Polled every cycle (`10 Hz`).
   - **Thread 3 (`20ms`):** Sub-sampled every 5th cycle (`100ms` / `10 Hz`).
   - **Thread 4 (`5ms`):** Sub-sampled every 20th cycle (`100ms` / `10 Hz`) for canvas labels, with optional burst ring-buffer capture for the `Scope` window.
   - **Thread 5 (`Idle`):** Polled at a fixed low-priority rate (`250ms` / `4 Hz`).
3. **Delta Compression:** The PLC only transmits `(SlotID, NewValue)` pairs that changed since the last ACKed poll frame, keeping typical frame sizes under `64 bytes` (`< 6ms` wire time at `115200` baud).

---

## 12. `.mfl` Project File Schema & Package Structure

### 12.1 `.mfl` File Format
Saved as pretty-printed, deterministic UTF-8 JSON (with `.mfl` extension) so engineers can version-control projects cleanly in Git and inspect diffs:
```json
{
  "formatVersion": "1.0",
  "projectName": "test",
  "targetPlatform": "STM32F407-Maraflow-Std",
  "defaultThreadId": 2,
  "virtualRegisters": [
    { "name": "TankLevel", "dataType": "FLOAT", "retentive": true, "initialValue": "0.0" }
  ],
  "pages": [
    {
      "name": "Page 1",
      "isMacro": false,
      "blocks": [
        {
          "id": "b1",
          "type": "Bool.And",
          "name": "And1",
          "gridX": 30,
          "gridY": 19,
          "threadId": 2,
          "order": 30,
          "color": "clDefault",
          "description": "",
          "properties": { "inputCount": 2 },
          "constants": { "En": "1", "X1": "0", "X2": "0" }
        }
      ],
      "wires": [
        {
          "id": "w1",
          "netName": "8xDI1.DI0",
          "sourceBlock": "b0",
          "sourcePin": "DI0",
          "targetBlock": "b1",
          "targetPin": "X1",
          "hidden": false,
          "busId": null,
          "points": [[25, 21], [28, 21], [28, 21], [30, 21]]
        }
      ],
      "graphics": []
    }
  ]
}
```

### 12.2 Java Package Architecture (`cz.maramat.maraflow`)
- `cz.maramat.maraflow.Main` — Bootstrapper, FlatLaf Win95/7 theme setup, native keybindings.
- `cz.maramat.maraflow.model` — `Project`, `Page`, `BlockInstance`, `Pin`, `DataType`, `Wire`, `Bus`, `GraphicShape`, `VirtualRegister`, `TargetProfile`.
- `cz.maramat.maraflow.library` — `BlockDefinition`, `BlockCatalog` (all categories: `Bool`, `Math`, `IO`, `Communication`, `Main`, `Macro`, `Visual`, `Vars`).
- `cz.maramat.maraflow.ui` — `MainWindow`, `DockingWorkspace`, `EditorToolbar`, `PageTabBar`, `LibraryTreePanel`, `PlcLogPanel`, `ScopePanel`.
- `cz.maramat.maraflow.ui.canvas` — `FbdCanvas`, `GridRenderer`, `BlockRenderer`, `WireRouter`, `EdgeDragController`, `FlyoutToolButton`.
- `cz.maramat.maraflow.ui.dialogs` — `ModulePropertiesDialog`, `CompilationResultsDialog`, `CommunicationDialog`, `PlcConfigDialog`, `ImportPageDialog`.
- `cz.maramat.maraflow.ui.icons` — Vector `Graphics2D` icon implementations (`ToolIcons`, `CheckmarkIcon`, `CrossIcon` — zero emojis).
- `cz.maramat.maraflow.undo` — `UndoManager50`, `UndoAction` snapshots.
- `cz.maramat.maraflow.sim` — `SimulationEngine`, `ThreadRunner`, `BlockEvaluator`.
- `cz.maramat.maraflow.compiler` — `BytecodeCompiler`, `CompilationReport`, `VmOpcode`.
- `cz.maramat.maraflow.comm` — `PlcConnection`, `WatchPollingManager`, `SerialTransport`, `TcpTransport`.
- `cz.maramat.maraflow.io` — `MflProjectSerializer`, `PdfExporter`.
