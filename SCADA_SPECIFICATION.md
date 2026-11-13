# Maraview SCADA Designer & Operator Runtime — Comprehensive Technical Specification

---

## 1. System Overview & Core Rules

### 1.1 Aesthetic & UX Principles
1. **Utilitarian Win95 / Win7 Crossover UI Chrome:** Crisp `1px` borders, zero border-radius (`arc = 0`), high-density toolbars, classic gradient dock panel headers, and angled dotted tree connectors (`JTree.lineStyle = "Angled"`).
2. **3D Shaded Industrial Process Graphics (WinCC / Symbol Factory Style):**
   - While application chrome (menus, toolbars, docks, tables, dialogs) strictly follows the flat/beveled Win95/Win7 utilitarian style, **process equipment on the SCADA canvas** (pipes, pumps, valves, tanks, motors, conveyors, actuators) renders with **3D metallic/cylindrical shading** (`LinearGradientPaint` and `RadialGradientPaint` steel/iron gradients) bounded by crisp `1px` dark charcoal/black (`#1A1D22`) outlines and specular highlight bands, matching the reference centrifugal pump aesthetic.
3. **Strict Zero-Emoji Policy:** No Unicode emojis anywhere in the source code, comments, logs, or UI. All toolbar, tree, and button icons must be rendered programmatically via custom `javax.swing.Icon` vector painters (`Graphics2D`).
4. **No Hand-Holding:** Built for PLC and SCADA engineers. No onboarding popups, wizards, or hints.

### 1.2 Technology Stack & Architecture
- **Language & Runtime:** Java 21+ (`cz.maramat.scada` or integrated into `cz.maramat`), sharing core infrastructure with Maraflow.
- **UI Framework:** Swing with customized `FlatLightLaf` (`Button.arc = 0`, `Component.arc = 0`, `TextComponent.arc = 0`, `ScrollBar.width = 14`).
- **Rendering Engine:** Custom hardware-accelerated Swing `JComponent` (`ScadaCanvas`) using `Graphics2D` multi-pass procedural vector rendering, `LinearGradientPaint` / `RadialGradientPaint` 3D shaders, and per-symbol `BufferedImage` static-layer caching.
- **File Extensions:**
  - `.msl` — Standalone Maraview SCADA Project File (UTF-8 deterministic JSON).
  - Direct binding support for `.mfl` (Maraflow PLC Project File) to import tags (`VirtualRegister`, `xVar`, `StVariable`, and hardware I/O channels) and run side-by-side with `SimulationEngine` or physical PLC Watch/Modbus connections.

---

## 2. Visual Language: 3D Industrial Shading Palette & Rendering Rules

To achieve the 3D shaded industrial look (reference: 3D grey centrifugal pump with blue segmented impeller) completely in `Graphics2D` without external SVG libraries:

### 2.1 Standard 3D Metallic Gradient Palettes
All equipment casings, flanges, pipes, and vessels use deterministic multi-stop gradients coupled with a `1px` `#14171A` outer contour and a `1px` `#B8C2CC` inner bevel highlight:

| Material / Surface | Gradient Type | Color Stops (`0.0 -> 0.28 -> 0.65 -> 1.0`) | Usage |
| :--- | :--- | :--- | :--- |
| **Cast Iron / Pump Grey** | `Linear` / `Radial` | `#464E59` $\rightarrow$ `#8C97A6` $\rightarrow$ `#636D7A` $\rightarrow$ `#343A42` | Pump volutes, motor housings, valve bodies, gearboxes |
| **Stainless Steel / Vessel** | `Linear` (Horizontal) | `#5A6370` $\rightarrow$ `#BCC6D4` $\rightarrow$ `#7C8796` $\rightarrow$ `#404752` | Vertical/horizontal tanks, silos, hoppers, pipe walls |
| **Flange / Dark Machined Steel** | `Linear` | `#2F353D` $\rightarrow$ `#6E7885` $\rightarrow$ `#474E59` $\rightarrow$ `#22262B` | Pipe flanges, mounting feet, bolts, end-caps |
| **Impeller / Active Rotor Blue** | Alternating Wedges | Blade A: `#3B4CCA` (`#5266E8` highlight), Blade B: `#1B224B` | Pump impellers, fan blades, turbine rotors |
| **Sight-Glass / Cutout Interior** | `Linear` (Inset) | `#181C20` $\rightarrow$ `#2A3038` | Recessed cavity behind rotating blades or tank level cutouts |

