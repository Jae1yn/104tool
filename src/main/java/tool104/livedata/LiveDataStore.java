package tool104.livedata;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

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
}
