package tool104.protocol;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;

import tool104.protocol.model.CommandResult;
import tool104.protocol.model.ConnectionMode;

/**
 * 主站替身会话：与子站之间建立 TCP 连接（默认作为服务端监听子站拨入，见 ADR-0002；
 * 也可切换为客户端主动拨号连接子站，见 ADR-0003），
 * 之后可下发总召唤、时钟同步、单点遥控，并通过 {@link SessionListener} 上报事件。
 *
 * 本阶段只接受/维持单个子站连接。
 */
public interface MasterSession extends AutoCloseable {

    /** 按当前配置的连接方式开始建立连接（LISTEN 监听 / DIAL 拨号）。 */
    void start() throws IOException;

    /**
     * 重新配置连接方式、目标地址、端口、ASDU 公共地址与遥控确认超时。
     * 运行中时以新配置重启连接（已有子站连接会断开）；已停止则仅保存，待下次 {@link #start()} 生效。
     */
    void reconfigure(ConnectionMode mode, String substationHost, int port, int commonAddress, long commandTimeoutMs)
            throws IOException;

    /** 停止连接（监听或拨号）并断开已有连接。 */
    void stop();

    boolean isRunning();

    /** 是否有子站已连入且完成 STARTDT。 */
    boolean isSubstationConnected();

    /** 下发总召唤（C_IC_NA_1，QOI=20）。 */
    void sendGeneralInterrogation() throws IOException;

    /** 下发时钟同步（C_CS_NA_1，当前系统时间）。 */
    void sendClockSync() throws IOException;

    /**
     * 下发单点遥控（C_SC_NA_1，直接执行）。
     * 异步返回确认结果：肯定/否定/超时/发送失败，见 {@link CommandResult}。
     */
    CompletableFuture<CommandResult> sendSingleCommand(int ioa, boolean on);

    void addListener(SessionListener listener);

    void removeListener(SessionListener listener);

    @Override
    default void close() {
        stop();
    }
}
