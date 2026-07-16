package tool104.protocol;

import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/**
 * IEC 60870-5-104 APDU 逐字段剖析：输入完整原始帧（含 0x68 起始字节），
 * 输出"字节分组 + 含义"的多行文本，供报文日志详情区展示。
 *
 * 独立于 j60870（其解码器为包私有）。覆盖本工程实际收发的类型；
 * 未知类型/非标帧优雅降级：已解出的头部保留，剩余字节标注"未按类型解析"，绝不抛异常。
 */
public final class ApduExplainer {

    private static final HexFormat HEX = HexFormat.ofDelimiter(" ").withUpperCase();
    /** hex 分组列的最小宽度，保证含义列对齐。 */
    private static final int HEX_COLUMN_WIDTH = 21;

    private static final Map<Integer, String> TYPE_NAMES = Map.ofEntries(
            Map.entry(1, "M_SP_NA_1 单点遥信"),
            Map.entry(3, "M_DP_NA_1 双点遥信"),
            Map.entry(9, "M_ME_NA_1 归一化遥测"),
            Map.entry(11, "M_ME_NB_1 标度化遥测"),
            Map.entry(13, "M_ME_NC_1 短浮点遥测"),
            Map.entry(15, "M_IT_NA_1 累计量"),
            Map.entry(30, "M_SP_TB_1 带时标单点遥信"),
            Map.entry(31, "M_DP_TB_1 带时标双点遥信"),
            Map.entry(34, "M_ME_TD_1 带时标归一化遥测"),
            Map.entry(35, "M_ME_TE_1 带时标标度化遥测"),
            Map.entry(36, "M_ME_TF_1 带时标短浮点遥测"),
            Map.entry(45, "C_SC_NA_1 单点遥控"),
            Map.entry(46, "C_DC_NA_1 双点遥控"),
            Map.entry(47, "C_RC_NA_1 升降命令"),
            Map.entry(48, "C_SE_NA_1 归一化设定"),
            Map.entry(49, "C_SE_NB_1 标度化设定"),
            Map.entry(50, "C_SE_NC_1 短浮点遥调"),
            Map.entry(70, "M_EI_NA_1 初始化结束"),
            Map.entry(100, "C_IC_NA_1 总召唤"),
            Map.entry(101, "C_CI_NA_1 计数量召唤"),
            Map.entry(103, "C_CS_NA_1 时钟同步"),
            Map.entry(104, "C_TS_NA_1 测试命令"),
            Map.entry(105, "C_RP_NA_1 复位进程"));

    private static final Map<Integer, String> COT_NAMES = Map.ofEntries(
            Map.entry(1, "周期(PERIODIC)"),
            Map.entry(2, "背景扫描(BACKGROUND)"),
            Map.entry(3, "突发(SPONTANEOUS)"),
            Map.entry(4, "初始化(INITIALIZED)"),
            Map.entry(5, "请求(REQUEST)"),
            Map.entry(6, "激活(ACT)"),
            Map.entry(7, "激活确认(ACTCON)"),
            Map.entry(8, "停止激活(DEACT)"),
            Map.entry(9, "停止激活确认(DEACTCON)"),
            Map.entry(10, "激活终止(ACTTERM)"),
            Map.entry(20, "响应总召唤(INROGEN)"),
            Map.entry(37, "响应计数量召唤"),
            Map.entry(44, "未知类型标识"),
            Map.entry(45, "未知传送原因"),
            Map.entry(46, "未知公共地址"),
            Map.entry(47, "未知信息对象地址"));

    /** SQ=1 序列最多展开的对象数，超出折叠。 */
    private static final int MAX_OBJECTS_SHOWN = 8;

    private ApduExplainer() {
    }

    /** 供 UI 直接用日志里的 hex 字符串调用。非法 hex 返回提示行。 */
    public static String explain(String rawHex) {
        byte[] apdu;
        try {
            apdu = HEX.parseHex(rawHex.trim());
        } catch (IllegalArgumentException e) {
            return "(原始帧 hex 无法解析)";
        }
        return explain(apdu);
    }

    public static String explain(byte[] apdu) {
        try {
            return doExplain(apdu);
        } catch (RuntimeException e) {
            // 兜底：任何意外都不抛给调用方
            return "(解析失败: " + e.getMessage() + ")\n" + HEX.formatHex(apdu);
        }
    }

