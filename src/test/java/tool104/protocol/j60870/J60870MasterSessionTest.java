package tool104.protocol.j60870;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.ClientConnectionBuilder;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.Server;
import org.openmuc.j60870.ServerEventListener;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.InformationObject;

import tool104.protocol.SessionListener;
import tool104.protocol.model.CommandResult;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.ConnectionMode;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;

/**
 * J60870MasterSession 集成测试：用 j60870 客户端角色模拟子站经回环 TCP 连入。
 */
@Timeout(30)
class J60870MasterSessionTest {

    private static final int COMMON_ADDRESS = 1;

    private final int port = ThreadLocalRandom.current().nextInt(20000, 60000);

    private J60870MasterSession session;
    private Connection substation;

    private final BlockingQueue<ConnectionEvent> connectionEvents = new LinkedBlockingQueue<>();
    private final BlockingQueue<PointUpdate> pointUpdates = new LinkedBlockingQueue<>();
    private final BlockingQueue<RawFrame> frames = new LinkedBlockingQueue<>();
    private final BlockingQueue<ASdu> receivedBySubstation = new LinkedBlockingQueue<>();

    private Server fakeSubstationServer;

    @BeforeEach
    void setUp() throws IOException {
        session = newSession(ConnectionMode.LISTEN, "127.0.0.1", port);
        session.start();
    }

    @AfterEach
    void tearDown() {
        if (substation != null) {
            substation.close();
        }
        session.stop();
        if (fakeSubstationServer != null) {
            fakeSubstationServer.stop();
        }
    }

    private J60870MasterSession newSession(ConnectionMode mode, String host, int targetPort) {
        J60870MasterSession s = new J60870MasterSession(mode, host, targetPort, COMMON_ADDRESS, 1000);
        s.addListener(new SessionListener() {
            @Override
            public void onConnectionEvent(ConnectionEvent event) {
                connectionEvents.add(event);
            }

            @Override
            public void onPointUpdate(PointUpdate update) {
                pointUpdates.add(update);
            }

            @Override
            public void onFrame(RawFrame frame) {
                frames.add(frame);
            }
        });
        return s;
    }

    private void connectSubstation() throws Exception {
        connectSubstation(port);
    }

    private void connectSubstation(int targetPort) throws Exception {
        substation = new ClientConnectionBuilder("127.0.0.1")
                .setPort(targetPort)
                .setConnectionEventListener(new ConnectionEventListener() {
                    @Override
                    public void newASdu(Connection c, ASdu aSdu) {
                        receivedBySubstation.add(aSdu);
                    }

                    @Override
                    public void connectionClosed(Connection c, IOException cause) {
                    }

                    @Override
                    public void dataTransferStateChanged(Connection c, boolean stopped) {
                    }
                })
                .build();
        substation.startDataTransfer();
    }

    private ConnectionEvent awaitConnectionState(ConnectionEvent.State expected) throws InterruptedException {
        while (true) {
            ConnectionEvent event = connectionEvents.poll(5, TimeUnit.SECONDS);
            assertNotNull(event, "等待 " + expected + " 事件超时");
            if (event.state() == expected) {
                return event;
            }
        }
    }

    @Test
    void reportsListeningThenConnectedWithRemoteAddress() throws Exception {
        awaitConnectionState(ConnectionEvent.State.LISTENING);
        connectSubstation();
        ConnectionEvent connected = awaitConnectionState(ConnectionEvent.State.CONNECTED);
        assertNotNull(connected.remoteAddress());
        assertTrue(connected.remoteAddress().startsWith("127.0.0.1:"));
        assertTrue(session.isSubstationConnected());
    }

