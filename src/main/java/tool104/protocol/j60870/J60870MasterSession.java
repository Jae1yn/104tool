package tool104.protocol.j60870;

import java.io.IOException;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import javax.net.ServerSocketFactory;
import javax.net.SocketFactory;

import org.openmuc.j60870.ASdu;
import org.openmuc.j60870.ASduType;
import org.openmuc.j60870.CauseOfTransmission;
import org.openmuc.j60870.ClientConnectionBuilder;
import org.openmuc.j60870.Connection;
import org.openmuc.j60870.ConnectionEventListener;
import org.openmuc.j60870.Server;
import org.openmuc.j60870.ServerEventListener;
import org.openmuc.j60870.ie.IeDoublePointWithQuality;
import org.openmuc.j60870.ie.IeNormalizedValue;
import org.openmuc.j60870.ie.IeQualifierOfInterrogation;
import org.openmuc.j60870.ie.IeQualifierOfSetPointCommand;
import org.openmuc.j60870.ie.IeQuality;
import org.openmuc.j60870.ie.IeScaledValue;
import org.openmuc.j60870.ie.IeShortFloat;
import org.openmuc.j60870.ie.IeSingleCommand;
import org.openmuc.j60870.ie.IeSinglePointWithQuality;
import org.openmuc.j60870.ie.IeTime56;
import org.openmuc.j60870.ie.InformationElement;
import org.openmuc.j60870.ie.InformationObject;

import tool104.protocol.MasterSession;
import tool104.protocol.SessionListener;
import tool104.protocol.model.CommandResult;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.ConnectionMode;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;

/**
 * 基于 openmuc j60870 的 MasterSession 实现。整个工程中唯一 import j60870 的地方。
 */
public final class J60870MasterSession implements MasterSession {

    private static final long DIAL_RETRY_DELAY_MS = 3000;

