package com.mordor.kelly.ui.login;

import com.mordor.kelly.kelsy.KelsyPaths;
import com.mordor.kelly.kelsy.config.ConfigLoader;
import com.mordor.kelly.kelsy.config.KelsyConfig;
import com.mordor.kelly.kelsy.provider.ProviderCatalog;
import com.mordor.kelly.kelsy.provider.ProviderSpec;
import javafx.event.ActionEvent;
import javafx.geometry.Insets;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar.ButtonData;
import javafx.scene.control.ButtonType;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Control;
import javafx.scene.control.Dialog;
import javafx.scene.control.Label;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TextField;
import javafx.scene.layout.ColumnConstraints;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Window;

import java.io.UncheckedIOException;
import java.util.List;
import java.util.Optional;

/**
 * 大模型配置对话框：配置 API 服务商、模型名和 API Key。
 *
 * <h3>配置存储</h3>
 * <p>写入 {@code ~/.kelly/kelsy/config.json} 的 {@code model} 字段。</p>
 *
 * <h3>布局结构</h3>
 * <pre>
 *   Dialog
 *   └── VBox (content)
 *       ├── Label (错误提示)
 *       └── GridPane (表单网格, 2列)
 *           ├── Row 0: "服务商" + ComboBox (ProviderSpec)
 *           ├── Row 1: "模型名" + TextField
 *           └── Row 2: "API Key" + PasswordField
 * </pre>
 *
 * <h3>交互逻辑</h3>
 * <ul>
 *   <li>选择服务商时自动填充默认模型名</li>
 *   <li>三个字段全部非空时"保存"按钮才可用</li>
 *   <li>保存时校验并写入配置文件</li>
 * </ul>
 *
 * <p>工具类，不能实例化。</p>
 */
public final class ModelConfigDialog {

    private ModelConfigDialog() {}

