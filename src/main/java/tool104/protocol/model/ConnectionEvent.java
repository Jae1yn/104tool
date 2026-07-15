package tool104.protocol.model;

import java.time.Instant;

/**
 * 连接状态变化事件。remoteAddress 仅在 CONNECTED / DISCONNECTED 时有值。
 */
public record ConnectionEvent(State state, String remoteAddress, Instant timestamp) {

    public enum State {
        /** 已开始监听，等待子站连入 */
        LISTENING,
        /** 子站已连入并完成 STARTDT */
        CONNECTED,
        /** 子站连接断开（回到等待连入状态） */
        DISCONNECTED,
        /** 已停止监听 */
        STOPPED
    }

    public static ConnectionEvent now(State state, String remoteAddress) {
        return new ConnectionEvent(state, remoteAddress, Instant.now());
    }
}
