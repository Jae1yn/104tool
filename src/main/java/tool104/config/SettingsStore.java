package tool104.config;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 配置的加载与保存（JSON 文件）。文件缺失或损坏时回落到默认配置。
 */
public final class SettingsStore {

    private final Path file;
    private final ObjectMapper mapper = new ObjectMapper();

    public SettingsStore(Path file) {
        this.file = file;
    }

    public Settings load() {
        if (!Files.exists(file)) {
            return Settings.defaults();
        }
        try {
            return mapper.readValue(file.toFile(), Settings.class);
        } catch (IOException e) {
            return Settings.defaults();
        }
    }

    public void save(Settings settings) throws IOException {
        if (file.getParent() != null) {
            Files.createDirectories(file.getParent());
        }
        mapper.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), settings);
    }
}
