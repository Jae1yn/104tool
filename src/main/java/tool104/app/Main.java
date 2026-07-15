package tool104.app;

import java.io.IOException;
import java.nio.file.Path;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.scene.Scene;
import javafx.stage.Stage;

import tool104.config.Settings;
import tool104.config.SettingsStore;
import tool104.framelog.FrameLog;
import tool104.livedata.LiveDataStore;
import tool104.pointtable.PointTable;
import tool104.protocol.SessionListener;
import tool104.protocol.j60870.J60870MasterSession;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.PointUpdate;
import tool104.protocol.model.RawFrame;
import tool104.ui.MainWindow;

/**
 * 装配与启动：加载配置 → 构造各模块 → 接线 → 启动 UI。没有业务逻辑。
 */
public class Main extends Application {

    private J60870MasterSession session;
    private SettingsStore settingsStore;
    private FrameLog frameLog;
    private LiveDataStore liveData;
    private volatile Settings settings;

    @Override
    public void start(Stage stage) {
        Path configDir = Path.of(System.getProperty("user.home"), ".tool104");
        settingsStore = new SettingsStore(configDir.resolve("settings.json"));
        settings = settingsStore.load();
        PointTable pointTable = new PointTable(configDir.resolve("points.json"));
        liveData = new LiveDataStore();
        frameLog = new FrameLog();

        session = new J60870MasterSession(settings.connectionMode(), settings.substationHost(),
                settings.port(), settings.commonAddress(), settings.commandTimeoutMs());

        MainWindow window = new MainWindow(session, settings, liveData, frameLog, pointTable,
                this::applySettings);

        session.addListener(new SessionListener() {
            @Override
            public void onConnectionEvent(ConnectionEvent event) {
                if (event.state() == ConnectionEvent.State.CONNECTED) {
                    autoSendOnConnect();
                }
                Platform.runLater(() -> window.statusBar().onConnectionEvent(event));
            }

            @Override
            public void onPointUpdate(PointUpdate update) {
                liveData.apply(update);
            }

            @Override
            public void onFrame(RawFrame frame) {
                frameLog.append(frame);
            }
        });

        stage.setTitle("104工具 · 主站替身");
        stage.setScene(new Scene(window, 1200, 750));
        stage.show();
        // 不自动连接：由用户点击状态栏的「连接子站/启动监听」启动
    }

    private void autoSendOnConnect() {
        try {
            if (settings.autoGeneralInterrogation()) {
                session.sendGeneralInterrogation();
            }
            if (settings.autoClockSync()) {
                session.sendClockSync();
            }
        } catch (IOException e) {
            frameLog.append(RawFrame.received("连接后自动总召/对时发送失败: " + e.getMessage()));
        }
    }

    private void applySettings(Settings updated) {
        Settings previous = settings;
        settings = updated;
        try {
            settingsStore.save(updated);
        } catch (IOException e) {
            frameLog.append(RawFrame.received("保存配置失败: " + e.getMessage()));
        }
        if (!previous.sameConnection(updated)) {
            // 连接参数变了意味着 reconfigure 会重启连接：旧会话的数据和报文属于旧对端，先清掉避免误读
            liveData.clear();
            frameLog.clear();
        }
        try {
            session.reconfigure(updated.connectionMode(), updated.substationHost(), updated.port(),
                    updated.commonAddress(), updated.commandTimeoutMs());
        } catch (IOException e) {
            frameLog.append(RawFrame.received("以新配置重启连接失败: " + e.getMessage() + "（端口被占用？）"));
        }
    }

    @Override
    public void stop() {
        if (session != null) {
            session.stop();
        }
    }

    public static void main(String[] args) {
        launch(args);
    }
}