### 2.2 Fluid & Process Media Colors
Pipes and tank levels render their interior fill based on the configured `FluidType` (or dynamic state):

| Fluid Type | Base Fill Color | Specular Center Highlight | Flow Dash Color |
| :--- | :--- | :--- | :--- |
| **Empty / Depressurized** | `#4A525D` | `#636D7A` | *(None)* |
| **Water (Cold / Process)** | `#1F618D` | `#5499C7` | `#AED6F1` |
| **Hot Water / Steam** | `#C0392B` | `#E74C3C` | `#F5B7B1` |
| **Oil / Fuel / Hydraulic** | `#B7791F` | `#D69E2E` | `#F6E05E` |
| **Chemical / Acid / Gas** | `#1E8449` | `#2ECC71` | `#ABEBC6` |
| **Slurry / Wastewater** | `#5D4037` | `#8D6E63` | `#BCAAA4` |

### 2.3 Equipment State Overlay Rules
Every active equipment symbol supports four operational visual states driven by bound PLC tags:
1. **`STOPPED (0)`:** Standard 3D cast-iron grey body; rotor/impeller stationary; status LED (if enabled) dark grey `#4A525D`.
2. **`RUNNING (1)`:** Rotor/impeller/belt animates at frame rate proportional to speed tag; optional subtle green status indicator (`#1E8449`) on motor terminal box.
3. **`WARNING / INTERLOCK`:** Amber (`#D68910`) indicator frame or status badge.
4. **`FAULT / ALARM`:** Alternates a `2px` high-visibility red (`#D92626`) outer halo / tinted casing highlight at `2 Hz` (`500ms` period).

### 2.4 Static Layer Caching (`BufferedImage`)
- Complex 3D shaded housings (such as the centrifugal pump volute, flanged elbows, valve bodies, and tank shells) are rendered once per `(width, height, zoom, rotation)` into an offscreen `BufferedImage` cache.
- During the `60 FPS` runtime animation loop, `ScadaCanvas` blits the cached static shell in $O(1)$ time and only executes `Graphics2D` calls for the dynamic interior sub-part (rotating impeller angle, tank liquid clip height, conveyor cleat offset, or pipe flow dash phase).

---

## 3. Canvas Grid, Coordinate System, & Orthogonal Pipe Routing

### 3.1 Strict Integer Grid & Zoom
- Identical to `FbdCanvas`:
  - Base Grid Unit: $G = 10\text{ px}$ at `100%` zoom.
  - Discrete Zoom Levels: `50%` ($G=5$), `70%` ($G=7$), `80%` ($G=8$), `100%` ($G=10$), `120%` ($G=12$), `150%` ($G=15$), `200%` ($G=20$), `300%` ($G=30$).
  - Every equipment symbol's position $(X_g, Y_g)$, size $(W_g, H_g)$, flange port anchor, and pipe vertex is stored in integer grid coordinates.
  - In **Design Mode**, the canvas renders the `#CCCCCC` `1x1` dotted grid over a configurable background (`#FFFFFF` White, `#E2E6EA` Industrial Light Slate, or `#2B3036` Dark Control Room). In **Operator Mode**, grid dots are hidden.

### 3.2 Equipment Flange Ports (`PipePort`)
- Unlike FBD blocks (which only have left inputs and right outputs), SCADA equipment symbols define directional `PipePort` anchors on any of the 4 cardinal sides (`NORTH`, `SOUTH`, `EAST`, `WEST`), snapped to integer grid coordinates:
  - Example (`CentrifugalPump`, `8G x 10G`):
    - `Suction` port at center or side (`WEST` / `AXIAL`).
    - `Discharge` top flanged port at `(X_g + 3, Y_g)` pointing `NORTH` (matching the top discharge flange in the reference image).
- Hovering over a `PipePort` with the Cursor or Pipe Tool highlights a `1G x 1G` flange snap box.

