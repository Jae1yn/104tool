package tool104.protocol.j60870;

import java.io.FilterInputStream;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.SocketException;

/**
 * 委托式 Socket 包装：在输入/输出流上旁路观察字节流，按 IEC 104 帧定界
 * （0x68 起始字节 + 1 字节长度 + 该长度的帧体）切出每一帧完整 APDU 并回调。
 * 定界失败（流中出现非 0x68 的帧间字节，正常协议下不会发生）时静默丢弃该字节，
 * 只影响抓帧展示，不影响真实通信。
 */
final class TappedSocket extends Socket {

    /** 完整 APDU 回调。sent=true 表示本端发出。回调发生在做 I/O 的线程上。 */
    interface ApduTap {
        void onApdu(boolean sent, byte[] apdu);
    }

    private final Socket delegate;
    private final ApduTap tap;

    TappedSocket(Socket delegate, ApduTap tap) {
        this.delegate = delegate;
        this.tap = tap;
    }

    @Override
    public InputStream getInputStream() throws IOException {
        return new TapInputStream(delegate.getInputStream(), new ApduAssembler(false, tap));
    }

    @Override
    public OutputStream getOutputStream() throws IOException {
        return new TapOutputStream(delegate.getOutputStream(), new ApduAssembler(true, tap));
    }

    // ---- 其余方法全部委托 ----

    @Override
    public void connect(SocketAddress endpoint) throws IOException {
        delegate.connect(endpoint);
    }

    @Override
    public void connect(SocketAddress endpoint, int timeout) throws IOException {
        delegate.connect(endpoint, timeout);
    }

    @Override
    public void bind(SocketAddress bindpoint) throws IOException {
        delegate.bind(bindpoint);
    }

    @Override
    public InetAddress getInetAddress() {
        return delegate.getInetAddress();
    }

    @Override
    public InetAddress getLocalAddress() {
        return delegate.getLocalAddress();
    }

    @Override
    public int getPort() {
        return delegate.getPort();
    }

    @Override
    public int getLocalPort() {
        return delegate.getLocalPort();
    }

    @Override
    public SocketAddress getRemoteSocketAddress() {
        return delegate.getRemoteSocketAddress();
    }

    @Override
    public SocketAddress getLocalSocketAddress() {
        return delegate.getLocalSocketAddress();
    }

    @Override
    public void setTcpNoDelay(boolean on) throws SocketException {
        delegate.setTcpNoDelay(on);
    }

    @Override
    public boolean getTcpNoDelay() throws SocketException {
        return delegate.getTcpNoDelay();
    }

    @Override
    public void setSoLinger(boolean on, int linger) throws SocketException {
        delegate.setSoLinger(on, linger);
    }

    @Override
    public int getSoLinger() throws SocketException {
        return delegate.getSoLinger();
    }

    @Override
    public void setSoTimeout(int timeout) throws SocketException {
        delegate.setSoTimeout(timeout);
    }

    @Override
    public int getSoTimeout() throws SocketException {
        return delegate.getSoTimeout();
    }

    @Override
    public void setKeepAlive(boolean on) throws SocketException {
        delegate.setKeepAlive(on);
    }

    @Override
    public boolean getKeepAlive() throws SocketException {
        return delegate.getKeepAlive();
    }

    @Override
    public void setSendBufferSize(int size) throws SocketException {
        delegate.setSendBufferSize(size);
    }

    @Override
    public int getSendBufferSize() throws SocketException {
        return delegate.getSendBufferSize();
    }

    @Override
    public void setReceiveBufferSize(int size) throws SocketException {
        delegate.setReceiveBufferSize(size);
    }

    @Override
    public int getReceiveBufferSize() throws SocketException {
        return delegate.getReceiveBufferSize();
    }

    @Override
    public void setTrafficClass(int tc) throws SocketException {
        delegate.setTrafficClass(tc);
    }

    @Override
    public int getTrafficClass() throws SocketException {
        return delegate.getTrafficClass();
    }

    @Override
    public void setReuseAddress(boolean on) throws SocketException {
        delegate.setReuseAddress(on);
    }

    @Override
    public boolean getReuseAddress() throws SocketException {
        return delegate.getReuseAddress();
    }

    @Override
    public void shutdownInput() throws IOException {
        delegate.shutdownInput();
    }

    @Override
    public void shutdownOutput() throws IOException {
        delegate.shutdownOutput();
    }

    @Override
    public boolean isConnected() {
        return delegate.isConnected();
    }

    @Override
    public boolean isBound() {
        return delegate.isBound();
    }

    @Override
    public boolean isClosed() {
        return delegate.isClosed();
    }

    @Override
    public boolean isInputShutdown() {
        return delegate.isInputShutdown();
    }

    @Override
    public boolean isOutputShutdown() {
        return delegate.isOutputShutdown();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    @Override
    public String toString() {
        return delegate.toString();
    }

    /**
     * APDU 定界状态机：0x68 → 长度字节 → 帧体（长度字节的值）。
     * 每凑齐一帧完整 APDU 回调一次。容忍任意粒度的字节分片。
     */
    static final class ApduAssembler {

        private static final int START_BYTE = 0x68;
        /** 104 帧体最大 253 字节，加 68+长度共 255。 */
        private final byte[] buf = new byte[255];
        private final boolean sent;
        private final ApduTap tap;
        private int filled;
        private int bodyLength = -1;

        ApduAssembler(boolean sent, ApduTap tap) {
            this.sent = sent;
            this.tap = tap;
        }

        void accept(byte b) {
            if (filled == 0) {
                if ((b & 0xFF) != START_BYTE) {
                    return; // 帧间意外字节，丢弃（正常协议流不会出现）
                }
                buf[filled++] = b;
                return;
            }
            if (filled == 1) {
                bodyLength = b & 0xFF;
                buf[filled++] = b;
                if (bodyLength == 0) {
                    finishFrame();
                }
                return;
            }
            buf[filled++] = b;
            if (filled == bodyLength + 2) {
                finishFrame();
            }
        }

        void accept(byte[] bytes, int off, int len) {
            for (int i = off; i < off + len; i++) {
                accept(bytes[i]);
            }
        }

        private void finishFrame() {
            byte[] apdu = new byte[filled];
            System.arraycopy(buf, 0, apdu, 0, filled);
            filled = 0;
            bodyLength = -1;
            try {
                tap.onApdu(sent, apdu);
            } catch (RuntimeException ignored) {
                // 抓帧回调异常不得影响真实通信
            }
        }
    }

    private static final class TapInputStream extends FilterInputStream {

        private final ApduAssembler assembler;

        TapInputStream(InputStream in, ApduAssembler assembler) {
            super(in);
            this.assembler = assembler;
        }

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                assembler.accept((byte) b);
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                assembler.accept(b, off, n);
            }
            return n;
        }
    }

    private static final class TapOutputStream extends FilterOutputStream {

        private final ApduAssembler assembler;

        TapOutputStream(OutputStream out, ApduAssembler assembler) {
            super(out);
            this.assembler = assembler;
        }

        @Override
        public void write(int b) throws IOException {
            out.write(b);
            assembler.accept((byte) b);
        }

        @Override
        public void write(byte[] b, int off, int len) throws IOException {
            out.write(b, off, len);
            assembler.accept(b, off, len);
        }
    }
}
