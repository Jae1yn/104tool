package tool104.framelog;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

import tool104.protocol.model.RawFrame;

/**
 * 报文日志：环形缓冲 + 可导出文本文件。线程安全，回调发生在调用 append 的线程上。
 */
public final class FrameLog {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final int capacity;
    private final Deque<RawFrame> frames = new ArrayDeque<>();
    private final List<Consumer<RawFrame>> subscribers = new CopyOnWriteArrayList<>();
    private final List<Runnable> clearSubscribers = new CopyOnWriteArrayList<>();

    public FrameLog(int capacity) {
        this.capacity = capacity;
    }

    public FrameLog() {
        this(5000);
    }

    public void append(RawFrame frame) {
        synchronized (this) {
            frames.addLast(frame);
            while (frames.size() > capacity) {
                frames.removeFirst();
            }
        }
        for (Consumer<RawFrame> subscriber : subscribers) {
            subscriber.accept(frame);
        }
    }

    public synchronized List<RawFrame> snapshot() {
        return new ArrayList<>(frames);
    }

    public void clear() {
        synchronized (this) {
            frames.clear();
        }
        for (Runnable subscriber : clearSubscribers) {
            subscriber.run();
        }
    }

    public void subscribe(Consumer<RawFrame> subscriber) {
        subscribers.add(subscriber);
    }

    /** 清空时回调，供视图同步清掉已展示的内容。回调发生在调用 clear 的线程上。 */
    public void subscribeClear(Runnable subscriber) {
        clearSubscribers.add(subscriber);
    }

    public void exportToFile(Path target) throws IOException {
        List<RawFrame> copy = snapshot();
        List<String> lines = new ArrayList<>(copy.size());
        for (RawFrame frame : copy) {
            lines.add(format(frame));
        }
        if (target.getParent() != null) {
            Files.createDirectories(target.getParent());
        }
        Files.write(target, lines);
    }

    public static String format(RawFrame frame) {
        String arrow = frame.direction() == RawFrame.Direction.SENT ? "→" : "←";
        String line = TIME_FORMAT.format(frame.timestamp()) + " " + arrow + " " + frame.summary();
        return frame.rawHex() == null ? line : line + " | " + frame.rawHex();
    }
}