### 3.3 Wire-Style Orthogonal Pipe Routing (`PipeRouter`)
Users route plumbing exactly like wires in `FbdCanvas`—never placing individual elbow pieces by hand:
1. **Drag-to-Connect:** Press LMB on a source `PipePort` (or existing pipe tee point), drag a rubber-band line to a target `PipePort`, and release LMB.
2. **Direction-Aware Orthogonal Shortest-Route Algorithm:**
   - Given Source Port $(x_1, y_1)$ with exit normal $\vec{n}_1 \in \{\text{N, S, E, W}\}$ and Target Port $(x_2, y_2)$ with entry normal $\vec{n}_2$:
   - The router steps out orthogonally by flange clearance $C = 2G$ along $\vec{n}_1$ and $\vec{n}_2$, then connects the two clearance points using a 1-segment straight run, 2-segment L-bend, 3-segment Z-bend (midpoint snapped to $G$), or 5-segment U/S-bend.
3. **Blender-Style Pipe Segment Dragging:**
   - Hovering over any horizontal pipe run shows `N_RESIZE_CURSOR`; dragging moves that horizontal pipe segment up/down on the integer grid while automatically lengthening/shortening adjacent vertical risers.
   - Hovering over any vertical pipe run shows `E_RESIZE_CURSOR`; dragging moves that vertical pipe segment left/right on the grid.
   - Right-click context menu on a pipe:
     - `Add Bend / Step`: Splits the clicked pipe segment so the user can drag half of it into an obstacle-avoiding step around a tank or pump.
     - `Create Tee Branch`: Starts a new pipe branch anchored at the clicked grid point on the existing pipe.
     - `Pipe Properties...`: Configures Pipe Diameter (`Small 1G`, `Medium 1.5G`, `Large 2G`), `FluidType`, and `FlowTag` binding.
     - `Delete Pipe`.

### 3.4 3D Multi-Pass Pipe & Fitting Rendering
For every orthogonal pipe polyline `[(x0, y0), (x1, y1), ..., (xk, yk)]` of diameter $D$:
1. **Pass 1 — 3D Cylindrical Pipe Segments:**
   - Each horizontal segment is filled with a vertical 4-stop `LinearGradientPaint` (`#2B3038 -> #9AA4B2 -> #5A6370 -> #2B3038`) and top/bottom `1px` `#14171A` outlines.
   - Each vertical segment is filled with a horizontal 4-stop `LinearGradientPaint` and left/right `1px` `#14171A` outlines.
2. **Pass 2 — 3D Elbows, Tees, & Flanges:**
   - At every $90^\circ$ vertex, a procedural 3D mitered/rounded elbow collar with two raised flange rings (`width = D + 4px`) is drawn so horizontal and vertical cylindrical gradients transition cleanly without raw overlapping seams.
   - At every port attachment and T-junction, a raised 3D bolted flange collar is rendered.
3. **Pass 3 — Animated Liquid Cutaway / Flow Core:**
   - When `ShowFlowAnimation` is enabled and the pipe's effective flow rate $> 0$, a central cutaway channel (`width = 0.45 * D`) renders the `FluidType` base color plus a moving `BasicStroke` dashed highlight (`dashPhase -= velocity * dt`) marching in the flow direction.

---

## 4. Complete SCADA Symbol & Component Catalog

The left `Symbol Library` dock displays a `JTree` with angled connectors. Only leaf nodes are draggable onto the canvas.

