package tool104.ui;

import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;

import tool104.livedata.LiveDataStore;
import tool104.protocol.model.PointUpdate;

/**
 * 实时数据表：每个 IOA 一行，显示最新值。订阅 LiveDataStore 并封送到 FX 线程。
 */
public final class LiveDataPanel extends BorderPane {

    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());

    private final ObservableList<PointUpdate> rows = FXCollections.observableArrayList();

    public LiveDataPanel(LiveDataStore store) {
        TableView<PointUpdate> table = new TableView<>(rows);
        table.setPlaceholder(new Label("暂无数据，等待子站上送"));
        table.getColumns().add(column("IOA", 80, PointUpdate::ioa));
        table.getColumns().add(column("类型", 110, PointUpdate::type));
        table.getColumns().add(column("值", 90, PointUpdate::value));
        table.getColumns().add(column("品质", 80, PointUpdate::quality));
        table.getColumns().add(column("传送原因", 170, PointUpdate::cause));
        table.getColumns().add(column("时标", 110, u -> TIME_FORMAT.format(u.timestamp())));

        Button clear = new Button("清空");
        clear.setOnAction(e -> store.clear());
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, new Label("实时数据（遥测/遥信/遥控/遥调）"), spacer, clear);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6));

        setTop(header);
        setCenter(table);

        for (PointUpdate update : store.snapshot()) {
            applyRow(update);
        }
        store.subscribe(update -> Platform.runLater(() -> applyRow(update)));
        store.subscribeClear(() -> Platform.runLater(rows::clear));
    }

    private void applyRow(PointUpdate update) {
        int low = 0;
        int high = rows.size() - 1;
        while (low <= high) {
            int mid = (low + high) >>> 1;
            int midIoa = rows.get(mid).ioa();
            if (midIoa < update.ioa()) {
                low = mid + 1;
            } else if (midIoa > update.ioa()) {
                high = mid - 1;
            } else {
                rows.set(mid, update);
                return;
            }
        }
        rows.add(low, update);
    }

    private static <T> TableColumn<PointUpdate, T> column(String title, int width,
            Function<PointUpdate, T> extractor) {
        TableColumn<PointUpdate, T> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(extractor.apply(data.getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
