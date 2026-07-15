package tool104.ui;

import java.io.File;
import java.io.IOException;

import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.ListView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.stage.FileChooser;

import tool104.framelog.FrameLog;

/**
 * 报文日志视图：滚动展示收发报文摘要，可清空、导出。
 */
public final class FrameLogPanel extends BorderPane {

    private static final int MAX_VISIBLE_LINES = 2000;

    private final ListView<String> list = new ListView<>();
    private final CheckBox autoScroll = new CheckBox("自动滚动");

    public FrameLogPanel(FrameLog frameLog) {
        autoScroll.setSelected(true);

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
                    appendLine("!! 导出失败: " + ex.getMessage());
                }
            }
        });

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox header = new HBox(8, new Label("报文日志"), spacer, autoScroll, clear, export);
        header.setAlignment(Pos.CENTER_LEFT);
        header.setPadding(new Insets(6));

        setTop(header);
        setCenter(list);

        for (var frame : frameLog.snapshot()) {
            appendLine(FrameLog.format(frame));
        }
        frameLog.subscribe(frame -> Platform.runLater(() -> appendLine(FrameLog.format(frame))));
        frameLog.subscribeClear(() -> Platform.runLater(() -> list.getItems().clear()));
    }

    private void appendLine(String line) {
        list.getItems().add(line);
        if (list.getItems().size() > MAX_VISIBLE_LINES) {
            list.getItems().remove(0);
        }
        if (autoScroll.isSelected()) {
            list.scrollTo(list.getItems().size() - 1);
        }
    }
}
