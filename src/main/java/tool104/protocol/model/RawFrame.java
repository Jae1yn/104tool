package tool104.protocol.model;

import java.time.Instant;

/**
 * 一条通信报文记录（ASDU 级别）。summary 为可读的解析摘要。
 */
public record RawFrame(Direction direction, String summary, Instant timestamp) {

    public enum Direction {
        /** 主站替身 → 子站 */
        SENT,
        /** 子站 → 主站替身 */
        RECEIVED
    }

    public static RawFrame sent(String summary) {
        return new RawFrame(Direction.SENT, summary, Instant.now());
    }

    public static RawFrame received(String summary) {
        return new RawFrame(Direction.RECEIVED, summary, Instant.now());
    }
}
