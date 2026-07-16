package tool104.protocol;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ApduExplainerTest {

    private static void assertContains(String text, String... expected) {
        for (String s : expected) {
            assertTrue(text.contains(s), "解析结果缺少 [" + s + "]:\n" + text);
        }
    }

    @Test
    void explainsStartdtAct() {
        String text = ApduExplainer.explain(new byte[]{0x68, 0x04, 0x07, 0x00, 0x00, 0x00});
        assertContains(text, "U帧", "STARTDT_ACT");
    }

    @Test
    void explainsSFrameReceiveSeq() {
        // recvSeq=1 → ctl3=0x02
        String text = ApduExplainer.explain(new byte[]{0x68, 0x04, 0x01, 0x00, 0x02, 0x00});
        assertContains(text, "S帧", "接收序号=1");
    }

    @Test
    void explainsGeneralInterrogation() {
        // I帧 send=0 recv=0；C_IC_NA_1(100=0x64) SQ=0 数量1 COT=6 激活 orig=0 CA=1 IOA=0 QOI=20
        byte[] apdu = {0x68, 0x0E, 0x00, 0x00, 0x00, 0x00,
                0x64, 0x01, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x14};
        String text = ApduExplainer.explain(apdu);
        assertContains(text, "I帧", "发送序号=0", "类型 100", "C_IC_NA_1",
                "SQ=0", "对象数=1", "传送原因 6", "激活", "公共地址 1", "IOA 0", "QOI=20", "总召唤");
    }

    @Test
    void explainsShortFloatMeasurement() {
        // M_ME_NC_1(13) COT=3 CA=1 IOA=4001(0x0FA1) 值 12.5f(LE: 00 00 48 41) QDS=0
        byte[] apdu = {0x68, 0x12, 0x02, 0x00, 0x02, 0x00,
                0x0D, 0x01, 0x03, 0x00, 0x01, 0x00,
                (byte) 0xA1, 0x0F, 0x00,
                0x00, 0x00, 0x48, 0x41, 0x00};
        String text = ApduExplainer.explain(apdu);
        assertContains(text, "M_ME_NC_1", "突发", "IOA 4001", "短浮点值 = 12.5", "品质 QDS: 有效");
    }

    @Test
    void explainsSequenceOfSinglePoints() {
        // M_SP_NA_1(1) SQ=1 数量3 COT=20 CA=1 首IOA=1001(0x03E9)，SPI 1/0/1
        byte[] apdu = {0x68, 0x0F, 0x02, 0x00, 0x02, 0x00,
                0x01, (byte) 0x83, 0x14, 0x00, 0x01, 0x00,
                (byte) 0xE9, 0x03, 0x00,
                0x01, 0x00, 0x01};
        String text = ApduExplainer.explain(apdu);
        assertContains(text, "SQ=1", "对象数=3", "首 IOA 1001",
                "IOA 1001: 单点 合(1)", "IOA 1002: 单点 分(0)", "IOA 1003: 单点 合(1)");
    }

    @Test
    void explainsSetpointCommand() {
        // C_SE_NC_1(50=0x32) COT=6 CA=1 IOA=7001(0x1B59) 值 36.6f(LE: 66 66 12 42) QOS=0 执行
        byte[] apdu = {0x68, 0x12, 0x02, 0x00, 0x02, 0x00,
                0x32, 0x01, 0x06, 0x00, 0x01, 0x00,
                0x59, 0x1B, 0x00,
                0x66, 0x66, 0x12, 0x42, 0x00};
        String text = ApduExplainer.explain(apdu);
        assertContains(text, "C_SE_NC_1", "IOA 7001", "设定值 = 36.6", "QL=0", "执行");
    }

    @Test
    void explainsClockSyncCp56Time() {
        // C_CS_NA_1(103=0x67) COT=6 CA=1 IOA=0 CP56: 2026-07-16 10:20:30.500
        // ms = 30*1000+500 = 30500 = 0x7724；min=20；hour=10；day=16；month=7；year=26
        byte[] apdu = {0x68, 0x14, 0x02, 0x00, 0x02, 0x00,
                0x67, 0x01, 0x06, 0x00, 0x01, 0x00,
                0x00, 0x00, 0x00,
                0x24, 0x77, 0x14, 0x0A, 0x10, 0x07, 0x1A};
        String text = ApduExplainer.explain(apdu);
        assertContains(text, "C_CS_NA_1", "时标 CP56: 2026-07-16 10:20:30.500");
    }

    @Test
    void unknownTypeKeepsHeaderAndDumpsBody() {
        byte[] apdu = {0x68, 0x0C, 0x02, 0x00, 0x02, 0x00,
                (byte) 0x88, 0x01, 0x03, 0x00, 0x01, 0x00, 0x11, 0x22};
        String text = ApduExplainer.explain(apdu);
        assertContains(text, "类型 136", "未知/私有", "公共地址 1", "未按类型解析");
    }

    @Test
    void truncatedFrameDoesNotThrow() {
        String text = ApduExplainer.explain(new byte[]{0x68, 0x0E, 0x00, 0x00});
        assertContains(text, "APCI 不足");
        String junk = ApduExplainer.explain("zz not hex");
        assertContains(junk, "无法解析");
    }
}
