package tool104.protocol.model;

import java.time.Instant;

/**
 * 子站上送的一条数据（遥测或遥信），按原始 IOA 透传展示，不经点表过滤。
 *
 * @param ioa       信息体地址
 * @param type      ASDU 类型标识，如 "M_SP_NA_1"
 * @param value     格式化后的值，如 "合" / "12.5"
 * @param quality   品质描述，如 "有效" / "IV,NT"
 * @param cause     传送原因，如 "SPONTANEOUS"
 * @param timestamp 时标（报文带时标则用报文时标，否则为到达时间）
 */
public record PointUpdate(int ioa, String type, String value, String quality, String cause, Instant timestamp) {
}