    @Test
    void decodesUploadedPointsIntoPointUpdates() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        substation.send(new ASdu(ASduType.M_SP_NA_1, false, CauseOfTransmission.SPONTANEOUS,
                false, false, 0, COMMON_ADDRESS,
                new InformationObject(1001, new IeSinglePointWithQuality(true, false, false, false, false))));
        substation.send(new ASdu(ASduType.M_ME_NC_1, false, CauseOfTransmission.SPONTANEOUS,
                false, false, 0, COMMON_ADDRESS,
                new InformationObject(4001, new IeShortFloat(12.5f),
                        new org.openmuc.j60870.ie.IeQuality(false, false, false, false, false))));

        PointUpdate sp = pointUpdates.poll(5, TimeUnit.SECONDS);
        assertNotNull(sp);
        assertEquals(1001, sp.ioa());
        assertEquals("M_SP_NA_1", sp.type());
        assertEquals("合(1)", sp.value());
        assertEquals("有效", sp.quality());
        assertEquals("SPONTANEOUS", sp.cause());

        PointUpdate me = pointUpdates.poll(5, TimeUnit.SECONDS);
        assertNotNull(me);
        assertEquals(4001, me.ioa());
        assertEquals("12.5", me.value());
    }

    @Test
    void generalInterrogationReachesSubstation() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        session.sendGeneralInterrogation();

        ASdu received = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(received);
        assertEquals(ASduType.C_IC_NA_1, received.getTypeIdentification());
        assertEquals(CauseOfTransmission.ACTIVATION, received.getCauseOfTransmission());
    }

    @Test
    void singleCommandConfirmedBySubstation() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        var future = session.sendSingleCommand(6001, true);

        ASdu command = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(command);
        assertEquals(ASduType.C_SC_NA_1, command.getTypeIdentification());
        assertEquals(6001, command.getInformationObjects()[0].getInformationObjectAddress());
        substation.sendConfirmation(command);

        CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertEquals(CommandResult.Status.CONFIRMED, result.status());
    }

    @Test
    void singleCommandTimesOutWithoutConfirmation() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        CommandResult result = session.sendSingleCommand(6002, false).get(5, TimeUnit.SECONDS);
        assertEquals(CommandResult.Status.TIMEOUT, result.status());
    }

    @Test
    void commandWithoutConnectionFailsImmediately() throws Exception {
        CommandResult result = session.sendSingleCommand(6001, true).get(1, TimeUnit.SECONDS);
        assertEquals(CommandResult.Status.FAILED, result.status());
    }

    @Test
    void setpointCommandConfirmedBySubstation() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        var future = session.sendSetpointCommand(7001, 36.6f);

        ASdu command = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(command);
        assertEquals(ASduType.C_SE_NC_1, command.getTypeIdentification());
        assertEquals(7001, command.getInformationObjects()[0].getInformationObjectAddress());
        IeShortFloat sent = (IeShortFloat) command.getInformationObjects()[0].getInformationElements()[0][0];
        assertEquals(36.6f, sent.getValue());
        substation.sendConfirmation(command);

        CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertEquals(CommandResult.Status.CONFIRMED, result.status());
    }

    @Test
    void setpointCommandNegativeConfirmation() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        var future = session.sendSetpointCommand(7002, -1.5f);

        ASdu command = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(command);
        substation.send(new ASdu(ASduType.C_SE_NC_1, false, CauseOfTransmission.ACTIVATION_CON,
                false, true, 0, COMMON_ADDRESS, command.getInformationObjects()));

        CommandResult result = future.get(5, TimeUnit.SECONDS);
        assertEquals(CommandResult.Status.NEGATIVE, result.status());
    }

    @Test
    void setpointCommandWithoutConnectionFailsImmediately() throws Exception {
        CommandResult result = session.sendSetpointCommand(7001, 1.0f).get(1, TimeUnit.SECONDS);
        assertEquals(CommandResult.Status.FAILED, result.status());
    }

    @Test
    void secondSubstationConnectionIsRejected() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        assertThrows(IOException.class, () -> {
            Connection second = new ClientConnectionBuilder("127.0.0.1").setPort(port).build();
            second.startDataTransfer();
            second.close();
        });
        assertTrue(session.isSubstationConnected(), "原有连接不应受影响");
    }

    @Test
    void reconfigureMovesListeningToNewPort() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        int newPort = port + 1;
        session.reconfigure(ConnectionMode.LISTEN, "127.0.0.1", newPort, COMMON_ADDRESS, 1000);
        awaitConnectionState(ConnectionEvent.State.LISTENING);

        connectSubstation(newPort);
        awaitConnectionState(ConnectionEvent.State.CONNECTED);
        assertTrue(session.isSubstationConnected());
    }

    @Test
    void reconfigureWhileStoppedDoesNotStart() throws Exception {
        session.stop();
        awaitConnectionState(ConnectionEvent.State.STOPPED);

        session.reconfigure(ConnectionMode.LISTEN, "127.0.0.1", port + 1, COMMON_ADDRESS, 1000);

        assertTrue(!session.isRunning(), "停止状态下 reconfigure 不应自行启动监听");
    }

    @Test
    void disconnectedSubstationCanReconnect() throws Exception {
        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);

        substation.close();
        awaitConnectionState(ConnectionEvent.State.DISCONNECTED);
        awaitConnectionState(ConnectionEvent.State.LISTENING);

        connectSubstation();
        awaitConnectionState(ConnectionEvent.State.CONNECTED);
        assertTrue(session.isSubstationConnected());
    }

    @Test
    void dialModeConnectsToListeningSubstation() throws Exception {
        session.stop();
        awaitConnectionState(ConnectionEvent.State.STOPPED);

        int substationPort = ThreadLocalRandom.current().nextInt(20000, 60000);
        BlockingQueue<Connection> serverSideConnections = new LinkedBlockingQueue<>();
        fakeSubstationServer = startFakeSubstationServer(substationPort, serverSideConnections);

        session = newSession(ConnectionMode.DIAL, "127.0.0.1", substationPort);
        session.start();

        ConnectionEvent connected = awaitConnectionState(ConnectionEvent.State.CONNECTED);
        assertNotNull(connected.remoteAddress());
        assertTrue(connected.remoteAddress().startsWith("127.0.0.1:"));
        assertTrue(session.isSubstationConnected());
        assertNotNull(serverSideConnections.poll(5, TimeUnit.SECONDS), "子站（服务端）应感知到工具的拨入连接");
    }

    @Test
    void dialModeRetriesUntilSubstationBecomesAvailable() throws Exception {
        session.stop();
        awaitConnectionState(ConnectionEvent.State.STOPPED);

        int substationPort = ThreadLocalRandom.current().nextInt(20000, 60000);
        session = newSession(ConnectionMode.DIAL, "127.0.0.1", substationPort);
        session.start();
        awaitConnectionState(ConnectionEvent.State.LISTENING);

        assertNotNull(frames.poll(5, TimeUnit.SECONDS), "首次连接子站应立即失败并记录报文日志");

        fakeSubstationServer = startFakeSubstationServer(substationPort, new LinkedBlockingQueue<>());

        awaitConnectionState(ConnectionEvent.State.CONNECTED);
        assertTrue(session.isSubstationConnected());
    }

    private Server startFakeSubstationServer(int fakePort, BlockingQueue<Connection> serverSideConnections)
            throws IOException {
        Server fakeServer = Server.builder().setPort(fakePort).setMaxConnections(1).build();
        fakeServer.start(new ServerEventListener() {
            @Override
            public ConnectionEventListener connectionIndication(Connection connection) {
                serverSideConnections.add(connection);
                return new ConnectionEventListener() {
                    @Override
                    public void newASdu(Connection c, ASdu aSdu) {
                    }

                    @Override
                    public void connectionClosed(Connection c, IOException cause) {
                    }

                    @Override
                    public void dataTransferStateChanged(Connection c, boolean stopped) {
                    }
                };
            }

            @Override
            public void serverStoppedListeningIndication(IOException e) {
            }

            @Override
            public void connectionAttemptFailed(IOException e) {
            }
        });
        return fakeServer;
    }
}
