package tool104.config;

import tool104.protocol.model.ConnectionMode;

/**
 * 工具配置。
 *
 * @param connectionMode           连接建立方式：LISTEN（工具监听，子站拨入，默认）或 DIAL（工具拨号连接子站）
 * @param substationHost           DIAL 模式下子站的主机地址；LISTEN 模式下不使用
 * @param port                     LISTEN 模式下的监听端口，或 DIAL 模式下子站的端口（104 标准端口 2404）
 * @param commonAddress            ASDU 公共地址（子站站址）
 * @param commandTimeoutMs         遥控确认超时（毫秒）
 * @param autoGeneralInterrogation 连接建立后自动下发总召唤
 * @param autoClockSync            连接建立后自动下发时钟同步
 */
public record Settings(ConnectionMode connectionMode, String substationHost, int port, int commonAddress,
        long commandTimeoutMs, boolean autoGeneralInterrogation, boolean autoClockSync) {

    /** 旧版 settings.json 缺失新字段时，Jackson 会传入 null，这里回落到默认值。 */
    public Settings {
        if (connectionMode == null) {
            connectionMode = ConnectionMode.LISTEN;
        }
        if (substationHost == null || substationHost.isBlank()) {
            substationHost = "127.0.0.1";
        }
    }

    public static Settings defaults() {
        return new Settings(ConnectionMode.LISTEN, "127.0.0.1", 2404, 1, 5000, false, false);
    }

    /** 连接相关参数是否一致。不一致时应用新配置会重启连接（与 MasterSession.reconfigure 的判断一致）。 */
    public boolean sameConnection(Settings other) {
        return connectionMode == other.connectionMode
                && substationHost.equals(other.substationHost)
                && port == other.port
                && commonAddress == other.commonAddress
                && commandTimeoutMs == other.commandTimeoutMs;
    }
}
