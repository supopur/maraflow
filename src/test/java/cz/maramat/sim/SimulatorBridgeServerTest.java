package cz.maramat.sim;

import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
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
}
