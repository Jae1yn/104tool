package tool104.protocol.model;

/**
 * 工具与子站之间 TCP 连接的建立方式。
 */
public enum ConnectionMode {
    /** 工具作为服务端监听，等待子站作为客户端连入（见 ADR-0002）。 */
    LISTEN,
    /** 工具作为客户端主动拨号连接子站（子站作为服务端监听）。 */
    DIAL
}
