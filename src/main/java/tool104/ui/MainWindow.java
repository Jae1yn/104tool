package tool104.ui;

import java.util.function.Consumer;

import javafx.geometry.Orientation;
import javafx.scene.control.SplitPane;
import javafx.scene.layout.BorderPane;

import tool104.config.Settings;
import tool104.framelog.FrameLog;
import tool104.livedata.LiveDataStore;
import tool104.pointtable.PointTable;
import tool104.protocol.MasterSession;

/**
 * 主窗口布局：顶部状态栏，中间实时数据/报文日志上下分栏，右侧点表与遥控。
 */
public final class MainWindow extends BorderPane {

    private final StatusBar statusBar;

    public MainWindow(MasterSession session, Settings settings, LiveDataStore liveData,
            FrameLog frameLog, PointTable pointTable, Consumer<Settings> onSettingsChanged) {
        statusBar = new StatusBar(session, settings, onSettingsChanged);

        SplitPane center = new SplitPane(new LiveDataPanel(liveData), new FrameLogPanel(frameLog));
        center.setOrientation(Orientation.VERTICAL);
        center.setDividerPositions(0.6);

        setTop(statusBar);
        setCenter(center);
        setRight(new ControlPanel(pointTable, session));
    }

    public StatusBar statusBar() {
        return statusBar;
    }
}
