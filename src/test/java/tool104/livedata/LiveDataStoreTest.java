package tool104.livedata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import tool104.pointtable.ControlPoint;
import tool104.protocol.model.PointUpdate;

class LiveDataStoreTest {

    private static PointUpdate update(int ioa, String value) {
        return new PointUpdate(ioa, "M_SP_NA_1", value, "有效", "SPONTANEOUS", Instant.now());
    }

    @Test
    void keepsLatestValuePerIoaSortedByIoa() {
        LiveDataStore store = new LiveDataStore();
        store.apply(update(1002, "分(0)"));
        store.apply(update(1001, "分(0)"));
        store.apply(update(1001, "合(1)"));

        List<PointUpdate> snapshot = store.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(1001, snapshot.get(0).ioa());
        assertEquals("合(1)", snapshot.get(0).value());
        assertEquals(1002, snapshot.get(1).ioa());
    }

    @Test
    void recentEventsCappedAtCapacity() {
        LiveDataStore store = new LiveDataStore(3);
        for (int i = 1; i <= 5; i++) {
            store.apply(update(1000 + i, "合(1)"));
        }
        List<PointUpdate> events = store.recentEvents();
        assertEquals(3, events.size());
        assertEquals(1003, events.get(0).ioa());
        assertEquals(1005, events.get(2).ioa());
    }

    @Test
    void notifiesSubscribers() {
        LiveDataStore store = new LiveDataStore();
        List<PointUpdate> seen = new ArrayList<>();
        store.subscribe(seen::add);
        store.apply(update(1001, "合(1)"));
        assertEquals(1, seen.size());
        assertEquals(1001, seen.get(0).ioa());
    }

    @Test
    void clearEmptiesEverything() {
        LiveDataStore store = new LiveDataStore();
        store.apply(update(1001, "合(1)"));
        store.clear();
        assertEquals(0, store.snapshot().size());
        assertEquals(0, store.recentEvents().size());
    }

    private static final List<ControlPoint> POINTS = List.of(
            new ControlPoint(6001, "开关", ControlPoint.CommandType.C_SC_NA_1),
            new ControlPoint(7001, "设定", ControlPoint.CommandType.C_SE_NC_1));

    @Test
    void syncSeedsPlaceholdersForControlPoints() {
        LiveDataStore store = new LiveDataStore();
        List<PointUpdate> seen = new ArrayList<>();
        store.subscribe(seen::add);

        store.syncControlPoints(POINTS);

        List<PointUpdate> snapshot = store.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals("C_SC_NA_1", snapshot.get(0).type());
        assertTrue(snapshot.get(0).isPlaceholder());
        assertEquals("未下发", snapshot.get(1).value());
        assertEquals(2, seen.size(), "占位行应通知订阅者");
        assertEquals(0, store.recentEvents().size(), "占位行不进最近事件缓冲");
    }

    @Test
    void syncDoesNotOverwriteRealValues() {
        LiveDataStore store = new LiveDataStore();
        store.apply(new PointUpdate(6001, "C_SC_NA_1", "合(1)", "有效", "ACTIVATION_CON", Instant.now()));

        store.syncControlPoints(POINTS);

        assertEquals("合(1)", store.snapshot().get(0).value(), "已有真实值不被占位覆盖");
    }

    @Test
    void syncRemovesStaleCommandRowsButKeepsMonitorRows() {
        LiveDataStore store = new LiveDataStore();
        store.apply(update(1001, "合(1)")); // 遥信行
        store.syncControlPoints(POINTS);
        List<Integer> removed = new ArrayList<>();
        store.subscribeRemove(removed::add);

        store.syncControlPoints(List.of(POINTS.get(1))); // 点表里删掉 6001

        List<PointUpdate> snapshot = store.snapshot();
        assertEquals(2, snapshot.size());
        assertEquals(1001, snapshot.get(0).ioa(), "遥信行保留");
        assertEquals(7001, snapshot.get(1).ioa());
        assertEquals(List.of(6001), removed, "被删命令点应通知移除");
    }

    @Test
    void syncAfterClearRestoresPlaceholders() {
        LiveDataStore store = new LiveDataStore();
        store.syncControlPoints(POINTS);
        store.clear();
        assertEquals(0, store.snapshot().size());

        store.syncControlPoints(POINTS);
        assertEquals(2, store.snapshot().size());
    }
}
