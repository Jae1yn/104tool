package tool104.ui;

import java.util.function.UnaryOperator;

import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.RadioButton;
import javafx.scene.control.TextField;
import javafx.scene.control.TextFormatter;
import javafx.scene.control.ToggleGroup;
import javafx.scene.layout.GridPane;

import tool104.config.Settings;
import tool104.protocol.model.ConnectionMode;

/**
 * 设置编辑对话框：连接方式（监听子站拨入 / 主动拨号连接子站）、端口、ASDU 公共地址（子站站址）、
 * 遥控确认超时、连接建立后自动总召/对时。确定后返回新 Settings；持久化与应用由装配层完成。
 */
public final class SettingsDialog extends Dialog<Settings> {

    private final ToggleGroup modeGroup = new ToggleGroup();
    private final RadioButton listenMode = new RadioButton("监听子站连入（工具作为服务端）");
    private final RadioButton dialMode = new RadioButton("主动拨号连接子站（工具作为客户端）");
    private final Label hostLabel = new Label("子站地址");
    private final TextField hostField = new TextField();
    private final Label portLabel = new Label();
    private final TextField portField;
    private final TextField commonAddressField;
    private final TextField timeoutField;
    private final CheckBox autoInterrogation;
    private final CheckBox autoClockSync;
    private final Label hint = new Label();

    public SettingsDialog(Settings current) {
        setTitle("设置");
        setHeaderText("连接与子站通信参数（修改连接方式/端口/公共地址会重启连接，已连接的子站将断开）");

        listenMode.setToggleGroup(modeGroup);
        dialMode.setToggleGroup(modeGroup);
        listenMode.setSelected(current.connectionMode() == ConnectionMode.LISTEN);
        dialMode.setSelected(current.connectionMode() == ConnectionMode.DIAL);

        hostField.setText(current.substationHost());
        hostField.setPromptText("子站 IP 或主机名");
        hostField.setPrefWidth(160);

        portField = numericField(String.valueOf(current.port()));
        commonAddressField = numericField(String.valueOf(current.commonAddress()));
        timeoutField = numericField(String.valueOf(current.commandTimeoutMs()));
        autoInterrogation = new CheckBox("连接建立后自动下发总召唤");
        autoInterrogation.setSelected(current.autoGeneralInterrogation());
        autoClockSync = new CheckBox("连接建立后自动下发时钟同步");
        autoClockSync.setSelected(current.autoClockSync());

        GridPane grid = new GridPane();
        grid.setHgap(8);
        grid.setVgap(10);
        grid.setPadding(new Insets(12));
        grid.add(listenMode, 0, 0, 2, 1);
        grid.add(dialMode, 0, 1, 2, 1);
        grid.addRow(2, hostLabel, hostField);
        grid.addRow(3, portLabel, portField);
        grid.addRow(4, new Label("公共地址（子站站址）"), commonAddressField);
        grid.addRow(5, new Label("遥控确认超时（毫秒）"), timeoutField);
        grid.add(autoInterrogation, 0, 6, 2, 1);
        grid.add(autoClockSync, 0, 7, 2, 1);
        grid.add(hint, 0, 8, 2, 1);
        getDialogPane().setContent(grid);

        getDialogPane().getButtonTypes().addAll(ButtonType.OK, ButtonType.CANCEL);
        Node okButton = getDialogPane().lookupButton(ButtonType.OK);
        Runnable revalidate = () -> okButton.setDisable(!validate());
        modeGroup.selectedToggleProperty().addListener((obs, o, n) -> {
            onModeChanged();
            revalidate.run();
        });
        hostField.textProperty().addListener((obs, o, n) -> revalidate.run());
        portField.textProperty().addListener((obs, o, n) -> revalidate.run());
        commonAddressField.textProperty().addListener((obs, o, n) -> revalidate.run());
        timeoutField.textProperty().addListener((obs, o, n) -> revalidate.run());
        onModeChanged();

        setResultConverter(button -> button == ButtonType.OK
                ? new Settings(selectedMode(), hostField.getText().trim(), intOf(portField),
                        intOf(commonAddressField), intOf(timeoutField),
                        autoInterrogation.isSelected(), autoClockSync.isSelected())
                : null);
    }

    private ConnectionMode selectedMode() {
        return modeGroup.getSelectedToggle() == dialMode ? ConnectionMode.DIAL : ConnectionMode.LISTEN;
    }

    private void onModeChanged() {
        boolean dial = selectedMode() == ConnectionMode.DIAL;
        hostLabel.setVisible(dial);
        hostField.setVisible(dial);
        hostField.setManaged(dial);
        hostLabel.setManaged(dial);
        portLabel.setText(dial ? "子站端口" : "监听端口");
    }

    private boolean validate() {
        String problem = firstProblem();
        hint.setText(problem == null ? "" : problem);
        return problem == null;
    }

    private String firstProblem() {
        if (selectedMode() == ConnectionMode.DIAL && hostField.getText().trim().isEmpty()) {
            return "请填写子站地址";
        }
        Integer port = tryParse(portField);
        if (port == null || port < 1 || port > 65535) {
            return "端口须在 1~65535";
        }
        Integer ca = tryParse(commonAddressField);
        if (ca == null || ca < 1 || ca > 65534) {
            return "公共地址须在 1~65534";
        }
        Integer timeout = tryParse(timeoutField);
        if (timeout == null || timeout < 100 || timeout > 600000) {
            return "遥控确认超时须在 100~600000 毫秒";
        }
        return null;
    }

    private static Integer tryParse(TextField field) {
        try {
            return Integer.parseInt(field.getText().trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static int intOf(TextField field) {
        return Integer.parseInt(field.getText().trim());
    }

    private static TextField numericField(String initial) {
        TextField field = new TextField();
        UnaryOperator<TextFormatter.Change> digitsOnly =
                change -> change.getControlNewText().matches("\\d*") ? change : null;
        field.setTextFormatter(new TextFormatter<>(digitsOnly));
        field.setText(initial);
        field.setPrefWidth(120);
        return field;
    }
}
