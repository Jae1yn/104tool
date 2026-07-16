package tool104.protocol.model;

import java.time.Instant;

/**
 * 一条通信报文记录。summary 为可读的解析摘要；rawHex 为线上原始 APDU 字节
 * （含 68 起始字节，空格分隔大写 hex），无法获得原始字节的条目（如连接事件说明）为 null。
 */
public record RawFrame(Direction direction, String summary, String rawHex, Instant timestamp) {

    public enum Direction {
        /** 主站替身 → 子站 */
        SENT,
        /** 子站 → 主站替身 */
        RECEIVED
    }

    public static RawFrame sent(String summary) {
        return sent(summary, null);
    }

    public static RawFrame sent(String summary, String rawHex) {
        return new RawFrame(Direction.SENT, summary, rawHex, Instant.now());
    }

    public static RawFrame received(String summary) {
        return received(summary, null);
    }

    public static RawFrame received(String summary, String rawHex) {
        return new RawFrame(Direction.RECEIVED, summary, rawHex, Instant.now());
    }
}
