package tool104.ui;

import java.io.File;
import java.io.IOException;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.function.Function;

import javafx.application.Platform;
import javafx.beans.property.ReadOnlyObjectWrapper;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Orientation;
import javafx.geometry.Pos;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SplitPane;
import javafx.scene.control.TableCell;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.input.Clipboard;
import javafx.scene.input.ClipboardContent;
import javafx.scene.input.KeyCode;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.text.Font;
import javafx.stage.FileChooser;

import tool104.framelog.FrameLog;
import tool104.protocol.ApduExplainer;
import tool104.protocol.model.RawFrame;

/**
 * 报文日志视图：表格分列展示（时间/方向/摘要/原始帧），选中行在下方详情区逐字段剖析原始帧
 * （APCI/ASDU 头/信息对象逐组标注含义），可拖动分隔条调高度，内容可复制；可清空、导出。
 * 自动滚动在有选中行时暂停（Esc 清除选中恢复），避免查看详情时被新报文冲走。
 */
public final class FrameLogPanel extends BorderPane {

    private static final int MAX_VISIBLE_ROWS = 2000;
    private static final DateTimeFormatter TIME_FORMAT =
            DateTimeFormatter.ofPattern("HH:mm:ss.SSS").withZone(ZoneId.systemDefault());
    private static final Font MONO = Font.font("Monospaced");
    private static final String DETAIL_PROMPT = "选中一行查看完整报文（可复制）";

    private final ObservableList<RawFrame> rows = FXCollections.observableArrayList();
    private final TableView<RawFrame> table = new TableView<>(rows);
    private final TextArea detail = new TextArea();
    private final CheckBox autoScroll = new CheckBox("自动滚动");

    public FrameLogPanel(FrameLog frameLog) {
        autoScroll.setSelected(true);

        table.setPlaceholder(new Label("暂无报文"));
        table.getColumns().add(column("时间", 100, f -> TIME_FORMAT.format(f.timestamp()), null));
        table.getColumns().add(column("方向", 44, FrameLogPanel::arrow, FrameLogPanel::directionColor));
        TableColumn<RawFrame, String> summaryCol = column("摘要", 260, RawFrame::summary, null);
        table.getColumns().add(summaryCol);
        TableColumn<RawFrame, String> rawCol = column("原始帧", 300, f -> f.rawHex() == null ? "" : f.rawHex(), null);
        rawCol.setCellFactory(col -> {
            TableCell<RawFrame, String> cell = plainCell(null);
            cell.setFont(MONO);
            return cell;
        });
        table.getColumns().add(rawCol);

        table.setContextMenu(buildContextMenu());
        table.getSelectionModel().selectedItemProperty().addListener((obs, old, selected) -> showDetail(selected));
        table.setOnKeyPressed(e -> {
            if (e.getCode() == KeyCode.ESCAPE) {
                table.getSelectionModel().clearSelection();
            }
        });

        detail.setEditable(false);
        detail.setWrapText(false);
        detail.setFont(MONO);
        detail.setPromptText(DETAIL_PROMPT);

        Button clear = new Button("清空");
        clear.setOnAction(e -> frameLog.clear());

        Button export = new Button("导出…");
        export.setOnAction(e -> {
            FileChooser chooser = new FileChooser();
            chooser.setTitle("导出报文日志");
            chooser.setInitialFileName("frames.log");
            File target = chooser.showSaveDialog(getScene().getWindow());
            if (target != null) {
                try {
                    frameLog.exportToFile(target.toPath());
                } catch (IOException ex) {
                    new Alert(Alert.AlertType.ERROR, "导出失败: " + ex.getMessage()).showAndWait();
                }
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, new Label("报文日志"), spacer, autoScroll, clear, export);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6));

        SplitPane split = new SplitPane(table, detail);
        split.setOrientation(Orientation.VERTICAL);
        split.setDividerPositions(0.65);

        setTop(header);
        setCenter(split);

        for (RawFrame frame : frameLog.snapshot()) {
            append(frame);
        }
        frameLog.subscribe(frame -> Platform.runLater(() -> append(frame)));
        frameLog.subscribeClear(() -> Platform.runLater(() -> {
            rows.clear();
            detail.clear();
        }));
    }

    private void append(RawFrame frame) {
        rows.add(frame);
        if (rows.size() > MAX_VISIBLE_ROWS) {
            rows.remove(0);
        }
        // 有选中行时暂停自动滚动，避免查看详情被新报文冲走；Esc 清除选中即恢复
        if (autoScroll.isSelected() && table.getSelectionModel().isEmpty()) {
            table.scrollTo(rows.size() - 1);
        }
    }

    private void showDetail(RawFrame frame) {
        if (frame == null) {
            detail.clear();
            return;
        }
        detail.setText(detailText(frame));
    }

    private static String detailText(RawFrame frame) {
        StringBuilder sb = new StringBuilder(FrameLog.format(frame));
        if (frame.rawHex() != null) {
            sb.append("\n\n").append(ApduExplainer.explain(frame.rawHex()));
        }
        return sb.toString();
    }

    private ContextMenu buildContextMenu() {
        MenuItem copySummary = new MenuItem("复制摘要");
        copySummary.setOnAction(e -> copySelected(RawFrame::summary));
        MenuItem copyRaw = new MenuItem("复制原始帧");
        copyRaw.setOnAction(e -> copySelected(RawFrame::rawHex));
        MenuItem copyLine = new MenuItem("复制整行");
        copyLine.setOnAction(e -> copySelected(FrameLog::format));
        MenuItem copyExplain = new MenuItem("复制解析");
        copyExplain.setOnAction(e -> copySelected(FrameLogPanel::detailText));
        return new ContextMenu(copySummary, copyRaw, copyLine, copyExplain);
    }

    private void copySelected(Function<RawFrame, String> extractor) {
        RawFrame selected = table.getSelectionModel().getSelectedItem();
        if (selected == null) {
            return;
        }
        String text = extractor.apply(selected);
        if (text == null || text.isEmpty()) {
            return;
        }
        ClipboardContent content = new ClipboardContent();
        content.putString(text);
        Clipboard.getSystemClipboard().setContent(content);
    }

    private static String arrow(RawFrame frame) {
        return frame.direction() == RawFrame.Direction.SENT ? "→" : "←";
    }

    private static Color directionColor(RawFrame frame) {
        return frame.direction() == RawFrame.Direction.SENT ? Color.ROYALBLUE : Color.SEAGREEN;
    }

    private static TableColumn<RawFrame, String> column(String title, int width,
            Function<RawFrame, String> extractor, Function<RawFrame, Color> colorer) {
        TableColumn<RawFrame, String> column = new TableColumn<>(title);
        column.setCellValueFactory(data -> new ReadOnlyObjectWrapper<>(extractor.apply(data.getValue())));
        column.setPrefWidth(width);
        if (colorer != null) {
            column.setCellFactory(col -> plainCell(colorer));
        }
        return column;
    }

    private static TableCell<RawFrame, String> plainCell(Function<RawFrame, Color> colorer) {
        return new TableCell<>() {
            @Override
            protected void updateItem(String item, boolean empty) {
                super.updateItem(item, empty);
                if (empty || item == null) {
                    setText(null);
                    return;
                }
                setText(item);
                if (colorer != null && getTableRow() != null && getTableRow().getItem() != null) {
                    setTextFill(colorer.apply(getTableRow().getItem()));
                }
            }
        };
    }
}
