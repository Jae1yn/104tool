package tool104.protocol.model;

/**
 * 遥控命令的确认结果（C_SC_NA_1 直接执行）。
 */
public record CommandResult(Status status, String detail) {

    public enum Status {
        /** 收到肯定 ACTCON */
        CONFIRMED,
        /** 收到否定确认 */
        NEGATIVE,
        /** 超时未收到确认 */
        TIMEOUT,
        /** 发送失败（连接断开等） */
        FAILED
    }

    public static CommandResult confirmed() {
        return new CommandResult(Status.CONFIRMED, "");
    }

    public static CommandResult negative(String detail) {
        return new CommandResult(Status.NEGATIVE, detail);
    }

    public static CommandResult timeout() {
        return new CommandResult(Status.TIMEOUT, "超时未收到确认");
    }

    public static CommandResult failed(String detail) {
        return new CommandResult(Status.FAILED, detail);
    }
}
