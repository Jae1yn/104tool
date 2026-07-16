package tool104.livedata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.IntConsumer;

import tool104.pointtable.ControlPoint;
import tool104.protocol.model.PointUpdate;

/**
 * 实时数据：按 IOA 聚合最新值 + 最近事件环形缓冲。线程安全，回调发生在调用 apply 的线程上。
 */
public final class LiveDataStore {

    private final int eventCapacity;
    private final Map<Integer, PointUpdate> latestByIoa = new TreeMap<>();
    private final Deque<PointUpdate> recentEvents = new ArrayDeque<>();
    private final List<Consumer<PointUpdate>> subscribers = new CopyOnWriteArrayList<>();
    private final List<Runnable> clearSubscribers = new CopyOnWriteArrayList<>();
    private final List<IntConsumer> removeSubscribers = new CopyOnWriteArrayList<>();

    public LiveDataStore(int eventCapacity) {
        this.eventCapacity = eventCapacity;
    }

    public LiveDataStore() {
        this(1000);
    }

    public void apply(PointUpdate update) {
        synchronized (this) {
            latestByIoa.put(update.ioa(), update);
            recentEvents.addLast(update);
            while (recentEvents.size() > eventCapacity) {
                recentEvents.removeFirst();
            }
        }
        for (Consumer<PointUpdate> subscriber : subscribers) {
            subscriber.accept(update);
        }
    }

    /** 每个 IOA 的最新值，按 IOA 升序。 */
    public synchronized List<PointUpdate> snapshot() {
        return latestByIoa.values().stream()
                .sorted(Comparator.comparingInt(PointUpdate::ioa))
                .toList();
    }

    /** 最近上送事件，按到达顺序。 */
    public synchronized List<PointUpdate> recentEvents() {
        return new ArrayList<>(recentEvents);
    }

    public void clear() {
        synchronized (this) {
            latestByIoa.clear();
            recentEvents.clear();
        }
        for (Runnable subscriber : clearSubscribers) {
            subscriber.run();
        }
    }

    public void subscribe(Consumer<PointUpdate> subscriber) {
        subscribers.add(subscriber);
    }

    /** 清空时回调，供视图同步清掉已展示的内容。回调发生在调用 clear 的线程上。 */
    public void subscribeClear(Runnable subscriber) {
        clearSubscribers.add(subscriber);
    }

    /** 单个 IOA 被移除（点表删点）时回调。回调发生在调用 syncControlPoints 的线程上。 */
    public void subscribeRemove(IntConsumer subscriber) {
        removeSubscribers.add(subscriber);
    }

    /**
     * 与点表中的可控点同步：缺失的点补「未下发」占位行（不进最近事件缓冲），
     * 类型为 C_* 但已不在点表中的行移除；已有真实值或占位的行不动。
     * 遥测/遥信（M_*）行不受影响。
     */
    public void syncControlPoints(List<ControlPoint> controlPoints) {
        List<PointUpdate> seeded = new ArrayList<>();
        List<Integer> removed = new ArrayList<>();
        synchronized (this) {
            Set<Integer> controlIoas = new HashSet<>();
            for (ControlPoint point : controlPoints) {
                controlIoas.add(point.ioa());
                if (!latestByIoa.containsKey(point.ioa())) {
                    PointUpdate placeholder = PointUpdate.placeholder(point.ioa(), point.commandType().name());
                    latestByIoa.put(point.ioa(), placeholder);
                    seeded.add(placeholder);
                }
            }
            latestByIoa.values().removeIf(update -> {
                boolean stale = update.type().startsWith("C_") && !controlIoas.contains(update.ioa());
                if (stale) {
                    removed.add(update.ioa());
                }
                return stale;
            });
        }
        for (PointUpdate placeholder : seeded) {
            for (Consumer<PointUpdate> subscriber : subscribers) {
                subscriber.accept(placeholder);
            }
        }
        for (Integer ioa : removed) {
            for (IntConsumer subscriber : removeSubscribers) {
                subscriber.accept(ioa);
            }
        }
    }
}