```text
Symbol Library
├── Piping & Valves
│   ├── HandValve (Manual Isolation Valve)
│   ├── SolenoidValve (2-Way / 3-Way On-Off Valve)
│   ├── ControlValve (Proportional Modulating Globe Valve)
│   ├── CheckValve (Non-Return Swing/Ball Check Valve)
│   └── Damper (Louvre / Duct Damper)
├── Pumps, Motors & Fans
│   ├── CentrifugalPump (3D Volute with Visible Rotating Impeller)
│   ├── InlinePump (Booster / Circulation Pump)
│   ├── MeteringPump (Positive Displacement Dosing Pump)
│   ├── ElectricMotor (3D Finned Motor with Cooling Fan / Shaft)
│   └── BlowerFan (Centrifugal / Axial Industrial Fan)
├── Tanks, Vessels & Reactors
│   ├── CylindricalTank (Vertical 3D Dished-Head Tank with Sight Cutout)
│   ├── HorizontalTank (Pressurized Storage Vessel)
│   ├── HopperSilo (Conical Bottom Bulk Silo)
│   ├── OpenBasin (Open Sump / Clarifier Pit)
│   └── TankAgitator (Top-Mounted Motor + Rotating Mixing Shaft/Paddles)
├── Material Handling & Machines
│   ├── ConveyorBelt (Resizable 2-Roller Belt with Moving Cleats/Items)
│   ├── ScrewAuger (Rotating Helical Screw Conveyor in Tube)
│   ├── PneumaticCylinder (3D Double-Acting Cylinder with Extending Rod)
│   ├── HydraulicPress (Vertical Ram Press)
│   └── HeaterCoil (Immersion / Inline Heating Element with Thermal Glow)
├── Sensors & Instrumentation (ISA-5.1 / Inline)
│   ├── PressureSensor (PT - Inline 3D Dial Gauge or Digital Transmitter)
│   ├── TempSensor (TT - Thermowell Probe + Readout / Thermometer Bar)
│   ├── FlowMeter (FT - Inline Electromagnetic/Turbine Flow Meter)
│   ├── LevelSwitch (LS - Float / Point Level Switch Mounted on Tank Wall)
│   └── ProximitySensor (Inductive/Optical Sensor for Conveyors/Cylinders)
└── Operator Controls & Readouts
    ├── PushButton (3D Bezel Momentary / Illuminated Pushbutton)
    ├── SelectorSwitch (2-Pos / 3-Pos Rotary Cam Switch: Off/Hand/Auto)
    ├── EStopMushroom (Red/Yellow Twist-to-Release Emergency Stop)
    ├── PilotLamp (3D Domed LED Indicator Light)
    ├── NumericReadout (Digital Value Box with Engineering Units)
    ├── NumericSetpoint (Operator Input Field / Slider Popover)
    ├── BarGraph (Vertical/Horizontal Analog Fill Bar with Alarm Limits)
    └── TrendChart (Embedded Live Mini-Scope / Strip Chart)
```

---

## 5. Tag Binding & Flow Propagation Model

### 5.1 Tag Data Sources
Every SCADA symbol property can bind to a `ScadaTag`:
1. **Linked Maraflow Project (`.mfl`):**
   - Directly imports all `VirtualRegister` entries, `xVar` blocks (`BoolVar`, `IntVar`, `LongVar`, `FloatVar`), global `StVariable` declarations, and physical `DI`/`DO`/`AI`/`AO` pins from a linked `.mfl` file.
   - An `OperandPickerDialog` lets the engineer search and pick any tag by name or data type.
2. **Direct Communication Binding (Standalone Mode):**
   - Binds directly to a Maraflow VM slot ID or a Modbus RTU / Modbus TCP register (`SlaveID`, `FunctionCode`, `Address`, `DataType`, `Scale/Offset`).
3. **Internal Local SCADA Tags:**
   - Memory tags (`BOOL`, `INT`, `LONG`, `FLOAT`) for testing animations in the Designer before linking a PLC program.

### 5.2 Automatic vs. Explicit Pipe Flow Animation
Engineers should not have to manually bind a tag to every single pipe segment unless desired. Pipe flow state supports two modes:
- **`Auto-Derived from Upstream Equipment` (Default):**
  - If a pipe starts at a `Tank` (with `Level > 0` or infinite source) and passes through an active `CentrifugalPump` (`Running == true`) and open `Valve` (`Open > 0`), the SCADA engine automatically propagates active pressure and flow velocity down the connected pipe graph until a closed valve or destination vessel is reached.
- **`Explicit Tag Binding`:**
  - The pipe's flow velocity and pressurized state are directly driven by a bound `BOOL` or `INT`/`FLOAT` tag.

---

## 6. Main Window Layout, Modes, & Toolbars

### 6.1 Application Title Bar
- `{project_name} ({project_path}) - Maraview V{version} [DESIGN]` or `[OPERATOR]`

### 6.2 Workspace Layout
1. **Designer Mode:**
   - **Top Menu Bar:** `File`, `Edit`, `View`, `Screen`, `Tags`, `Communication`, `Window`, `Help`.
   - **Quick Toolbar:**
     ```
     [Cursor] [Pipe] [Line] [Shape v] [Circle v] [Text] | [Rotate 90] [Flip H] [Flip V] [Align v] | [Undo |v] [Redo |v] | [Sim Preview] [Operator Mode] | [Link .mfl] [Comm Settings]
     ```
   - **Screen Tab Bar:** Horizontal tabs for multiple HMI screens (e.g., `Overview`, `Boiler House`, `Packaging Line`, `Alarms`).
   - **Left Dock:** `Symbol Library` (`JTree`) + `Tag Browser` tab.
   - **Center Dock:** `ScadaCanvas` inside `JScrollPane`.
   - **Bottom Dock (Optional):** `Alarm Banner / Log` and `Tag Watch Table`.
