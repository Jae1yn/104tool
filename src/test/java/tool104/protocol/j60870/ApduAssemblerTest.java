package tool104.protocol.j60870;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

class ApduAssemblerTest {

    private final List<byte[]> frames = new ArrayList<>();
    private final TappedSocket.ApduAssembler assembler =
            new TappedSocket.ApduAssembler(false, (sent, apdu) -> frames.add(apdu));

    private static final byte[] STARTDT_ACT = {0x68, 0x04, 0x07, 0x00, 0x00, 0x00};
    private static final byte[] S_FRAME = {0x68, 0x04, 0x01, 0x00, 0x02, 0x00};

    @Test
    void assemblesSingleFrameFedByteByByte() {
        for (byte b : STARTDT_ACT) {
            assembler.accept(b);
        }
        assertEquals(1, frames.size());
        assertArrayEquals(STARTDT_ACT, frames.get(0));
    }

    @Test
    void splitsConcatenatedFramesInOneBulkWrite() {
        byte[] both = new byte[STARTDT_ACT.length + S_FRAME.length];
        System.arraycopy(STARTDT_ACT, 0, both, 0, STARTDT_ACT.length);
        System.arraycopy(S_FRAME, 0, both, STARTDT_ACT.length, S_FRAME.length);

        assembler.accept(both, 0, both.length);

        assertEquals(2, frames.size());
        assertArrayEquals(STARTDT_ACT, frames.get(0));
        assertArrayEquals(S_FRAME, frames.get(1));
    }

    @Test
    void toleratesArbitraryFragmentation() {
        byte[] iFrame = new byte[]{0x68, 0x0E, 0x02, 0x00, 0x02, 0x00,
                0x64, 0x01, 0x06, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x14};
        assembler.accept(iFrame, 0, 3);
        assembler.accept(iFrame, 3, 1);
        assembler.accept(iFrame, 4, 10);
        assembler.accept(iFrame, 14, 2);

        assertEquals(1, frames.size());
        assertArrayEquals(iFrame, frames.get(0));
    }

    @Test
    void dropsUnexpectedInterFrameBytes() {
        assembler.accept((byte) 0x00);
        assembler.accept((byte) 0x55);
        assembler.accept(S_FRAME, 0, S_FRAME.length);

        assertEquals(1, frames.size());
        assertArrayEquals(S_FRAME, frames.get(0));
    }
}
