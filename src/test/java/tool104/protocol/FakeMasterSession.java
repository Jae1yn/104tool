package tool104.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;

import tool104.protocol.model.CommandResult;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.ConnectionMode;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;

/**
 * 测试用内存实现：可编程地伪造子站连入/上送/确认，并记录下发的命令。
 */
public final class FakeMasterSession implements MasterSession {

    public record SentCommand(int ioa, boolean on) {
    }

    private final List<SessionListener> listeners = new CopyOnWriteArrayList<>();
    private final List<SentCommand> sentCommands = new ArrayList<>();
    private boolean running;
    private boolean connected;
    private int interrogationCount;
    private int clockSyncCount;
    private CommandResult nextCommandResult = CommandResult.confirmed();
    private int port;
    private int commonAddress;
    private long commandTimeoutMs;
    private ConnectionMode connectionMode = ConnectionMode.LISTEN;
    private String substationHost = "127.0.0.1";

    @Override
    public void start() {
        running = true;
        emit(ConnectionEvent.now(ConnectionEvent.State.LISTENING, null));
    }

    @Override
    public void reconfigure(ConnectionMode mode, String substationHost, int port, int commonAddress,
            long commandTimeoutMs) {
        this.connectionMode = mode;
        this.substationHost = substationHost;
        this.port = port;
        this.commonAddress = commonAddress;
        this.commandTimeoutMs = commandTimeoutMs;
    }

    @Override
    public void stop() {
        running = false;
        connected = false;
        emit(ConnectionEvent.now(ConnectionEvent.State.STOPPED, null));
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    @Override
    public boolean isSubstationConnected() {
        return connected;
    }

    @Override
    public void sendGeneralInterrogation() {
        interrogationCount++;
        emitFrame(RawFrame.sent("C_IC_NA_1 总召唤 ACT"));
    }

    @Override
    public void sendClockSync() {
        clockSyncCount++;
        emitFrame(RawFrame.sent("C_CS_NA_1 时钟同步 ACT"));
    }

    @Override
    public CompletableFuture<CommandResult> sendSingleCommand(int ioa, boolean on) {
        sentCommands.add(new SentCommand(ioa, on));
        return CompletableFuture.completedFuture(nextCommandResult);
    }

    @Override
    public void addListener(SessionListener listener) {
        listeners.add(listener);
    }

    @Override
    public void removeListener(SessionListener listener) {
        listeners.remove(listener);
    }

    // ---- 测试脚本入口 ----

    public void simulateSubstationConnected(String remoteAddress) {
        connected = true;
        emit(ConnectionEvent.now(ConnectionEvent.State.CONNECTED, remoteAddress));
    }

    public void simulateSubstationDisconnected() {
        connected = false;
        emit(ConnectionEvent.now(ConnectionEvent.State.DISCONNECTED, null));
    }

    public void simulatePointUpdate(PointUpdate update) {
        for (SessionListener listener : listeners) {
            listener.onPointUpdate(update);
        }
    }

    public void setNextCommandResult(CommandResult result) {
        this.nextCommandResult = result;
    }

    public List<SentCommand> sentCommands() {
        return sentCommands;
    }

    public int port() {
        return port;
    }

    public int commonAddress() {
        return commonAddress;
    }

    public long commandTimeoutMs() {
        return commandTimeoutMs;
    }

    public ConnectionMode connectionMode() {
        return connectionMode;
    }

    public String substationHost() {
        return substationHost;
    }

    public int interrogationCount() {
        return interrogationCount;
    }

    public int clockSyncCount() {
        return clockSyncCount;
    }

    private void emit(ConnectionEvent event) {
        for (SessionListener listener : listeners) {
            listener.onConnectionEvent(event);
        }
    }

    private void emitFrame(RawFrame frame) {
        for (SessionListener listener : listeners) {
            listener.onFrame(frame);
        }
    }
}
