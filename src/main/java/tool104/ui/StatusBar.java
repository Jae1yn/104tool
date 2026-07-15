package tool104.ui;

import java.io.IOException;
import java.util.function.Consumer;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.paint.Color;
import javafx.scene.shape.Circle;

import tool104.config.Settings;
import tool104.protocol.MasterSession;
import tool104.protocol.model.ConnectionEvent;
import tool104.protocol.model.ConnectionMode;

/**
 * 顶部状态栏：连接开关（监听/拨号，取决于设置）、连接状态指示、总召唤/时钟同步按钮、设置入口。
 * onConnectionEvent 必须在 FX 线程上调用（由装配层封送）。
 */
public final class StatusBar extends HBox {

    private final MasterSession session;
    private final Circle indicator = new Circle(6, Color.GRAY);
    private final Label statusText = new Label("未启动");
    private final Label config = new Label();
    private final Button startStop = new Button();
    private final Button interrogation = new Button("总召唤");
    private final Button clockSync = new Button("时钟同步");
    private final Button settingsButton = new Button("设置…");

    private Settings settings;

    public StatusBar(MasterSession session, Settings settings, Consumer<Settings> onSettingsChanged) {
        super(10);
        this.session = session;
        this.settings = settings;

        setPadding(new Insets(8));
        setAlignment(Pos.CENTER_LEFT);

        config.setText(configText(settings));
        startStop.setText(startLabel());

        startStop.setOnAction(e -> toggle());
        interrogation.setOnAction(e -> trySend(() -> session.sendGeneralInterrogation(), "总召唤"));
        clockSync.setOnAction(e -> trySend(() -> session.sendClockSync(), "时钟同步"));
        settingsButton.setOnAction(e -> new SettingsDialog(this.settings).showAndWait().ifPresent(updated -> {
            this.settings = updated;
            config.setText(configText(updated));
            if (!session.isRunning()) {
                startStop.setText(startLabel());
            }
            onSettingsChanged.accept(updated);
        }));
        setCommandsDisabled(true);

        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        getChildren().addAll(indicator, statusText, config, spacer,
                interrogation, clockSync, settingsButton, startStop);
    }

    private String configText(Settings settings) {
        return settings.connectionMode() == ConnectionMode.DIAL
                ? "子站 " + settings.substationHost() + ":" + settings.port() + " · 公共地址 " + settings.commonAddress()
                : "端口 " + settings.port() + " · 公共地址 " + settings.commonAddress();
    }

    private String startLabel() {
        return settings.connectionMode() == ConnectionMode.DIAL ? "连接子站" : "启动监听";
    }

    private String stopLabel() {
        return settings.connectionMode() == ConnectionMode.DIAL ? "断开" : "停止监听";
    }

    private interface SendAction {
        void send() throws IOException;
    }

    private void trySend(SendAction action, String label) {
        try {
            action.send();
        } catch (IOException e) {
            statusText.setText(label + "发送失败: " + e.getMessage());
        }
    }

    private void toggle() {
        if (session.isRunning()) {
            session.stop();
        } else {
            try {
                session.start();
            } catch (IOException e) {
                indicator.setFill(Color.RED);
                statusText.setText("启动失败: " + e.getMessage());
            }
        }
    }

    /** 仅在 FX 线程上调用。 */
    public void onConnectionEvent(ConnectionEvent event) {
        boolean dial = settings.connectionMode() == ConnectionMode.DIAL;
        switch (event.state()) {
            case LISTENING -> {
                indicator.setFill(Color.ORANGE);
                statusText.setText(dial ? "正在连接子站…" : "监听中，等待子站连入…");
                startStop.setText(stopLabel());
                setCommandsDisabled(true);
            }
            case CONNECTED -> {
                indicator.setFill(Color.LIMEGREEN);
                statusText.setText("子站已连接 " + (event.remoteAddress() == null ? "" : event.remoteAddress()));
                startStop.setText(stopLabel());
                setCommandsDisabled(false);
            }
            case DISCONNECTED -> {
                indicator.setFill(Color.ORANGE);
                statusText.setText(dial ? "与子站的连接已断开，重试中…" : "子站已断开");
                setCommandsDisabled(true);
            }
            case STOPPED -> {
                indicator.setFill(Color.GRAY);
                statusText.setText("已停止");
                startStop.setText(startLabel());
                setCommandsDisabled(true);
            }
        }
    }

    private void setCommandsDisabled(boolean disabled) {
        interrogation.setDisable(disabled);
        clockSync.setDisable(disabled);
    }
}
