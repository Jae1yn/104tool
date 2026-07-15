package tool104.protocol.j60870;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.net.ServerSocket;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.Server;
import org.openmuc.j60870.ServerEventListener;
import org.openmuc.j60870.ie.IeQuality;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.InformationObject;

import tool104.protocol.SessionListener;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.ConnectionMode;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;

/**
 * 回归：拨号模式下"握手完即被踢的端口 + 中途 reconfigure 切换端口 + 手动 stop"不得泄漏连接。
 * 现场曾出现：切换端口并断开后，旧连接仍在收数据刷新界面。
 */
class DialReconfigureLeakTest {

    private Server kicker;
    private Server normal;
    private J60870MasterSession session;

    @AfterEach
    void tearDown() {
        if (session != null) {
            session.stop();
        }
        if (kicker != null) {
            kicker.stop();
        }
        if (normal != null) {
            normal.stop();
        }
    }

    @Test
    void reconfigureAwayFromKickingPortThenStopLeavesNoLiveConnection() throws Exception {
        int kickerPort = freePort();
        int normalPort = freePort();

        // 端口A：复刻现场 2404 —— STARTDT 后立即主动断开
        kicker = Server.builder().setPort(kickerPort).setMaxConnections(10).build();
        kicker.start(new SilentServerListener() {
            @Override
            public ConnectionEventListener connectionIndication(Connection connection) {
                return new SilentConnectionListener() {
                    @Override
                    public void dataTransferStateChanged(Connection c, boolean stopped) {
                        if (!stopped) {
                            c.close();
                        }
                    }
                };
            }
        });

        // 端口B：正常子站，STARTDT 后每 300ms 上送一条遥测
        AtomicInteger normalAccepts = new AtomicInteger();
        CountDownLatch normalConnected = new CountDownLatch(1);
        normal = Server.builder().setPort(normalPort).setMaxConnections(10).build();
        normal.start(new SilentServerListener() {
            @Override
            public ConnectionEventListener connectionIndication(Connection connection) {
                normalAccepts.incrementAndGet();
                return new SilentConnectionListener() {
                    @Override
                    public void dataTransferStateChanged(Connection c, boolean stopped) {
                        if (stopped) {
                            return;
                        }
                        normalConnected.countDown();
                        Thread pump = new Thread(() -> {
                            try {
                                while (!c.isClosed()) {
                                    c.send(new ASdu(ASduType.M_ME_NC_1, false, CauseOfTransmission.SPONTANEOUS,
                                            false, false, 0, 1,
                                            new InformationObject(4001, new IeShortFloat(220f),
                                                    new IeQuality(false, false, false, false, false))));
                                    Thread.sleep(300);
                                }
                            } catch (IOException | InterruptedException ignored) {
                            }
                        });
                        pump.setDaemon(true);
                        pump.start();
                    }
                };
            }
        });

        List<PointUpdate> updates = new CopyOnWriteArrayList<>();
        session = new J60870MasterSession(ConnectionMode.DIAL, "127.0.0.1", kickerPort, 1, 3000);
        session.addListener(new SessionListener() {
            @Override
            public void onConnectionEvent(ConnectionEvent event) {
            }

            @Override
            public void onPointUpdate(PointUpdate update) {
                updates.add(update);
            }

            @Override
            public void onFrame(RawFrame frame) {
            }
        });
        session.start();

        // 让踢人循环跑起来（至少经历一次"断开 → 3 秒后重连"）
        Thread.sleep(4000);

        // 现场操作：改配置切到正常端口（stop + start，代次递增）
        session.reconfigure(ConnectionMode.DIAL, "127.0.0.1", normalPort, 1, 3000);
        assertTrue(normalConnected.await(5, TimeUnit.SECONDS), "reconfigure 后未连上正常端口");

        // 覆盖旧代次残留重拨可能触发的窗口（重拨延迟 3 秒），期间正常端口只允许出现一条连接
        Thread.sleep(4000);
        assertEquals(1, normalAccepts.get(), "正常端口出现了多条连接：旧代次的重拨循环未被终止");
        assertTrue(updates.size() >= 2, "正常端口的数据未流入");

        // 现场操作：手动断开，之后不得再有任何数据刷新
        session.stop();
        Thread.sleep(500);
        int after = updates.size();
        Thread.sleep(1500);
        assertEquals(after, updates.size(), "stop() 后仍在收到点更新：存在泄漏的连接");
    }

    private static int freePort() throws IOException {
        try (ServerSocket probe = new ServerSocket(0)) {
            return probe.getLocalPort();
        }
    }

    private abstract static class SilentServerListener implements ServerEventListener {
        @Override
        public void serverStoppedListeningIndication(IOException e) {
        }

        @Override
        public void connectionAttemptFailed(IOException e) {
        }
    }

    private abstract static class SilentConnectionListener implements ConnectionEventListener {
        @Override
        public void newASdu(Connection connection, ASdu aSdu) {
        }

        @Override
        public void connectionClosed(Connection connection, IOException cause) {
        }
    }
}
