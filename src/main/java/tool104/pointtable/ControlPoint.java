package tool104.pointtable;

/**
 * 点表中的一个可控点：可下发遥控命令的 IOA 及其命令类型。
 * 本阶段仅支持单点遥控 C_SC_NA_1（直接执行）。
 */
public record ControlPoint(int ioa, String name, CommandType commandType) {

    public enum CommandType {
        /** 单点遥控，直接执行 */
        C_SC_NA_1
    }

    public ControlPoint {
        if (ioa < 1 || ioa > 0xFFFFFF) {
            throw new IllegalArgumentException("IOA 必须在 1..16777215 之间: " + ioa);
        }
        if (name == null) {
            name = "";
        }
        if (commandType == null) {
            commandType = CommandType.C_SC_NA_1;
        }
    }
}
