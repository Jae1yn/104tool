package tool104.livedata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

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
}