    private static String doExplain(byte[] apdu) {
        List<String> lines = new ArrayList<>();
        if (apdu.length < 2 || (apdu[0] & 0xFF) != 0x68) {
            return "(不是 104 帧：缺少 0x68 起始字节)";
        }
        lines.add(line(apdu, 0, 2, "起始 0x68，APDU 长度 " + (apdu[1] & 0xFF)));
        if (apdu.length < 6) {
            lines.add("(帧不完整：APCI 不足 6 字节)");
            return String.join("\n", lines);
        }

        int ctl1 = apdu[2] & 0xFF;
        if ((ctl1 & 0x03) == 0x03) {
            lines.add(line(apdu, 2, 4, "U帧  " + uFunction(ctl1)));
            appendRemainder(lines, apdu, 6);
            return String.join("\n", lines);
        }
        if ((ctl1 & 0x03) == 0x01) {
            int recvSeq = seq(apdu[4], apdu[5]);
            lines.add(line(apdu, 2, 4, "S帧  接收序号=" + recvSeq));
            appendRemainder(lines, apdu, 6);
            return String.join("\n", lines);
        }
        int sendSeq = seq(apdu[2], apdu[3]);
        int recvSeq = seq(apdu[4], apdu[5]);
        lines.add(line(apdu, 2, 4, "I帧  发送序号=" + sendSeq + "  接收序号=" + recvSeq));

        explainAsdu(lines, apdu);
        return String.join("\n", lines);
    }

    // ---- ASDU ----

    private static void explainAsdu(List<String> lines, byte[] apdu) {
        Cursor cur = new Cursor(apdu, 6);
        if (!cur.has(6)) {
            lines.add("(ASDU 头不完整)");
            appendRemainder(lines, apdu, cur.pos);
            return;
        }
        int typeId = cur.u8();
        String typeName = TYPE_NAMES.get(typeId);
        lines.add(line(apdu, cur.pos - 1, 1,
                "类型 " + typeId + (typeName != null ? " " + typeName : " (未知/私有)")));

        int vsq = cur.u8();
        boolean sq = (vsq & 0x80) != 0;
        int count = vsq & 0x7F;
        lines.add(line(apdu, cur.pos - 1, 1, "SQ=" + (sq ? 1 : 0) + "  对象数=" + count));

        int cot = cur.u8();
        int orig = cur.u8();
        int cause = cot & 0x3F;
        String cotText = "传送原因 " + cause + " " + COT_NAMES.getOrDefault(cause, "");
        if ((cot & 0x40) != 0) {
            cotText += "  P/N=否定";
        }
        if ((cot & 0x80) != 0) {
            cotText += "  T=试验";
        }
        lines.add(line(apdu, cur.pos - 2, 2, cotText.trim() + "  源地址 " + orig));

        int ca = cur.u16();
        lines.add(line(apdu, cur.pos - 2, 2, "公共地址 " + ca));

        if (typeName == null) {
            appendRemainder(lines, apdu, cur.pos);
            return;
        }
        try {
            explainObjects(lines, cur, typeId, sq, count);
        } catch (RuntimeException e) {
            lines.add("(信息对象解析中断: " + e.getMessage() + ")");
        }
        appendRemainder(lines, apdu, cur.pos);
    }

    private static void explainObjects(List<String> lines, Cursor cur, int typeId, boolean sq, int count) {
        if (sq) {
            if (!cur.has(3)) {
                return;
            }
            int ioa = cur.u24();
            lines.add(line(cur.apdu, cur.pos - 3, 3, "首 IOA " + ioa + "（序列，IOA 连续递增）"));
            for (int i = 0; i < count; i++) {
                if (i >= MAX_OBJECTS_SHOWN) {
                    lines.add(pad("…") + "（其余 " + (count - MAX_OBJECTS_SHOWN) + " 个对象省略）");
                    cur.skipToEnd();
                    return;
                }
                explainElement(lines, cur, typeId, ioa + i);
            }
        } else {
            for (int i = 0; i < count; i++) {
                if (i >= MAX_OBJECTS_SHOWN) {
                    lines.add(pad("…") + "（其余 " + (count - MAX_OBJECTS_SHOWN) + " 个对象省略）");
                    cur.skipToEnd();
                    return;
                }
                if (!cur.has(3)) {
                    return;
                }
                int ioa = cur.u24();
                lines.add(line(cur.apdu, cur.pos - 3, 3, "IOA " + ioa));
                explainElement(lines, cur, typeId, -1);
            }
        }
    }

