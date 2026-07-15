package tool104.e2e;

import java.io.IOException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicReference;

import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.Server;
import org.openmuc.j60870.ServerEventListener;
import org.openmuc.j60870.ie.IeQuality;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.IeTime56;
import org.openmuc.j60870.ie.InformationObject;

/**
 * 模拟子站（服务端角色）：用于验证工具的"客户端拨号"连接模式（ADR-0003）。
 * 与 {@link SimulatedSubstation}（子站扮演客户端，验证工具默认的服务端监听模式）互补。
 * 监听端口，等待工具拨入，应答总召/对时/遥控，周期性上送遥测遥信。
 * 用法: SimulatedSubstationServer [port] [运行秒数]
 */
public final class SimulatedSubstationServer {

    private static final int COMMON_ADDRESS = 1;

    public static void main(String[] args) throws Exception {
        int port = args.length > 0 ? Integer.parseInt(args[0]) : 2404;
        long runSeconds = args.length > 1 ? Long.parseLong(args[1]) : 300;

        AtomicReference<Connection> tool = new AtomicReference<>();
        Server server = Server.builder().setPort(port).setMaxConnections(1).build();
        server.start(new ServerEventListener() {
            @Override
            public ConnectionEventListener connectionIndication(Connection connection) {
                tool.set(connection);
                System.out.println("主站替身已拨入");
                return new Responder(connection);
            }

            @Override
            public void serverStoppedListeningIndication(IOException e) {
                System.out.println("监听终止: " + e.getMessage());
            }

            @Override
            public void connectionAttemptFailed(IOException e) {
                System.out.println("拒绝新的连接（仅支持单个主站替身连接）");
            }
        });
        System.out.println("模拟子站（服务端）已监听端口 " + port + "，等待主站替身拨入");

        long deadline = System.currentTimeMillis() + runSeconds * 1000;
        int tick = 0;
        while (System.currentTimeMillis() < deadline) {
            Thread.sleep(2000);
            Connection connection = tool.get();
            if (connection == null || connection.isClosed()) {
                continue;
            }
            tick++;
            boolean on = tick % 2 == 0;
            float value = 220f + ThreadLocalRandom.current().nextFloat() * 10f;
            try {
                connection.send(new ASdu(ASduType.M_SP_NA_1, false, CauseOfTransmission.SPONTANEOUS,
                        false, false, 0, COMMON_ADDRESS,
                        new InformationObject(1001,
                                new IeSinglePointWithQuality(on, false, false, false, false))));
                connection.send(new ASdu(ASduType.M_ME_NC_1, false, CauseOfTransmission.SPONTANEOUS,
                        false, false, 0, COMMON_ADDRESS,
                        new InformationObject(4001, new IeShortFloat(value),
                                new IeQuality(false, false, false, false, false))));
                System.out.println("上送: IOA1001=" + (on ? "合" : "分") + ", IOA4001=" + value);
            } catch (IOException e) {
                System.out.println("上送失败: " + e.getMessage());
            }
        }
        server.stop();
        System.out.println("模拟子站（服务端）退出");
    }

    private static final class Responder implements ConnectionEventListener {

        private final Connection connection;

        Responder(Connection connection) {
            this.connection = connection;
        }

        @Override
        public void newASdu(Connection connection, ASdu aSdu) {
            System.out.println("收到: " + aSdu.getTypeIdentification() + " " + aSdu.getCauseOfTransmission());
            try {
                switch (aSdu.getTypeIdentification()) {
                    case C_IC_NA_1 -> {
                        connection.sendConfirmation(aSdu);
                        connection.send(new ASdu(ASduType.M_SP_NA_1, false,
                                CauseOfTransmission.INTERROGATED_BY_STATION, false, false, 0, COMMON_ADDRESS,
                                new InformationObject(1001,
                                        new IeSinglePointWithQuality(true, false, false, false, false))));
                        connection.send(new ASdu(ASduType.M_ME_NC_1, false,
                                CauseOfTransmission.INTERROGATED_BY_STATION, false, false, 0, COMMON_ADDRESS,
                                new InformationObject(4001, new IeShortFloat(225.3f),
                                        new IeQuality(false, false, false, false, false))));
                        connection.sendActivationTermination(aSdu);
                        System.out.println("已应答总召唤（ACTCON + 全数据 + ACTTERM）");
                    }
                    case C_CS_NA_1 -> {
                        connection.sendConfirmation(aSdu);
                        System.out.println("已确认时钟同步");
                    }
                    case C_SC_NA_1 -> {
                        connection.sendConfirmation(aSdu);
                        System.out.println("已确认遥控 IOA="
                                + aSdu.getInformationObjects()[0].getInformationObjectAddress());
                    }
                    case C_SE_NC_1 -> {
                        connection.sendConfirmation(aSdu);
                        System.out.println("已确认遥调 IOA="
                                + aSdu.getInformationObjects()[0].getInformationObjectAddress()
                                + " 值=" + setpointValue(aSdu));
                    }
                    default -> {
                    }
                }
            } catch (IOException e) {
                System.out.println("应答失败: " + e.getMessage());
            }
        }

        @Override
        public void connectionClosed(Connection connection, IOException cause) {
            System.out.println("主站替身连接关闭: " + (cause == null ? "" : cause.getMessage()));
        }

        @Override
        public void dataTransferStateChanged(Connection connection, boolean stopped) {
        }

        private static float setpointValue(ASdu aSdu) {
            return ((IeShortFloat) aSdu.getInformationObjects()[0].getInformationElements()[0][0]).getValue();
        }
    }
}
