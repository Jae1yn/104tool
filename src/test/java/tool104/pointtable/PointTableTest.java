package tool104.pointtable;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tool104.pointtable.ControlPoint.CommandType;

class PointTableTest {

    @TempDir
    Path dir;

    private Path file() {
        return dir.resolve("points.json");
    }

    @Test
    void notifiesSubscribersOnChanges() {
        PointTable table = new PointTable(file());
        int[] notified = {0};
        table.subscribe(() -> notified[0]++);

        table.add(new ControlPoint(6001, "开关", CommandType.C_SC_NA_1));
        table.update(new ControlPoint(6001, "开关改名", CommandType.C_SC_NA_1));
        table.remove(6001);

        assertEquals(3, notified[0], "增/改/删各通知一次");
    }

    @Test
    void addRemoveAndListSortedByIoa() {
        PointTable table = new PointTable(file());
        table.add(new ControlPoint(6002, "开关2", CommandType.C_SC_NA_1));
        table.add(new ControlPoint(6001, "开关1", CommandType.C_SC_NA_1));

        List<ControlPoint> points = table.list();
        assertEquals(2, points.size());
        assertEquals(6001, points.get(0).ioa());
        assertEquals(6002, points.get(1).ioa());

        table.remove(6001);
        assertEquals(1, table.list().size());
    }

    @Test
    void rejectsDuplicateIoa() {
        PointTable table = new PointTable(file());
        table.add(new ControlPoint(6001, "开关1", CommandType.C_SC_NA_1));
        assertThrows(IllegalArgumentException.class,
                () -> table.add(new ControlPoint(6001, "重复", CommandType.C_SC_NA_1)));
    }

    @Test
    void rejectsInvalidIoa() {
        assertThrows(IllegalArgumentException.class,
                () -> new ControlPoint(0, "非法", CommandType.C_SC_NA_1));
        assertThrows(IllegalArgumentException.class,
                () -> new ControlPoint(0x1000000, "非法", CommandType.C_SC_NA_1));
    }

    @Test
    void persistsAcrossReload() {
        PointTable table = new PointTable(file());
        table.add(new ControlPoint(6001, "开关1", CommandType.C_SC_NA_1));
        table.update(new ControlPoint(6001, "改名", CommandType.C_SC_NA_1));

        PointTable reloaded = new PointTable(file());
        assertEquals(1, reloaded.list().size());
        assertEquals("改名", reloaded.list().get(0).name());
        assertTrue(reloaded.list().get(0).commandType() == CommandType.C_SC_NA_1);
    }
}