    /** 解一个信息元素组。ioaForSeq >= 0 时（SQ=1 序列）在行内标注对应 IOA。 */
    private static void explainElement(List<String> lines, Cursor cur, int typeId, int ioaForSeq) {
        String prefix = ioaForSeq >= 0 ? "IOA " + ioaForSeq + ": " : "";
        switch (typeId) {
            case 1 -> { // SIQ
                int siq = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1,
                        prefix + "单点 " + ((siq & 0x01) != 0 ? "合(1)" : "分(0)") + qualityFlags(siq)));
            }
            case 3 -> { // DIQ
                int diq = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1,
                        prefix + "双点 " + doublePoint(diq & 0x03) + qualityFlags(diq)));
            }
            case 9 -> { // NVA + QDS
                int nva = cur.s16();
                int qds = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 3, 2, prefix + "归一化值 " + nva));
                lines.add(line(cur.apdu, cur.pos - 1, 1, "品质 QDS:" + qds(qds)));
            }
            case 11 -> { // SVA + QDS
                int sva = cur.s16();
                int qds = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 3, 2, prefix + "标度化值 " + sva));
                lines.add(line(cur.apdu, cur.pos - 1, 1, "品质 QDS:" + qds(qds)));
            }
            case 13 -> { // IEEE754 + QDS
                float v = cur.f32();
                int qds = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 5, 4, prefix + "短浮点值 = " + v));
                lines.add(line(cur.apdu, cur.pos - 1, 1, "品质 QDS:" + qds(qds)));
            }
            case 30 -> { // SIQ + CP56
                int siq = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1,
                        prefix + "单点 " + ((siq & 0x01) != 0 ? "合(1)" : "分(0)") + qualityFlags(siq)));
                explainCp56(lines, cur);
            }
            case 31 -> { // DIQ + CP56
                int diq = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1,
                        prefix + "双点 " + doublePoint(diq & 0x03) + qualityFlags(diq)));
                explainCp56(lines, cur);
            }
            case 34 -> { // NVA + QDS + CP56
                int nva = cur.s16();
                int qds = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 3, 2, prefix + "归一化值 " + nva));
                lines.add(line(cur.apdu, cur.pos - 1, 1, "品质 QDS:" + qds(qds)));
                explainCp56(lines, cur);
            }
            case 35 -> { // SVA + QDS + CP56
                int sva = cur.s16();
                int qds = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 3, 2, prefix + "标度化值 " + sva));
                lines.add(line(cur.apdu, cur.pos - 1, 1, "品质 QDS:" + qds(qds)));
                explainCp56(lines, cur);
            }
            case 36 -> { // float + QDS + CP56
                float v = cur.f32();
                int qds = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 5, 4, prefix + "短浮点值 = " + v));
                lines.add(line(cur.apdu, cur.pos - 1, 1, "品质 QDS:" + qds(qds)));
                explainCp56(lines, cur);
            }
            case 45 -> { // SCO
                int sco = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1, prefix + "单命令 " + ((sco & 0x01) != 0 ? "合(1)" : "分(0)")
                        + "  QU=" + ((sco >> 2) & 0x1F) + "  " + ((sco & 0x80) != 0 ? "选择" : "执行")));
            }
            case 50 -> { // float + QOS
                float v = cur.f32();
                int qos = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 5, 4, prefix + "设定值 = " + v));
                lines.add(line(cur.apdu, cur.pos - 1, 1,
                        "QOS: QL=" + (qos & 0x7F) + "  " + ((qos & 0x80) != 0 ? "选择" : "执行")));
            }
            case 70 -> { // COI
                int coi = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1, prefix + "初始化原因 COI=" + (coi & 0x7F)));
            }
            case 100 -> { // QOI
                int qoi = cur.u8();
                lines.add(line(cur.apdu, cur.pos - 1, 1,
                        prefix + "召唤限定词 QOI=" + qoi + (qoi == 20 ? "（总召唤）" : "")));
            }
            case 103 -> explainCp56(lines, cur);
            default -> {
                // 头部已识别类型但元素结构未内置：交给 appendRemainder 兜底
                cur.skipToEnd();
            }
        }
    }

    private static void explainCp56(List<String> lines, Cursor cur) {
        if (!cur.has(7)) {
            return;
        }
        int start = cur.pos;
        int ms = cur.u16();
        int minByte = cur.u8();
        int hour = cur.u8() & 0x1F;
        int day = cur.u8() & 0x1F;
        int month = cur.u8() & 0x0F;
        int year = 2000 + (cur.u8() & 0x7F);
        String text = String.format("时标 CP56: %d-%02d-%02d %02d:%02d:%02d.%03d%s",
                year, month, day, hour, minByte & 0x3F, ms / 1000, ms % 1000,
                (minByte & 0x80) != 0 ? " (IV无效)" : "");
        lines.add(line(cur.apdu, start, 7, text));
    }

    // ---- 帮助 ----

    private static int seq(byte lo, byte hi) {
        return ((lo & 0xFE) >> 1) | ((hi & 0xFF) << 7);
    }

    private static String uFunction(int ctl1) {
        return switch (ctl1) {
            case 0x07 -> "STARTDT_ACT（请求启动数据传输）";
            case 0x0B -> "STARTDT_CON（确认启动数据传输）";
            case 0x13 -> "STOPDT_ACT（请求停止数据传输）";
            case 0x23 -> "STOPDT_CON（确认停止数据传输）";
            case 0x43 -> "TESTFR_ACT（链路测试）";
            case 0x83 -> "TESTFR_CON（链路测试确认）";
            default -> "未知功能 0x" + Integer.toHexString(ctl1).toUpperCase();
        };
    }

    private static String doublePoint(int dpi) {
        return switch (dpi) {
            case 1 -> "分(1)";
            case 2 -> "合(2)";
            default -> "不定(" + dpi + ")";
        };
    }

    /** SIQ/DIQ 高 4 位品质标志。 */
    private static String qualityFlags(int b) {
        StringBuilder sb = new StringBuilder();
        if ((b & 0x10) != 0) {
            sb.append(" BL");
        }
        if ((b & 0x20) != 0) {
            sb.append(" SB");
        }
        if ((b & 0x40) != 0) {
            sb.append(" NT");
        }
        if ((b & 0x80) != 0) {
            sb.append(" IV");
        }
        return sb.isEmpty() ? "" : "  品质:" + sb;
    }

    private static String qds(int b) {
        StringBuilder sb = new StringBuilder();
        if ((b & 0x01) != 0) {
            sb.append(" OV");
        }
        if ((b & 0x10) != 0) {
            sb.append(" BL");
        }
        if ((b & 0x20) != 0) {
            sb.append(" SB");
        }
        if ((b & 0x40) != 0) {
            sb.append(" NT");
        }
        if ((b & 0x80) != 0) {
            sb.append(" IV");
        }
        return sb.isEmpty() ? " 有效" : sb.toString();
    }

    private static void appendRemainder(List<String> lines, byte[] apdu, int from) {
        if (from >= apdu.length) {
            return;
        }
        lines.add(line(apdu, from, apdu.length - from, "(未按类型解析)"));
    }

    private static String line(byte[] apdu, int off, int len, String meaning) {
        int end = Math.min(off + len, apdu.length);
        return pad(HEX.formatHex(apdu, off, end)) + meaning;
    }

    private static String pad(String hex) {
        if (hex.length() >= HEX_COLUMN_WIDTH) {
            return hex + "  ";
        }
        return hex + " ".repeat(HEX_COLUMN_WIDTH - hex.length()) + "  ";
    }

    /** 顺序读取游标；越界抛 RuntimeException，由 explain 兜底。 */
    private static final class Cursor {
        final byte[] apdu;
        int pos;

        Cursor(byte[] apdu, int pos) {
            this.apdu = apdu;
            this.pos = pos;
        }

        boolean has(int n) {
            return pos + n <= apdu.length;
        }

        int u8() {
            return apdu[pos++] & 0xFF;
        }

        int u16() {
            return u8() | (u8() << 8);
        }

        int s16() {
            return (short) u16();
        }

        int u24() {
            return u8() | (u8() << 8) | (u8() << 16);
        }

        float f32() {
            int bits = u8() | (u8() << 8) | (u8() << 16) | (u8() << 24);
            return Float.intBitsToFloat(bits);
        }

        void skipToEnd() {
            pos = apdu.length;
        }
    }
}