    public static Optional<KelsyConfig.ModelSettings> show(Window owner, KelsyPaths paths) {
        Dialog<KelsyConfig.ModelSettings> dialog = new Dialog<>();
        dialog.setTitle("接入大模型");
        if (owner != null) {
            dialog.initOwner(owner);
        }
        dialog.getDialogPane().getStylesheets().add(
                ModelConfigDialog.class.getResource("login.css").toExternalForm());

        ButtonType saveType = new ButtonType("保存", ButtonData.OK_DONE);
        dialog.getDialogPane().getButtonTypes().addAll(saveType, ButtonType.CANCEL);

        List<ProviderSpec> providers = ProviderCatalog.all();
        boolean catalogFailed = providers.isEmpty();

        ComboBox<ProviderSpec> providerCombo = new ComboBox<>();
        providerCombo.getItems().setAll(providers);
        providerCombo.setMaxWidth(Double.MAX_VALUE);
        providerCombo.setCellFactory(p -> new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ProviderSpec s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.displayName());
            }
        });
        providerCombo.setButtonCell(new javafx.scene.control.ListCell<>() {
            @Override
            protected void updateItem(ProviderSpec s, boolean empty) {
                super.updateItem(s, empty);
                setText(empty || s == null ? null : s.displayName());
            }
        });

        TextField modelNameField = field();
        modelNameField.setPromptText("模型名（如 MiniMax-M3）");

        PasswordField apiKeyField = new PasswordField();
        apiKeyField.setPromptText("API Key");
        apiKeyField.getStyleClass().add("login-field");
        apiKeyField.setMaxWidth(Double.MAX_VALUE);

        Label errorLabel = new Label();
        errorLabel.getStyleClass().add("login-error");
        errorLabel.setVisible(false);
        errorLabel.setManaged(false);
        errorLabel.setMaxWidth(Double.MAX_VALUE);
        errorLabel.setWrapText(true);

        KelsyConfig current = ConfigLoader.peek(paths);
        KelsyConfig.ModelSettings model = current.model() != null
                ? current.model()
                : new KelsyConfig.ModelSettings(null, null, null, null);

        ProviderSpec initialSpec = providers.stream()
                .filter(p -> p.id() != null
                        && p.id().equalsIgnoreCase(
                                model.provider() == null ? "" : model.provider()))
                .findFirst()
                .orElse(providers.isEmpty() ? null : providers.get(0));
        if (initialSpec != null) {
            providerCombo.setValue(initialSpec);
            modelNameField.setText(model.modelName() != null && !model.modelName().isBlank()
                    ? model.modelName()
                    : initialSpec.defaultModelName());
            apiKeyField.setText(model.apiKey() == null ? "" : model.apiKey());
        }

        providerCombo.setOnAction(e -> {
            ProviderSpec s = providerCombo.getValue();
            if (s != null) {
                modelNameField.setText(s.defaultModelName());
                apiKeyField.clear();
                apiKeyField.requestFocus();
            }
        });

        GridPane grid = new GridPane();
        grid.setHgap(10);
        grid.setVgap(8);
        grid.setMaxWidth(Double.MAX_VALUE);
        ColumnConstraints labelCol = new ColumnConstraints();
        labelCol.setMinWidth(70);
        ColumnConstraints fieldCol = new ColumnConstraints();
        fieldCol.setHgrow(Priority.ALWAYS);
        fieldCol.setFillWidth(true);
        grid.getColumnConstraints().addAll(labelCol, fieldCol);
        addRow(grid, 0, "服务商", providerCombo);
        addRow(grid, 1, "模型名", modelNameField);
        addRow(grid, 2, "API Key", apiKeyField);

        VBox content = new VBox(10, errorLabel, grid);
        content.setMaxWidth(Double.MAX_VALUE);
        content.setFillWidth(true);
        content.setPadding(new Insets(4, 0, 4, 0));
        dialog.getDialogPane().setContent(content);

        Button saveBtn = (Button) dialog.getDialogPane().lookupButton(saveType);
        Runnable refreshValidity = () -> {
            boolean ok = !catalogFailed
                    && providerCombo.getValue() != null
                    && !modelNameField.getText().isBlank()
                    && !apiKeyField.getText().isBlank();
            saveBtn.setDisable(!ok);
        };
        modelNameField.textProperty().addListener((o, a, b) -> refreshValidity.run());
        apiKeyField.textProperty().addListener((o, a, b) -> refreshValidity.run());
        providerCombo.valueProperty().addListener((o, a, b) -> refreshValidity.run());
        refreshValidity.run();

        if (catalogFailed) {
            providerCombo.setDisable(true);
            modelNameField.setDisable(true);
            apiKeyField.setDisable(true);
            errorLabel.setText("提供商清单加载失败");
            errorLabel.setVisible(true);
            errorLabel.setManaged(true);
        }

        saveBtn.addEventFilter(ActionEvent.ACTION, event -> {
            ProviderSpec spec = providerCombo.getValue();
            if (spec == null) {
                event.consume();
                return;
            }
            String apiKey = apiKeyField.getText();
            String modelName = modelNameField.getText().strip();
            try {
                KelsyConfig next = new KelsyConfig(
                        new KelsyConfig.ModelSettings(spec.id(), apiKey, spec.baseUrl(), modelName),
                        current.workspaceDir(),
                        current.lastUsername(),
                        current.selfAvatarPath(),
                        current.kelsyAvatarPath());
                ConfigLoader.save(paths, next);
                dialog.setResult(new KelsyConfig.ModelSettings(
                        spec.id(), apiKey, spec.baseUrl(), modelName));
            } catch (UncheckedIOException ex) {
                event.consume();
                showError(errorLabel, "无法写入配置：" + paths.config());
            }
        });

        return dialog.showAndWait();
    }

    private static TextField field() {
        TextField tf = new TextField();
        tf.getStyleClass().add("login-field");
        tf.setMaxWidth(Double.MAX_VALUE);
        return tf;
    }

    private static void addRow(GridPane grid, int row, String labelText, Control field) {
        Label l = new Label(labelText);
        l.getStyleClass().add("login-field-label");
        l.setMaxWidth(Double.MAX_VALUE);
        grid.add(l, 0, row);
        grid.add(field, 1, row);
        GridPane.setHgrow(field, Priority.ALWAYS);
        GridPane.setFillWidth(field, true);
    }

    private static void showError(Label label, String message) {
        label.setText(message);
        label.setVisible(true);
        label.setManaged(true);
    }
}
