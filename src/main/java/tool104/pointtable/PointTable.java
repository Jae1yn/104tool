package tool104.pointtable;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 点表：手动维护的可控点列表，JSON 文件持久化，每次变更即保存。
 */
public final class PointTable {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();
    private final List<ControlPoint> points = new ArrayList<>();
    private final List<Runnable> subscribers = new CopyOnWriteArrayList<>();

    public PointTable(Path file) {
        this.file = file;
        load();
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }
        try {
            List<ControlPoint> loaded = mapper.readValue(file.toFile(), new TypeReference<List<ControlPoint>>() {
            });
            points.addAll(loaded);
        } catch (IOException ignored) {
            // 文件损坏时按空点表处理，避免工具无法启动
        }
    }

    public synchronized List<ControlPoint> list() {
        return points.stream().sorted(Comparator.comparingInt(ControlPoint::ioa)).toList();
    }

    public void add(ControlPoint point) {
        synchronized (this) {
            if (points.stream().anyMatch(p -> p.ioa() == point.ioa())) {
                throw new IllegalArgumentException("IOA=" + point.ioa() + " 已存在于点表中");
            }
            points.add(point);
            save();
        }
        notifyChanged();
    }

    public void remove(int ioa) {
        synchronized (this) {
            points.removeIf(p -> p.ioa() == ioa);
            save();
        }
        notifyChanged();
    }

    public void update(ControlPoint point) {
        synchronized (this) {
            points.removeIf(p -> p.ioa() == point.ioa());
            points.add(point);
            save();
        }
        notifyChanged();
    }

    /** 点表内容变更（增/删/改）后回调，发生在变更调用线程上。 */
    public void subscribe(Runnable subscriber) {
        subscribers.add(subscriber);
    }

    private void notifyChanged() {
        for (Runnable subscriber : subscribers) {
            subscriber.run();
        }
    }

    private void save() {
        try {
            if (file.getParent() != null) {
                Files.createDirectories(file.getParent());
            }
            mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), points);
        } catch (IOException e) {
            throw new UncheckedIOException("点表保存失败: " + file, e);
        }
    }
}