2. **Operator View Mode (`F5` or launched with `--operator project.msl`):**
   - Hides the `Symbol Library` dock, editing toolbar, and canvas grid dots.
   - Locks symbol selection, dragging, and pipe editing.
   - Displays a top or bottom industrial navigation bar with **Screen Switch Buttons**, **Active Alarm Banner** (with `[Acknowledge]` button), **Connection Status LED (`ONLINE / OFFLINE`)**, and an `[Exit Operator Mode]` button (optionally password-protected).
   - Clicking interactive controls (`PushButton`, `SelectorSwitch`, `SolenoidValve` in manual mode, `NumericSetpoint`) immediately writes the new value to the local simulation engine or physical PLC.

---

## 7. Implementation Roadmap (Split into Epochs)

### Epoch 1: Core SCADA Data Model, `.msl` Serializer, & `.mfl` Tag Bridge
**Goal:** Establish the data structures, tag database, file persistence, and live bridge to Maraflow `.mfl` projects and `SimulationEngine`.
1. **Model Classes (`cz.maramat.scada.model`):**
   - `ScadaProject` (`.msl`), `ScadaScreen` (multi-screen tabs, background color, grid size), `ScadaSymbolInstance` (grid $X, Y, W, H$, rotation $0^\circ/90^\circ/180^\circ/270^\circ$, mirror flags, layer Z-index, property map, tag bindings), `ScadaPipe` (source/target port references, orthogonal grid vertex list, diameter, `FluidType`, flow binding), `PipePort` (offset $X_g, Y_g$, cardinal direction $\vec{n}$).
2. **Tag & Binding System (`cz.maramat.scada.tags`):**
   - `ScadaTag` (`name`, `DataType`, `sourceType` `[INTERNAL, MFL_LINK, MODBUS]`, current runtime value, alarm limits).
   - `MflTagImporter`: Parses a linked `.mfl` project file to populate `VirtualRegister`, `xVar`, `StVariable`, and I/O tags automatically.
   - `RuntimeTagProvider`: Interfaces with Maraflow's `SimulationEngine` (in-process shared memory when launched from Maraflow) or `PlcConnection` (serial/TCP Watch & Write frames).
3. **JSON Persistence (`MslProjectSerializer`):**
   - Deterministic pretty-printed UTF-8 JSON load/save for `.msl` files.

### Epoch 2: 3D Metallic Shader Engine & Static Layer Cache
**Goal:** Build the reusable `Graphics2D` 3D industrial rendering toolkit (`ScadaShader`) and `BufferedImage` cache so all equipment shares a cohesive WinCC / Symbol Factory 3D grey aesthetic.
1. **`ScadaShader` Utility Class (`cz.maramat.scada.render.ScadaShader`):**
   - Procedural helpers for:
     - `paintCylinderH` / `paintCylinderV` (4-stop metallic cylindrical gradients with `1px` `#14171A` outline and inner specular highlight).
     - `paintDishedTankHead` (3D shaded elliptical dome caps).
     - `paintFlange` (raised bolted pipe flange collar in any cardinal orientation).
     - `paintBeveledHousing` / `paintVoluteCasing` (radial + linear shaded cast-iron bodies with recessed circular cutouts and drop-shadow inner rings).
     - `paintRotorImpeller` (n-blade alternating `#3B4CCA` / `#1B224B` bevel-shaded impeller wheel with metallic center hub cap).
     - `paintLedIndicator` (3D domed status lamp with radial specular dot).
2. **`SymbolLayerCache`:**
   - Caches static `BackgroundShell` and `ForegroundOverlay` `BufferedImage` instances keyed by `(symbolType, widthPx, heightPx, rotation, flipH, flipV, stateVariant)` so static 3D gradients are never recomputed during 60 FPS animations.