    private volatile ConnectionMode connectionMode;
    private volatile String substationHost;
    private volatile int port;
    private volatile int commonAddress;
    private volatile long commandTimeoutMs;

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final ConcurrentMap<Integer, PendingCommand> pendingCommands = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "command-timeout");
        t.setDaemon(true);
        return t;
    });

    private volatile Server server;
    private volatile Connection substation;
    private volatile boolean transferStarted;
    private volatile boolean running;
    private volatile String remoteAddress;
    /** 每次 start/stop/reconfigure 递增，用于让过期的拨号线程在返回时自行放弃结果，见 {@link #dialOnce}。 */
    private volatile long connectGeneration;

    private record PendingCommand(CompletableFuture<CommandResult> future) {
    }

    // ---- 原始帧捕获（TappedSocket 旁路抓帧，与摘要配对）----

    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();

    /**
     * 收到的 I 帧原始字节队列：tap 在 j60870 读线程上切出帧入队，newASdu 回调（j60870 派发在其它线程，
     * 但按连接内顺序串行）按 FIFO 取走配对。连接断开时清空，防止跨连接错配。
     */
    private final ConcurrentLinkedQueue<String> receivedRawQueue = new ConcurrentLinkedQueue<>();
    /** 我方 API 调用发帧时的捕获槽：调用前置入列表，j60870 在同线程内写出，tap 填入原始字节。 */
    private final ThreadLocal<List<String>> sendCapture = new ThreadLocal<>();

    private void onApdu(boolean sent, byte[] apdu) {
        String hex = HEX.formatHex(apdu);
        int ctl1 = apdu.length >= 3 ? apdu[2] & 0xFF : 0;
        boolean iFrame = (ctl1 & 0x01) == 0;
        if (sent) {
            List<String> capture = sendCapture.get();
            if (capture != null && iFrame) {
                capture.add(hex);
                return;
            }
            // j60870 内部自动发出：STARTDT/STOPDT/TESTFR 应答、S 帧确认等
            emitFrame(RawFrame.sent(apciLabel(apdu), hex));
            return;
        }
        if (iFrame) {
            receivedRawQueue.add(hex);
            return;
        }
        emitFrame(RawFrame.received(apciLabel(apdu), hex));
    }

    /** 取走与当前 newASdu 回调配对的原始帧 hex；取不到返回 null（优雅降级）。 */
    private String takeReceivedRaw() {
        return receivedRawQueue.poll();
    }

    /** 在捕获槽内执行我方发帧调用，返回发出帧的 hex（未捕获到返回 null）。 */
    private String captureSend(SendAction action) throws IOException {
        List<String> capture = new ArrayList<>(1);
        sendCapture.set(capture);
        try {
            action.run();
        } finally {
            sendCapture.remove();
        }
        return capture.isEmpty() ? null : String.join(" / ", capture);
    }

    private interface SendAction {
        void run() throws IOException;
    }

    /** 从 APCI 控制域解析 U/S 帧标注。 */
    private static String apciLabel(byte[] apdu) {
        if (apdu.length < 6) {
            return "APCI（不完整）";
        }
        int ctl1 = apdu[2] & 0xFF;
        if ((ctl1 & 0x03) == 0x03) {
            return switch (ctl1) {
                case 0x07 -> "U帧 STARTDT_ACT";
                case 0x0B -> "U帧 STARTDT_CON";
                case 0x13 -> "U帧 STOPDT_ACT";
                case 0x23 -> "U帧 STOPDT_CON";
                case 0x43 -> "U帧 TESTFR_ACT";
                case 0x83 -> "U帧 TESTFR_CON";
                default -> "U帧";
            };
        }
        if ((ctl1 & 0x03) == 0x01) {
            int recvSeq = ((apdu[4] & 0xFE) >> 1) | ((apdu[5] & 0xFF) << 7);
            return "S帧 确认 recvSeq=" + recvSeq;
        }
        return "I帧";
    }

    public J60870MasterSession(ConnectionMode connectionMode, String substationHost, int port, int commonAddress,
            long commandTimeoutMs) {
        this.connectionMode = connectionMode;
        this.substationHost = substationHost;
        this.port = port;
        this.commonAddress = commonAddress;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    @Override
    public synchronized void start() throws IOException {
        if (running) {
            return;
        }
        if (connectionMode == ConnectionMode.LISTEN) {
            server = Server.builder()
                    .setPort(port)
                    .setMaxConnections(1)
                    .setSocketFactory(new AddressCapturingServerSocketFactory())
                    .build();
            server.start(new MasterServerListener());
            running = true;
            emitConnection(ConnectionEvent.now(ConnectionEvent.State.LISTENING, null));
        } else {
            running = true;
            long generation = ++connectGeneration;
            emitConnection(ConnectionEvent.now(ConnectionEvent.State.LISTENING, null));
            dialAsync(generation);
        }
    }

    @Override
    public synchronized void reconfigure(ConnectionMode mode, String substationHost, int port, int commonAddress,
            long commandTimeoutMs) throws IOException {
        if (this.connectionMode == mode && this.substationHost.equals(substationHost) && this.port == port
                && this.commonAddress == commonAddress && this.commandTimeoutMs == commandTimeoutMs) {
            return;
        }
        boolean wasRunning = running;
        if (wasRunning) {
            stop();
        }
        this.connectionMode = mode;
        this.substationHost = substationHost;
        this.port = port;
        this.commonAddress = commonAddress;
        this.commandTimeoutMs = commandTimeoutMs;
        if (wasRunning) {
            start();
        }
    }

    @Override
    public synchronized void stop() {
        if (!running) {
            return;
        }
        running = false;
        // 使尚未返回的拨号尝试（含正在阻塞的 TCP connect）在完成后自行放弃结果，见 dialOnce。
        connectGeneration++;
        Connection conn = substation;
        if (conn != null) {
            conn.close();
        }
        substation = null;
        transferStarted = false;
        if (server != null) {
            server.stop();
            server = null;
        }
        failAllPending("会话已停止");
        emitConnection(ConnectionEvent.now(ConnectionEvent.State.STOPPED, null));
    }

    /** 在后台线程发起一次拨号；TCP connect 与 STARTDT 握手均为阻塞调用，不能在调用线程（FX 线程）上执行。 */
    private void dialAsync(long generation) {
        Thread thread = new Thread(() -> dialOnce(generation), "dial-substation");
        thread.setDaemon(true);
        thread.start();
    }

    private void dialOnce(long generation) {
        String host = substationHost;
        int targetPort = port;
        Connection connection;
        try {
            connection = new ClientConnectionBuilder(host)
                    .setPort(targetPort)
                    .setSocketFactory(new TappingSocketFactory())
                    .setConnectionEventListener(new SubstationConnectionListener(generation))
                    .build();
        } catch (IOException e) {
            if (generation == connectGeneration && running) {
                emitFrame(RawFrame.received("连接子站失败: " + e.getMessage()));
                scheduler.schedule(() -> dialOnce(generation), DIAL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        synchronized (this) {
            if (generation != connectGeneration || !running) {
                connection.close();
                return;
            }
            substation = connection;
            remoteAddress = host + ":" + targetPort;
        }
        emitFrame(RawFrame.received("已连接子站 " + remoteAddress + "，等待 STARTDT"));
        try {
            connection.startDataTransfer();
        } catch (IOException e) {
            connection.close();
            if (substation == connection) {
                substation = null;
                remoteAddress = null;
            }
            if (generation == connectGeneration && running) {
                emitFrame(RawFrame.received("STARTDT 握手失败: " + e.getMessage()));
                scheduler.schedule(() -> dialOnce(generation), DIAL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
            }
            return;
        }
        // stop()/reconfigure() 可能赶在 STARTDT 完成前发生；这条连接已不属于当前会话，必须在此关闭，
        // 否则它会带着监听器继续收数据且再无人持有它的引用（泄漏连接）。
        synchronized (this) {
            if (generation != connectGeneration || !running) {
                connection.close();
                if (substation == connection) {
                    substation = null;
                    remoteAddress = null;
                }
            }
        }
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isSubstationConnected() {
        return substation != null && transferStarted;
    }

    @Override
    public void sendGeneralInterrogation() throws IOException {
        Connection conn = requireConnected();
        String raw = captureSend(() -> conn.interrogation(commonAddress, CauseOfTransmission.ACTIVATION,
                new IeQualifierOfInterrogation(20)));
        emitFrame(RawFrame.sent("C_IC_NA_1 总召唤 ACT, CA=" + commonAddress + ", QOI=20", raw));
    }

    @Override
    public void sendClockSync() throws IOException {
        Connection conn = requireConnected();
        Instant now = Instant.now();
        String raw = captureSend(() -> conn.synchronizeClocks(commonAddress, new IeTime56(now.toEpochMilli())));
        emitFrame(RawFrame.sent("C_CS_NA_1 时钟同步 ACT, CA=" + commonAddress + ", time=" + now, raw));
    }

    @Override
    public CompletableFuture<CommandResult> sendSingleCommand(int ioa, boolean on) {
        return sendConfirmedCommand(ioa, conn -> {
            conn.singleCommand(commonAddress, CauseOfTransmission.ACTIVATION, ioa,
                    new IeSingleCommand(on, 0, false));
            return "C_SC_NA_1 单点遥控 ACT, CA=" + commonAddress + ", IOA=" + ioa
                    + ", " + (on ? "合" : "分") + ", 直接执行";
        });
    }

    @Override
    public CompletableFuture<CommandResult> sendSetpointCommand(int ioa, float value) {
        return sendConfirmedCommand(ioa, conn -> {
            // QL=0、S/E=false：直接执行
            conn.setShortFloatCommand(commonAddress, CauseOfTransmission.ACTIVATION, ioa,
                    new IeShortFloat(value), new IeQualifierOfSetPointCommand(0, false));
            return "C_SE_NC_1 遥调 ACT, CA=" + commonAddress + ", IOA=" + ioa
                    + ", 值=" + value + ", 直接执行";
        });
    }

    private interface CommandSend {
        /** 执行 j60870 发送调用，返回报文日志摘要文案。 */
        String send(Connection conn) throws IOException;
    }

    /** 发出需要 ACTCON 确认的命令：按 IOA 挂起等待，由 {@link #resolveCommandConfirmation} 兑现或超时。 */
    private CompletableFuture<CommandResult> sendConfirmedCommand(int ioa, CommandSend sender) {
        CompletableFuture<CommandResult> future = new CompletableFuture<>();
        Connection conn = substation;
        if (conn == null || !transferStarted) {
            future.complete(CommandResult.failed("子站未连接"));
            return future;
        }
        PendingCommand pending = new PendingCommand(future);
        if (pendingCommands.putIfAbsent(ioa, pending) != null) {
            future.complete(CommandResult.failed("IOA=" + ioa + " 已有待确认的命令"));
            return future;
        }
        try {
            String[] summary = new String[1];
            String raw = captureSend(() -> summary[0] = sender.send(conn));
            emitFrame(RawFrame.sent(summary[0], raw));
        } catch (IOException e) {
            pendingCommands.remove(ioa, pending);
            future.complete(CommandResult.failed("发送失败: " + e.getMessage()));
            return future;
        }
        scheduler.schedule(() -> {
            if (pendingCommands.remove(ioa, pending)) {
                future.complete(CommandResult.timeout());
            }
        }, commandTimeoutMs, TimeUnit.MILLISECONDS);
        return future;
    }

    @Override
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    private Connection requireConnected() throws IOException {
        Connection conn = substation;
        if (conn == null || !transferStarted) {
            throw new IOException("子站未连接");
        }
        return conn;
    }

    private void failAllPending(String reason) {
        for (Integer ioa : pendingCommands.keySet()) {
            PendingCommand pending = pendingCommands.remove(ioa);
            if (pending != null) {
                pending.future().complete(CommandResult.failed(reason));
            }
        }
    }

    // ---- 服务端回调 ----

    private final class MasterServerListener implements ServerEventListener {

        @Override
        public ConnectionEventListener connectionIndication(Connection connection) {
            substation = connection;
            emitFrame(RawFrame.received("子站 TCP 连入" + (remoteAddress != null ? " " + remoteAddress : "")
                    + "，等待 STARTDT"));
            return new SubstationConnectionListener(connectGeneration);
        }

        @Override
        public void serverStoppedListeningIndication(IOException e) {
            if (running) {
                emitFrame(RawFrame.received("监听异常终止: " + e.getMessage()));
            }
        }

        @Override
        public void connectionAttemptFailed(IOException e) {
            emitFrame(RawFrame.received("拒绝新的连接（本阶段仅支持单个子站连接）"));
        }
    }

    private final class SubstationConnectionListener implements ConnectionEventListener {

        /** 本监听器所属连接创建时的会话代次。stop/reconfigure 会递增代次，过期连接的事件一律忽略。 */
        private final long generation;

        SubstationConnectionListener(long generation) {
            this.generation = generation;
        }

        private boolean stale() {
            return generation != connectGeneration;
        }

        @Override
        public void newASdu(Connection connection, ASdu aSdu) {
            if (stale()) {
                connection.close();
                return;
            }
            emitFrame(RawFrame.received(summarize(aSdu), takeReceivedRaw()));
            if (aSdu.getCauseOfTransmission() == CauseOfTransmission.ACTIVATION_CON
                    && (aSdu.getTypeIdentification() == ASduType.C_SC_NA_1
                            || aSdu.getTypeIdentification() == ASduType.C_SE_NC_1)) {
                resolveCommandConfirmation(aSdu);
                return;
            }
            if (aSdu.getTypeIdentification().name().startsWith("M_")) {
                for (PointUpdate update : decode(aSdu)) {
                    emitPointUpdate(update);
                }
            }
        }

        @Override
        public void connectionClosed(Connection connection, IOException cause) {
            if (substation == connection) {
                substation = null;
                transferStarted = false;
                receivedRawQueue.clear();
            }
            if (stale()) {
                // 过期连接（stop/reconfigure 之前建立的）关闭时不得触发重拨，
                // 否则会与新会话的拨号循环并行，产生第二条连接。
                return;
            }
            failAllPending("连接断开");
            String addr = remoteAddress;
            remoteAddress = null;
            String reason = cause == null || cause.getMessage() == null ? "（无原因信息）"
                    : ": " + cause.getClass().getSimpleName() + " - " + cause.getMessage();
            emitFrame(RawFrame.received("连接断开" + reason
                    + (running && connectionMode == ConnectionMode.DIAL ? "，3 秒后重连" : "")));
            emitConnection(ConnectionEvent.now(ConnectionEvent.State.DISCONNECTED, addr));
            if (running) {
                emitConnection(ConnectionEvent.now(ConnectionEvent.State.LISTENING, null));
                if (connectionMode == ConnectionMode.DIAL) {
                    scheduler.schedule(() -> dialOnce(generation), DIAL_RETRY_DELAY_MS, TimeUnit.MILLISECONDS);
                }
            }
        }

        @Override
        public void dataTransferStateChanged(Connection connection, boolean stopped) {
            if (stale()) {
                connection.close();
                return;
            }
            transferStarted = !stopped;
            if (!stopped) {
                emitFrame(RawFrame.received(connectionMode == ConnectionMode.LISTEN
                        ? "STARTDT_ACT（已自动回 STARTDT_CON）"
                        : "已发 STARTDT_ACT 并收到 STARTDT_CON，数据传输开始"));
                emitConnection(ConnectionEvent.now(ConnectionEvent.State.CONNECTED, remoteAddress));
            } else {
                emitFrame(RawFrame.received("STOPDT_ACT（已自动回 STOPDT_CON）"));
            }
        }
    }

    private void resolveCommandConfirmation(ASdu aSdu) {
        InformationObject[] objects = aSdu.getInformationObjects();
        if (objects == null || objects.length == 0) {
            return;
        }
        int ioa = objects[0].getInformationObjectAddress();
        PendingCommand pending = pendingCommands.remove(ioa);
        if (pending != null) {
            if (aSdu.isNegativeConfirm()) {
                pending.future().complete(CommandResult.negative("收到否定确认"));
            } else {
                pending.future().complete(CommandResult.confirmed());
            }
        }
    }

    // ---- ASDU 解码 ----

    private static String summarize(ASdu aSdu) {
        StringBuilder sb = new StringBuilder();
        sb.append(aSdu.getTypeIdentification().name())
                .append(' ').append(aSdu.getCauseOfTransmission().name())
                .append(aSdu.isNegativeConfirm() ? " (否定)" : "")
                .append(", CA=").append(aSdu.getCommonAddress());
        InformationObject[] objects = aSdu.getInformationObjects();
        if (objects != null && objects.length > 0) {
            sb.append(", IOA=");
            for (int i = 0; i < objects.length; i++) {
                if (i > 0) {
                    sb.append('/');
                }
                sb.append(objects[i].getInformationObjectAddress());
            }
        }
        return sb.toString();
    }

    private List<PointUpdate> decode(ASdu aSdu) {
        List<PointUpdate> updates = new ArrayList<>();
        InformationObject[] objects = aSdu.getInformationObjects();
        if (objects == null) {
            return updates;
        }
        String type = aSdu.getTypeIdentification().name();
        String cause = aSdu.getCauseOfTransmission().name();
        for (InformationObject io : objects) {
            InformationElement[][] rows = io.getInformationElements();
            for (int i = 0; i < rows.length; i++) {
                int ioa = io.getInformationObjectAddress() + (aSdu.isSequenceOfElements() ? i : 0);
                updates.add(decodeRow(type, cause, ioa, rows[i]));
            }
        }
        return updates;
    }

    private PointUpdate decodeRow(String type, String cause, int ioa, InformationElement[] row) {
        String value = "";
        String quality = "";
        Instant timestamp = Instant.now();
        for (InformationElement element : row) {
            if (element instanceof IeSinglePointWithQuality sp) {
                value = sp.isOn() ? "合(1)" : "分(0)";
                quality = qualityText(sp.isInvalid(), sp.isNotTopical(), sp.isSubstituted(), sp.isBlocked(), false);
            } else if (element instanceof IeDoublePointWithQuality dp) {
                value = switch (dp.getDoublePointInformation()) {
                    case OFF -> "分(1)";
                    case ON -> "合(2)";
                    case INDETERMINATE_OR_INTERMEDIATE -> "不定(0)";
                    case INDETERMINATE -> "不定(3)";
                };
                quality = qualityText(dp.isInvalid(), dp.isNotTopical(), dp.isSubstituted(), dp.isBlocked(), false);
            } else if (element instanceof IeShortFloat sf) {
                value = String.valueOf(sf.getValue());
            } else if (element instanceof IeScaledValue sv) {
                value = String.valueOf(sv.getUnnormalizedValue());
            } else if (element instanceof IeNormalizedValue nv) {
                value = String.valueOf(nv.getNormalizedValue());
            } else if (element instanceof IeQuality q) {
                quality = qualityText(q.isInvalid(), q.isNotTopical(), q.isSubstituted(), q.isBlocked(), q.isOverflow());
            } else if (element instanceof IeTime56 time) {
                timestamp = Instant.ofEpochMilli(time.getTimestamp());
            } else if (value.isEmpty()) {
                value = element.toString();
            }
        }
        if (quality.isEmpty()) {
            quality = "有效";
        }
        return new PointUpdate(ioa, type, value, quality, cause, timestamp);
    }

    private static String qualityText(boolean invalid, boolean notTopical, boolean substituted, boolean blocked,
            boolean overflow) {
        List<String> flags = new ArrayList<>();
        if (invalid) {
            flags.add("IV");
        }
        if (notTopical) {
            flags.add("NT");
        }
        if (substituted) {
            flags.add("SB");
        }
        if (blocked) {
            flags.add("BL");
        }
        if (overflow) {
            flags.add("OV");
        }
        return flags.isEmpty() ? "有效" : String.join(",", flags);
    }

    // ---- 事件分发 ----

    private void emitConnection(ConnectionEvent event) {
        for (SessionListener listener : listeners) {
            try {
                listener.onConnectionEvent(event);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void emitPointUpdate(PointUpdate update) {
        for (SessionListener listener : listeners) {
            try {
                listener.onPointUpdate(update);
            } catch (RuntimeException ignored) {
            }
        }
    }

    private void emitFrame(RawFrame frame) {
        for (SessionListener listener : listeners) {
            try {
                listener.onFrame(frame);
            } catch (RuntimeException ignored) {
            }
        }
    }

    /**
     * j60870 的 Connection 不暴露对端地址，这里在 accept 时捕获。
     * 单连接场景下 accept 与 connectionIndication 一一对应，不存在错配。
     */
    private final class AddressCapturingServerSocketFactory extends ServerSocketFactory {

        @Override
        public ServerSocket createServerSocket() throws IOException {
            return new CapturingServerSocket();
        }

        @Override
        public ServerSocket createServerSocket(int port) throws IOException {
            return new CapturingServerSocket(port);
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog) throws IOException {
            return new CapturingServerSocket(port, backlog);
        }

        @Override
        public ServerSocket createServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
            return new CapturingServerSocket(port, backlog, ifAddress);
        }
    }

    private final class CapturingServerSocket extends ServerSocket {

        CapturingServerSocket() throws IOException {
        }

        CapturingServerSocket(int port) throws IOException {
            super(port);
        }

        CapturingServerSocket(int port, int backlog) throws IOException {
            super(port, backlog);
        }

        CapturingServerSocket(int port, int backlog, InetAddress ifAddress) throws IOException {
            super(port, backlog, ifAddress);
        }

        @Override
        public Socket accept() throws IOException {
            Socket socket = super.accept();
            remoteAddress = socket.getInetAddress().getHostAddress() + ":" + socket.getPort();
            return new TappedSocket(socket, J60870MasterSession.this::onApdu);
        }
    }

    /** DIAL 模式的客户端 socket 工厂：返回带旁路抓帧的 socket。 */
    private final class TappingSocketFactory extends SocketFactory {

        @Override
        public Socket createSocket() {
            return new TappedSocket(new Socket(), J60870MasterSession.this::onApdu);
        }

        @Override
        public Socket createSocket(String host, int port) throws IOException {
            return new TappedSocket(new Socket(host, port), J60870MasterSession.this::onApdu);
        }

        @Override
        public Socket createSocket(String host, int port, InetAddress localHost, int localPort) throws IOException {
            return new TappedSocket(new Socket(host, port, localHost, localPort), J60870MasterSession.this::onApdu);
        }

        @Override
        public Socket createSocket(InetAddress host, int port) throws IOException {
            return new TappedSocket(new Socket(host, port), J60870MasterSession.this::onApdu);
        }

        @Override
        public Socket createSocket(InetAddress address, int port, InetAddress localAddress, int localPort)
                throws IOException {
            return new TappedSocket(new Socket(address, port, localAddress, localPort),
                    J60870MasterSession.this::onApdu);
        }
    }
}
