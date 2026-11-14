package cz.maramat.sim;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.IecLanguage;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorBridgeServerTest {

    @Test
    void bridgeServerReadsWritesAndControlsSimulation() throws Exception {
        Project project = Project.createDefaultSampleProject();
        project.setProjectName("Pump Demo");
        Page page = project.getPages().getFirst();

        BlockInstance boolVar = new BlockInstance(BlockCatalog.getById("Visual.Vars.BoolVar"), "PumpEnable", 10, 10, 1, 1);
        boolVar.getCustomProperties().put("CurrentValue", "0");
        page.getBlocks().add(boolVar);

        StVariable stLevel = new StVariable(StVariable.Scope.VAR, "TankLevel", DataType.INT, "42", "");
        page.getStVariables().add(stLevel);

        SimulationEngine engine = new SimulationEngine(() -> {});
        AtomicInteger notifications = new AtomicInteger(0);
        SimulatorBridgeServer server = new SimulatorBridgeServer(engine, () -> project, notifications::incrementAndGet);
        server.start(0);
        int port = server.getPort();
        assertTrue(port > 0);
        assertTrue(server.isRunning());

        try (Socket sock = new Socket("127.0.0.1", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("PING");
            assertEquals("OK PONG", in.readLine());

            out.println("STATUS");
            String status = in.readLine();
            assertTrue(status.startsWith("OK state=STOPPED estop=0 speed=1.0 project=Pump+Demo"));

            out.println("START");
            assertEquals("OK state=RUNNING", in.readLine());
            assertEquals(SimulationEngine.State.RUNNING, engine.getState());

            out.println("SET DI0 1.0");
            assertEquals("OK", in.readLine());
            assertEquals(1.0, engine.getOperandValue(project, "DI0"));

            out.println("SET PumpEnable.Y 1");
            assertEquals("OK", in.readLine());
            assertEquals("1", boolVar.getCustomProperties().get("CurrentValue"));

            out.println("SET TankLevel 75");
            assertEquals("OK", in.readLine());
            assertEquals(75.0, stLevel.getRuntimeValue());

            out.println("MGET DI0 PumpEnable.Y TankLevel");
            assertEquals("OK 1.0 1.0 75.0", in.readLine());

            out.println("SPEED 2.5");
            assertEquals("OK speed=2.5", in.readLine());
            assertEquals(2.5, engine.getSpeedMultiplier());

            out.println("PAUSE");
            assertEquals("OK state=PAUSED", in.readLine());
            assertEquals(SimulationEngine.State.PAUSED, engine.getState());

            out.println("STOP");
            assertEquals("OK state=STOPPED", in.readLine());
            assertEquals(SimulationEngine.State.STOPPED, engine.getState());

            assertTrue(notifications.get() >= 3);
        } finally {
            server.stop();
        }
    }

    @Test
    void subPollSendsDeltaUpdatesAndBoolTrueFalseParsesCleanly() throws Exception {
        assertEquals(1.0, DataType.BOOL.toNumericValue("TRUE"));
        assertEquals(0.0, DataType.BOOL.toNumericValue("FALSE"));

        Project project = new Project();
        project.setProjectName("Delta Demo");
        Page stPage = new Page("Control", IecLanguage.ST);
        stPage.getStVariables().add(new StVariable(StVariable.Scope.VAR, "Flag", DataType.BOOL, "FALSE", ""));
        stPage.getStVariables().add(new StVariable(StVariable.Scope.VAR, "Temp", DataType.FLOAT, "21.5", ""));
        stPage.getStVariables().add(new StVariable(StVariable.Scope.VAR, "Count", DataType.INT, "0", ""));
        stPage.setStCode("""
                IF Flag THEN
                    Count := Count + 1;
                END_IF;
                """);
        project.getPages().add(stPage);

        SimulationEngine engine = new SimulationEngine(() -> {});
        SimulatorBridgeServer server = new SimulatorBridgeServer(engine, () -> project, () -> {});
        server.start(0);
        int port = server.getPort();

        try (Socket sock = new Socket("127.0.0.1", port);
             BufferedReader in = new BufferedReader(new InputStreamReader(sock.getInputStream(), StandardCharsets.UTF_8));
             PrintWriter out = new PrintWriter(new OutputStreamWriter(sock.getOutputStream(), StandardCharsets.UTF_8), true)) {

            out.println("SUB Flag Temp Count");
            assertEquals("OK 3", in.readLine());

            // Initial POLL must return all 3 subscribed indices
            out.println("POLL");
            String first = in.readLine();
            assertTrue(first.startsWith("OK state=STOPPED"));
            assertTrue(first.contains("| 0=0.0 1=21.5 2=0.0"), "Expected initial full snapshot, got: " + first);

            // Second POLL with no changes must return empty delta section
            out.println("POLL");
            String second = in.readLine();
            assertTrue(second.endsWith("|"), "Expected empty delta, got: " + second);

            // Mutate Flag and execute ST scan -> Count increments once
            out.println("SET Flag 1.0");
            assertEquals("OK", in.readLine());
            engine.stepOnceForTesting(project, 10.0);
            assertEquals(1.0, engine.getOperandValue(project, "Flag"));
            assertEquals(1.0, engine.getOperandValue(project, "Count"));

            // Third POLL must include only changed indices (0 and 2), omitting unchanged index 1
            out.println("POLL");
            String third = in.readLine();
            assertTrue(third.contains("0=1.0"), "Expected changed index 0 in delta: " + third);
            assertTrue(third.contains("2=1.0"), "Expected changed index 2 in delta: " + third);
            assertFalse(third.contains("1="), "Unchanged index 1 must be omitted from delta: " + third);
        } finally {
            server.stop();
        }
    }
}