### Epoch 3: Interactive `ScadaCanvas`, Orthogonal `PipeRouter`, & 3D Pipe Rendering
**Goal:** Deliver the Designer canvas with drag-and-drop placement, rotation/flipping, wire-style orthogonal pipe routing, Blender-style pipe segment dragging, and 3D animated pipe rendering.
1. **`ScadaCanvas` (`cz.maramat.scada.ui.ScadaCanvas`):**
   - Integer grid rendering ($G=10\text{px}$), discrete zoom (`50%..300%`), MMB pan, marquee selection, `UndoManager50` integration, `90°` rotation (`R`), horizontal/vertical flip, and Z-order management.
2. **Direction-Aware `PipeRouter` & `PipeEdgeDragController`:**
   - Port-to-port rubber-band dragging, flange-clearance orthogonal auto-routing (`1`, `2`, `3`, or `5` segments), T-junction branching onto existing pipes, segment dragging (`N_RESIZE_CURSOR` / `E_RESIZE_CURSOR`), and `Add Bend` splitting.
3. **`PipeRenderer`:**
   - 3-pass rendering: 3D cylindrical horizontal/vertical pipe walls, 3D mitered elbows/tees/flanges at vertices, and animated central fluid cutaway with velocity-driven `dashPhase`.
   - Automatic upstream-to-downstream fluid pressure & flow propagation solver across open valves and running pumps.

### Epoch 4: Rotating Machinery, Valves, Tanks, & Material Handling Symbols
**Goal:** Implement the complete industrial equipment catalog with smooth `60 FPS` procedural animations.
1. **Pumps, Motors, & Fans:**
   - `CentrifugalPump` (exact match to reference: 3D grey volute casing, top/side flanged ports, recessed circular window, rotating 8-wedge blue/navy impeller, center metallic cap), `InlinePump`, `MeteringPump`, `ElectricMotor`, `BlowerFan`.
2. **Valves & Dampers:**
   - `HandValve`, `SolenoidValve` (3D actuator coil on top + color-coded valve body), `ControlValve` (diaphragm dome + rising stem position indicator `0..100%`), `CheckValve`, `Damper` (rotating internal louvre blade).
3. **Tanks, Vessels, & Agitators:**
   - `CylindricalTank`, `HorizontalTank`, `HopperSilo`, `OpenBasin` with smooth interpolated liquid level cutaway (`0..100%`), graduated ruler ticks, optional animated surface wave, and `TankAgitator` (rotating shaft + 3D mixing paddles).
4. **Conveyors & Linear Machines:**
   - `ConveyorBelt` (2 rotating 3D end-rollers + translating belt cleats and moving workpieces), `ScrewAuger` (translating helical flight phase), `PneumaticCylinder` (animated extending/retracting chrome piston rod + end-of-stroke reed switch indicators), `HydraulicPress`, `HeaterCoil` (interpolated orange/red thermal glow).

### Epoch 5: Sensors, Operator Controls, Alarms, & Full-Screen Operator Runtime
**Goal:** Complete the instrumentation, interactive operator controls, properties dialogs, alarm management, and locked-down Operator View mode.
1. **Sensors & Instrumentation:**
   - `PressureSensor` (3D circular dial gauge with rotating needle + digital box), `TempSensor` (thermowell + mercury/digital bar), `FlowMeter`, `LevelSwitch`, `ProximitySensor`.
2. **Interactive Operator Controls & Displays:**
   - `PushButton` (momentary/latching 3D industrial pushbutton), `SelectorSwitch` (2/3-position rotary cam switch), `EStopMushroom`, `PilotLamp`, `NumericReadout`, `NumericSetpoint` (inline keypad / slider popover), `BarGraph`, and `TrendChart` (live multi-pen scrolling strip chart).
3. **`ScadaSymbolPropertiesDialog`:**
   - Win95/7 split dialog: Left `JTable` for standard geometry/appearance (`Name`, `X`, `Y`, `Width`, `Height`, `Rotation`, `FluidType`, `Tooltip`); Right panel for tag bindings (`RunTag`, `SpeedTag`, `FaultTag`, `LevelTag`, `SetpointTag`, `Min`, `Max`, `EngineeringUnits`) with `OperandPickerDialog` button.
4. **Alarm Engine & Operator Mode:**
   - Configurable High/Low/Fault alarm thresholds per tag, live bottom `AlarmBannerPanel` (with `UNACK` blinking, timestamp, and `[Acknowledge]` button), screen navigation buttons, and seamless switching between **Design Mode** and **Operator Mode** (plus standalone launch from Maraflow's toolbar or CLI).
