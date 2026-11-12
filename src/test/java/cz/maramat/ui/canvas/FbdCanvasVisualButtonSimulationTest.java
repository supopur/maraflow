package cz.maramat.ui.canvas;

import cz.maramat.model.GraphicElement;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.sim.SimulationEngine;
import org.junit.jupiter.api.Test;

import java.awt.event.MouseEvent;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class FbdCanvasVisualButtonSimulationTest {

    private static FbdCanvas createCanvas(Project project, SimulationEngine simEngine, AtomicInteger editCount) {
        FbdCanvas canvas = new FbdCanvas(project, simEngine, new FbdCanvas.CanvasEditListener() {
            @Override
            public void onBeforePageEdit(String actionName, Page snapshotBefore) {}

            @Override
            public void onAfterPageEdit(String actionName, Page snapshotBefore, Page snapshotAfter) {
                editCount.incrementAndGet();
            }

            @Override
            public void onResetToolToCursor() {}

            @Override
            public void onInspectMacroPage(String macroTypeName) {}

            @Override
            public boolean isPinOnScope(String pinRef) {
                return false;
            }

            @Override
            public void onTogglePinOnScope(String pinRef) {}
        });
        canvas.setSize(800, 600);
        return canvas;
    }

    private static MouseEvent mouseEvent(FbdCanvas canvas, int id, int x, int y, int clickCount) {
        return new MouseEvent(
                canvas,
                id,
                System.currentTimeMillis(),
                MouseEvent.BUTTON1_DOWN_MASK,
                x,
                y,
                clickCount,
                false,
                MouseEvent.BUTTON1
        );
    }

    @Test
    void doubleClickAndDragOnVisualButtonAreBlockedInSimulationMode() {
        Project project = new Project();
        Page page = new Page("Main", IecLanguage.FBD);
        project.getPages().clear();
        project.getPages().add(page);

        GraphicElement toggleBtn = new GraphicElement(GraphicElement.Kind.VISUAL_BUTTON, 4, 4, 5, 2);
        toggleBtn.setToggleButton(true);
        page.getGraphics().add(toggleBtn);

        GraphicElement pushBtn = new GraphicElement(GraphicElement.Kind.VISUAL_BUTTON, 12, 4, 5, 2);
        pushBtn.setToggleButton(false);
        page.getGraphics().add(pushBtn);

        SimulationEngine simEngine = new SimulationEngine(() -> {});
        AtomicInteger editCount = new AtomicInteger(0);
        FbdCanvas canvas = createCanvas(project, simEngine, editCount);

        simEngine.startSimulation(project);
        try {
            assertEquals(SimulationEngine.State.RUNNING, simEngine.getState());
            int g = canvas.getGridUnitPx();

            // Double-click toggle button: should toggle twice, NOT open modal, NOT stop simulation
            int tx = 6 * g;
            int ty = 5 * g;
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_PRESSED, tx, ty, 1));
            assertTrue(toggleBtn.isActiveState());
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_RELEASED, tx, ty, 1));

            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_PRESSED, tx, ty, 2));
            assertFalse(toggleBtn.isActiveState());
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_RELEASED, tx, ty, 2));

            assertEquals(SimulationEngine.State.RUNNING, simEngine.getState());
            assertEquals(4, toggleBtn.getGridX());
            assertEquals(4, toggleBtn.getGridY());
            assertEquals(0, editCount.get());

            // Press and drag momentary button: should activate on press, stay at same position, deactivate on release
            int px = 14 * g;
            int py = 5 * g;
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_PRESSED, px, py, 1));
            assertTrue(pushBtn.isActiveState());
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, px + 4 * g, py + 3 * g, 0));
            assertEquals(12, pushBtn.getGridX());
            assertEquals(4, pushBtn.getGridY());
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_RELEASED, px + 4 * g, py + 3 * g, 1));
            assertFalse(pushBtn.isActiveState());

            assertEquals(SimulationEngine.State.RUNNING, simEngine.getState());
            assertEquals(0, editCount.get());
        } finally {
            simEngine.stopSimulation(project);
        }
    }

    @Test
    void preSelectedVisualButtonCannotBeResizedDeletedCutOrDuplicatedInSimulationMode() {
        Project project = new Project();
        Page page = new Page("Main", IecLanguage.FBD);
        project.getPages().clear();
        project.getPages().add(page);

        GraphicElement btn = new GraphicElement(GraphicElement.Kind.VISUAL_BUTTON, 5, 5, 5, 2);
        page.getGraphics().add(btn);

        SimulationEngine simEngine = new SimulationEngine(() -> {});
        AtomicInteger editCount = new AtomicInteger(0);
        FbdCanvas canvas = createCanvas(project, simEngine, editCount);

        // Select the visual button before starting simulation
        canvas.selectAll();

        simEngine.startSimulation(project);
        try {
            int g = canvas.getGridUnitPx();

            // Attempt to drag its SE resize handle (corner at (10*g, 7*g))
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_PRESSED, 10 * g, 7 * g, 1));
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_DRAGGED, 14 * g, 10 * g, 0));
            canvas.dispatchEvent(mouseEvent(canvas, MouseEvent.MOUSE_RELEASED, 14 * g, 10 * g, 1));

            assertEquals(5, btn.getGridX());
            assertEquals(5, btn.getGridY());
            assertEquals(5, btn.getGridW());
            assertEquals(2, btn.getGridH());
            assertEquals(SimulationEngine.State.RUNNING, simEngine.getState());

            // Attempt delete, cut, duplicate
            canvas.deleteSelection();
            canvas.cutSelection();
            canvas.duplicateSelection();

            assertEquals(1, page.getGraphics().size());
            assertEquals(SimulationEngine.State.RUNNING, simEngine.getState());
            assertEquals(0, editCount.get());
        } finally {
            simEngine.stopSimulation(project);
        }
    }
}
