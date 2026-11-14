package cz.maramat.io;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.LadderElement;
import cz.maramat.model.LadderRung;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import cz.maramat.model.Wire;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.awt.Point;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class MflProjectSerializerTest {

    @Test
    void saveAndLoadRoundTripPreservesFbdLdStAndMacroPages(@TempDir Path tmpDir) throws IOException {
        Project project = new Project("RoundTrip", "STM32F407-Maraflow-Std", IecLanguage.FBD, 2);
        project.getPages().clear();

        Page fbd = new Page("MainFbd", IecLanguage.FBD);
        BlockInstance and1 = new BlockInstance(BlockCatalog.getById("Bool.And"), "And1", 4, 4, 2, 10);
        BlockInstance out1 = new BlockInstance(BlockCatalog.getById("IO.OREF"), "Out1", 14, 4, 2, 20);
        fbd.getBlocks().add(and1);
        fbd.getBlocks().add(out1);
        fbd.getWires().add(new Wire(and1.getId(), 0, out1.getId(), 0, "Net1", new Point(10, 5), new Point(14, 5)));

        Page ld = new Page("MainLd", IecLanguage.LD);
        LadderRung rung = new LadderRung(2, 10, "Start");
        rung.getMainElements().add(new LadderElement(LadderElement.Kind.NO_CONTACT, "DI0"));
        rung.getCoils().add(new LadderElement(LadderElement.Kind.NORMAL_COIL, "DO0"));
        ld.getRungs().add(rung);

        Page st = new Page("MainSt", IecLanguage.ST);
        st.getStVariables().add(new StVariable(StVariable.Scope.VAR, "Speed", DataType.INT, "42", "Motor speed"));
        st.setStCode("Speed := Speed + 1;");

        Page macro = new Page("*MyMacro", IecLanguage.FBD);
        macro.getBlocks().add(new BlockInstance(BlockCatalog.getById("Macro.MacroIn"), "In1", 2, 2, 2, 10));

        project.getPages().add(fbd);
        project.getPages().add(ld);
        project.getPages().add(st);
        project.getPages().add(macro);

        File mfl = tmpDir.resolve("test.mfl").toFile();
        MflProjectSerializer.saveProject(project, mfl);
        assertFalse(project.isDirty());

        Project loaded = MflProjectSerializer.loadProject(mfl);
        assertEquals("RoundTrip", loaded.getProjectName());
        assertEquals(4, loaded.getPages().size());

        Page loadedFbd = loaded.getPages().get(0);
        assertEquals(2, loadedFbd.getBlocks().size());
        assertEquals(1, loadedFbd.getWires().size());
        assertEquals("And1", loadedFbd.getBlocks().get(0).getName());

        Page loadedLd = loaded.getPages().get(1);
        assertEquals(IecLanguage.LD, loadedLd.getLanguage());
        assertEquals(1, loadedLd.getRungs().size());
        assertEquals("DI0", loadedLd.getRungs().get(0).getMainElements().get(0).getOperand());

        Page loadedSt = loaded.getPages().get(2);
        assertEquals(IecLanguage.ST, loadedSt.getLanguage());
        assertEquals("Speed := Speed + 1;", loadedSt.getStCode());
        assertNotNull(loadedSt.findStVariableByName("speed"));

        assertTrue(loaded.getPages().get(3).isMacro());
    }

    @Test
    void loadProjectRejectsUnknownBlockDefinition(@TempDir Path tmpDir) throws IOException {
        Project project = new Project();
        project.getPages().get(0).getBlocks().add(
                new BlockInstance(BlockCatalog.getById("Bool.And"), "And1", 4, 4, 2, 10)
        );
        File mfl = tmpDir.resolve("bad.mfl").toFile();
        MflProjectSerializer.saveProject(project, mfl);

        String xml = Files.readString(mfl.toPath(), StandardCharsets.UTF_8)
                .replace("Bool.And", "NonExistent.Block");
        Files.writeString(mfl.toPath(), xml, StandardCharsets.UTF_8);

        assertThrows(IOException.class, () -> MflProjectSerializer.loadProject(mfl));
    }
}
