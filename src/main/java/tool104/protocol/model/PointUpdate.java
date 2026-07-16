package tool104.protocol.model;

import java.time.Instant;

/**
 * 实时数据表中的一条记录：子站上送的数据（遥测/遥信）、命令确认回显（遥控/遥调），
 * 或点表可控点的未下发占位。按原始 IOA 透传展示，不经点表过滤。
 *
 * @param ioa       信息体地址
 * @param type      ASDU 类型标识，如 "M_SP_NA_1"
 * @param value     格式化后的值，如 "合" / "12.5"；占位行为 {@link #PLACEHOLDER_VALUE}
 * @param quality   品质描述，如 "有效" / "IV,NT"
 * @param cause     传送原因，如 "SPONTANEOUS"；占位行为空串
 * @param timestamp 时标（报文带时标则用报文时标，否则为到达时间）
 */
public record PointUpdate(int ioa, String type, String value, String quality, String cause, Instant timestamp) {

    public static final String PLACEHOLDER_VALUE = "未下发";

    /** 点表可控点的常驻占位行：尚未下发过命令，无值无时标。 */
    public static PointUpdate placeholder(int ioa, String type) {
        return new PointUpdate(ioa, type, PLACEHOLDER_VALUE, "", "", Instant.now());
    }

    public boolean isPlaceholder() {
        return PLACEHOLDER_VALUE.equals(value) && cause.isEmpty();
    }
}
