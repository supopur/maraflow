package cz.maramat.sim;

import cz.maramat.comm.MaraProtClient;
import cz.maramat.comm.MaraTransport;
import cz.maramat.model.BlockCatalog;
import cz.maramat.model.BlockInstance;
import cz.maramat.model.DataType;
import cz.maramat.model.Page;
import cz.maramat.model.Project;
import cz.maramat.model.StVariable;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SimulatorBridgeServerTest {

    @Test
    void maraProtBridgeAuthenticatesAndControlsSimulation() throws Exception {
        Project project = Project.createDefaultSampleProject();
        project.setProjectName("Pump Demo");
        project.getUnitConfig().setAccessPassword("secret123");
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

        try {
            // Wrong password must fail
            try (MaraProtClient badClient = new MaraProtClient(new MaraTransport.TcpTransport("127.0.0.1", port), 1)) {
                badClient.connect();
                assertThrows(IOException.class, () -> badClient.authenticate("wrong"));
            }

            // Valid password authenticates and controls simulation
            try (MaraProtClient client = new MaraProtClient(new MaraTransport.TcpTransport("127.0.0.1", port), 1)) {
                client.connect();
                client.authenticate("secret123");

                MaraProtClient.PlcInfo info = client.getInfo();
                assertEquals("MaraFlow Sim", info.modelName());

                client.controlVm(MaraProtClient.CTRL_START);
                assertEquals(SimulationEngine.State.RUNNING, engine.getState());

                client.controlVm(MaraProtClient.CTRL_PAUSE);
                assertEquals(SimulationEngine.State.PAUSED, engine.getState());

                client.controlVm(MaraProtClient.CTRL_STOP);
                assertEquals(SimulationEngine.State.STOPPED, engine.getState());
                assertTrue(notifications.get() >= 3);
            }
        } finally {
            server.stop();
        }
    }
}
