package tool104.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
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
import org.openmuc.j60870.ie.IeQualifierOfInterrogation;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.IeTime56;
import org.openmuc.j60870.ie.InformationObject;

/**
 * Spike（ADR-0002 验证）：j60870 的 Server 能否以 TCP 服务端身份行使主站职能。
 *
 * 服务端 = 主站替身：监听、等子站 STARTDT、下发总召/对时/单点遥控。
 * 客户端 = 模拟子站：连入、STARTDT、应答 ACTCON、上送遥信、确认遥控。
 */
@Timeout(30)
class J60870ServerAsMasterSpikeTest {

    private static final int COMMON_ADDRESS = 1;
    private static final int CONTROL_IOA = 6001;
    private static final int SIGNAL_IOA = 1001;

    private final int port = ThreadLocalRandom.current().nextInt(20000, 60000);

    private Server server;
    private Connection substationSide;

    private final CompletableFuture<Connection> masterSideConnection = new CompletableFuture<>();
    private final CompletableFuture<Boolean> dataTransferStarted = new CompletableFuture<>();
    private final BlockingQueue<ASdu> receivedByMaster = new LinkedBlockingQueue<>();
    private final BlockingQueue<ASdu> receivedBySubstation = new LinkedBlockingQueue<>();

    @BeforeEach
    void startMasterServerAndConnectSubstation() throws IOException {
        server = Server.builder().setPort(port).setMaxConnections(1).build();
        server.start(new ServerEventListener() {
            @Override
            public ConnectionEventListener connectionIndication(Connection connection) {
                masterSideConnection.complete(connection);
                return new ConnectionEventListener() {
                    @Override
                    public void newASdu(Connection c, ASdu aSdu) {
                        receivedByMaster.add(aSdu);
                    }

                    @Override
                    public void connectionClosed(Connection c, IOException cause) {
                    }

                    @Override
                    public void dataTransferStateChanged(Connection c, boolean stopped) {
                        if (!stopped) {
                            dataTransferStarted.complete(true);
                        }
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

        substationSide = new ClientConnectionBuilder("127.0.0.1")
                .setPort(port)
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
        substationSide.startDataTransfer();
    }

    @AfterEach
    void tearDown() {
        if (substationSide != null) {
            substationSide.close();
        }
        if (server != null) {
            server.stop();
        }
    }

    @Test
    void masterAsServerCanInterrogateSyncClocksAndSendSingleCommand() throws Exception {
        Connection master = masterSideConnection.get(5, TimeUnit.SECONDS);
        assertTrue(dataTransferStarted.get(5, TimeUnit.SECONDS), "服务端应感知到 STARTDT 完成");

        // --- 总召唤 ---
        master.interrogation(COMMON_ADDRESS, CauseOfTransmission.ACTIVATION, new IeQualifierOfInterrogation(20));

        ASdu giRequest = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(giRequest, "子站应收到总召");
        assertEquals(ASduType.C_IC_NA_1, giRequest.getTypeIdentification());
        assertEquals(CauseOfTransmission.ACTIVATION, giRequest.getCauseOfTransmission());

        // 子站应答：ACTCON → 上送一个遥信 → ACTTERM
        substationSide.sendConfirmation(giRequest);
        substationSide.send(new ASdu(ASduType.M_SP_NA_1, false, CauseOfTransmission.INTERROGATED_BY_STATION,
                false, false, 0, COMMON_ADDRESS,
                new InformationObject(SIGNAL_IOA,
                        new IeSinglePointWithQuality(true, false, false, false, false))));
        substationSide.sendActivationTermination(giRequest);

        ASdu giConfirm = receivedByMaster.poll(5, TimeUnit.SECONDS);
        assertNotNull(giConfirm);
        assertEquals(ASduType.C_IC_NA_1, giConfirm.getTypeIdentification());
        assertEquals(CauseOfTransmission.ACTIVATION_CON, giConfirm.getCauseOfTransmission());

        ASdu uploaded = receivedByMaster.poll(5, TimeUnit.SECONDS);
        assertNotNull(uploaded);
        assertEquals(ASduType.M_SP_NA_1, uploaded.getTypeIdentification());
        assertEquals(SIGNAL_IOA, uploaded.getInformationObjects()[0].getInformationObjectAddress());
        IeSinglePointWithQuality sp =
                (IeSinglePointWithQuality) uploaded.getInformationObjects()[0].getInformationElements()[0][0];
        assertTrue(sp.isOn());

        ASdu giTermination = receivedByMaster.poll(5, TimeUnit.SECONDS);
        assertNotNull(giTermination);
        assertEquals(CauseOfTransmission.ACTIVATION_TERMINATION, giTermination.getCauseOfTransmission());

        // --- 时钟同步 ---
        master.synchronizeClocks(COMMON_ADDRESS, new IeTime56(System.currentTimeMillis()));

        ASdu clockRequest = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(clockRequest, "子站应收到对时");
        assertEquals(ASduType.C_CS_NA_1, clockRequest.getTypeIdentification());

        substationSide.sendConfirmation(clockRequest);
        ASdu clockConfirm = receivedByMaster.poll(5, TimeUnit.SECONDS);
        assertNotNull(clockConfirm);
        assertEquals(ASduType.C_CS_NA_1, clockConfirm.getTypeIdentification());
        assertEquals(CauseOfTransmission.ACTIVATION_CON, clockConfirm.getCauseOfTransmission());

        // --- 单点遥控（直接执行）---
        master.singleCommand(COMMON_ADDRESS, CauseOfTransmission.ACTIVATION, CONTROL_IOA,
                new IeSingleCommand(true, 0, false));

        ASdu commandRequest = receivedBySubstation.poll(5, TimeUnit.SECONDS);
        assertNotNull(commandRequest, "子站应收到遥控");
        assertEquals(ASduType.C_SC_NA_1, commandRequest.getTypeIdentification());
        assertEquals(CONTROL_IOA, commandRequest.getInformationObjects()[0].getInformationObjectAddress());
        IeSingleCommand cmd =
                (IeSingleCommand) commandRequest.getInformationObjects()[0].getInformationElements()[0][0];
        assertTrue(cmd.isCommandStateOn());
        assertTrue(!cmd.isSelect(), "应为直接执行而非 SBO 选择");

        substationSide.sendConfirmation(commandRequest);
        ASdu commandConfirm = receivedByMaster.poll(5, TimeUnit.SECONDS);
        assertNotNull(commandConfirm);
        assertEquals(ASduType.C_SC_NA_1, commandConfirm.getTypeIdentification());
        assertEquals(CauseOfTransmission.ACTIVATION_CON, commandConfirm.getCauseOfTransmission());
    }
}
