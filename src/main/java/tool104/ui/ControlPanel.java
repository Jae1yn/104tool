package tool104.ui;

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
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;

import tool104.pointtable.ControlPoint;
import tool104.pointtable.PointTable;
import tool104.protocol.MasterSession;
import tool104.protocol.model.CommandResult;

/**
 * 点表编辑 + 遥控下发面板。点表仅定义可控点（C_SC_NA_1 直接执行），与接收数据展示无关。
 */
public final class ControlPanel extends BorderPane {

    private final PointTable pointTable;
    private final MasterSession session;

    private final ObservableList<ControlPoint> rows = FXCollections.observableArrayList();
    private final TableView<ControlPoint> table = new TableView<>(rows);
    private final Label message = new Label();
    private final Button closeButton = new Button("合闸 (ON)");
    private final Button openButton = new Button("分闸 (OFF)");

    public ControlPanel(PointTable pointTable, MasterSession session) {
        this.pointTable = pointTable;
        this.session = session;

        table.setPlaceholder(new Label("点表为空，请添加可控点"));
        table.getColumns().add(column("IOA", 80, ControlPoint::ioa));
        table.getColumns().add(column("名称", 110, ControlPoint::name));
        table.getColumns().add(column("命令类型", 100, p -> p.commandType().name()));
        refresh();

        TextField ioaField = new TextField();
        ioaField.setPromptText("IOA");
        ioaField.setPrefWidth(80);
        TextField nameField = new TextField();
        nameField.setPromptText("名称（可选）");
        nameField.setPrefWidth(110);

        Button add = new Button("添加");
        add.setOnAction(e -> {
            try {
                int ioa = Integer.parseInt(ioaField.getText().trim());
                pointTable.add(new ControlPoint(ioa, nameField.getText().trim(),
                        ControlPoint.CommandType.C_SC_NA_1));
                ioaField.clear();
                nameField.clear();
                showMessage("");
                refresh();
            } catch (NumberFormatException ex) {
                showMessage("IOA 必须是数字");
            } catch (IllegalArgumentException ex) {
                showMessage(ex.getMessage());
            }
        });

        Button remove = new Button("删除选中");
        remove.setOnAction(e -> {
            ControlPoint selected = table.getSelectionModel().getSelectedItem();
            if (selected != null) {
                pointTable.remove(selected.ioa());
                refresh();
            }
        });

        HBox editRow = new HBox(6, ioaField, nameField, add, remove);
        editRow.setAlignment(Pos.CENTER_LEFT);

        closeButton.setOnAction(e -> sendCommand(true));
        openButton.setOnAction(e -> sendCommand(false));
        HBox commandRow = new HBox(6, new Label("遥控:"), closeButton, openButton);
        commandRow.setAlignment(Pos.CENTER_LEFT);

        message.setWrapText(true);

        VBox bottom = new VBox(8, editRow, commandRow, message);
        bottom.setPadding(new Insets(8, 6, 6, 6));

        Label title = new Label("点表（可控点）");
        BorderPane.setMargin(title, new Insets(6));

        setTop(title);
        setCenter(table);
        setBottom(bottom);
        setPrefWidth(320);
    }

    private void sendCommand(boolean on) {
        ControlPoint selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            showMessage("请先在点表中选择一个可控点");
            return;
        }
        setCommandButtonsDisabled(true);
        String action = (on ? "合闸" : "分闸") + " IOA=" + selected.ioa();
        showMessage(action + " 已下发，等待确认…");
        session.sendSingleCommand(selected.ioa(), on).whenComplete((result, error) ->
                Platform.runLater(() -> {
                    setCommandButtonsDisabled(false);
                    if (error != null) {
                        showMessage(action + " 异常: " + error.getMessage());
                    } else {
                        showMessage(action + " → " + describe(result));
                    }
                }));
    }

    private static String describe(CommandResult result) {
        return switch (result.status()) {
            case CONFIRMED -> "已确认 ✓";
            case NEGATIVE -> "否定确认: " + result.detail();
            case TIMEOUT -> "超时未确认";
            case FAILED -> "失败: " + result.detail();
        };
    }

    private void setCommandButtonsDisabled(boolean disabled) {
        closeButton.setDisable(disabled);
        openButton.setDisable(disabled);
    }

    private void showMessage(String text) {
        message.setText(text);
    }

    private void refresh() {
        rows.setAll(pointTable.list());
    }

    private static <T> TableColumn<ControlPoint, T> column(String title, int width,
            Function<ControlPoint, T> extractor) {
        TableColumn<ControlPoint, T> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(extractor.apply(data.getValue())));
        column.setPrefWidth(width);
        return column;
    }
}
