package tool104.framelog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tool104.protocol.model.RawFrame;

class FrameLogTest {

    @TempDir
    Path dir;

    @Test
    void cappedAtCapacity() {
        FrameLog log = new FrameLog(2);
        log.append(RawFrame.sent("frame1"));
        log.append(RawFrame.received("frame2"));
        log.append(RawFrame.sent("frame3"));

        List<RawFrame> frames = log.snapshot();
        assertEquals(2, frames.size());
        assertEquals("frame2", frames.get(0).summary());
        assertEquals("frame3", frames.get(1).summary());
    }

    @Test
    void exportsReadableLines() throws IOException {
        FrameLog log = new FrameLog();
        log.append(RawFrame.sent("C_IC_NA_1 总召唤 ACT"));
        log.append(RawFrame.received("C_IC_NA_1 ACTIVATION_CON"));

        Path target = dir.resolve("out/frames.log");
        log.exportToFile(target);

        List<String> lines = Files.readAllLines(target);
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains("→ C_IC_NA_1 总召唤 ACT"));
        assertTrue(lines.get(1).contains("← C_IC_NA_1 ACTIVATION_CON"));
    }
}
