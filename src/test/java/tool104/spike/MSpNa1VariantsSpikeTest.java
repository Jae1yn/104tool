package tool104.spike;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.Server;
import org.openmuc.j60870.ServerEventListener;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.InformationElement;
import org.openmuc.j60870.ie.InformationObject;

import tool104.protocol.SessionListener;
import tool104.protocol.j60870.J60870MasterSession;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.ConnectionMode;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;

/**
 * Spike：复现现场"端口含 M_SP_NA_1 点时连接即断"。
 * 模拟子站在 STARTDT 后依次发送多种形态的 M_SP_NA_1，观察工具侧连接是否存活。
 */
class MSpNa1VariantsSpikeTest {

    @Test
    void mspVariantsShouldNotKillConnection() throws Exception {
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }

        CountDownLatch transferStarted = new CountDownLatch(1);
        Server server = Server.builder().setPort(port).setMaxConnections(1).build();
        server.start(new ServerEventListener() {
            @Override
            public ConnectionEventListener connectionIndication(Connection connection) {
                return new ConnectionEventListener() {
                    @Override
                    public void newASdu(Connection c, ASdu aSdu) {
                    }

                    @Override
                    public void connectionClosed(Connection c, IOException cause) {
                        System.out.println("[server] 工具侧连接关闭: " + cause);
                    }

                    @Override
                    public void dataTransferStateChanged(Connection c, boolean stopped) {
                        if (!stopped) {
                            transferStarted.countDown();
                            new Thread(() -> sendVariants(c)).start();
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

        List<String> frames = new CopyOnWriteArrayList<>();
        List<PointUpdate> updates = new CopyOnWriteArrayList<>();
        J60870MasterSession session = new J60870MasterSession(ConnectionMode.DIAL, "127.0.0.1", port, 1, 3000);
        session.addListener(new SessionListener() {
            @Override
            public void onConnectionEvent(ConnectionEvent event) {
                System.out.println("[tool] 连接事件: " + event.state());
            }

            @Override
            public void onPointUpdate(PointUpdate update) {
                updates.add(update);
            }

            @Override
            public void onFrame(RawFrame frame) {
                frames.add(frame.summary());
                System.out.println("[tool] " + frame.summary());
            }
        });
        session.start();

        assertTrue(transferStarted.await(5, TimeUnit.SECONDS), "STARTDT 未完成");
        Thread.sleep(3000);

        System.out.println("[tool] 共收到点更新 " + updates.size() + " 条");
        boolean disconnected = frames.stream().anyMatch(f -> f.startsWith("连接断开"));
        session.stop();
        server.stop();
        assertTrue(!disconnected, "连接被 M_SP_NA_1 变体杀死，见上方日志");
    }

    private static void sendVariants(Connection c) {
        try {
            // 变体1：SQ=0，单对象（e2e 已验证过的基线）
            c.send(new ASdu(ASduType.M_SP_NA_1, false, CauseOfTransmission.SPONTANEOUS, false, false, 0, 1,
                    new InformationObject(1001, sp(true))));
            System.out.println("[server] 已发 变体1 SQ=0 单对象");
            Thread.sleep(200);

            // 变体2：SQ=0，多对象
            c.send(new ASdu(ASduType.M_SP_NA_1, false, CauseOfTransmission.SPONTANEOUS, false, false, 0, 1,
                    new InformationObject(1001, sp(true)),
                    new InformationObject(1002, sp(false)),
                    new InformationObject(1003, sp(true))));
            System.out.println("[server] 已发 变体2 SQ=0 多对象");
            Thread.sleep(200);

            // 变体3：SQ=1，打包序列（总召响应常见形态）
            InformationElement[][] seq = new InformationElement[8][];
            for (int i = 0; i < seq.length; i++) {
                seq[i] = new InformationElement[] { sp(i % 2 == 0) };
            }
            c.send(new ASdu(ASduType.M_SP_NA_1, true, CauseOfTransmission.INTERROGATED_BY_STATION,
                    false, false, 0, 1, new InformationObject(1001, seq)));
            System.out.println("[server] 已发 变体3 SQ=1 打包8点");
            Thread.sleep(200);

            // 变体4：SQ=1，大包（127 点，NumIx 上限）
            InformationElement[][] big = new InformationElement[127][];
            for (int i = 0; i < big.length; i++) {
                big[i] = new InformationElement[] { sp(true) };
            }
            c.send(new ASdu(ASduType.M_SP_NA_1, true, CauseOfTransmission.INTERROGATED_BY_STATION,
                    false, false, 0, 1, new InformationObject(1001, big)));
            System.out.println("[server] 已发 变体4 SQ=1 打包127点");
        } catch (IOException | InterruptedException e) {
            System.out.println("[server] 发送中断: " + e);
        }
    }

    private static IeSinglePointWithQuality sp(boolean on) {
        return new IeSinglePointWithQuality(on, false, false, false, false);
    }
}
