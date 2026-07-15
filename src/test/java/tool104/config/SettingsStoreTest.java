package tool104.config;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tool104.protocol.model.ConnectionMode;

class SettingsStoreTest {

    @TempDir
    Path dir;

    @Test
    void missingFileFallsBackToDefaults() {
        SettingsStore store = new SettingsStore(dir.resolve("nope.json"));
        assertEquals(Settings.defaults(), store.load());
    }

    @Test
    void corruptFileFallsBackToDefaults() throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, "{not valid json");
        assertEquals(Settings.defaults(), new SettingsStore(file).load());
    }

    @Test
    void saveThenLoadRoundTrips() throws IOException {
        Path file = dir.resolve("sub/settings.json");
        SettingsStore store = new SettingsStore(file);
        Settings custom = new Settings(ConnectionMode.DIAL, "10.0.0.5", 2405, 3, 8000, true, true);
        store.save(custom);
        assertEquals(custom, store.load());
    }

    @Test
    void loadingFileWithoutNewFieldsFallsBackToListenDefaults() throws IOException {
        Path file = dir.resolve("settings.json");
        Files.writeString(file, """
                {
                  "port" : 2404,
                  "commonAddress" : 1,
                  "commandTimeoutMs" : 5000,
                  "autoGeneralInterrogation" : true,
                  "autoClockSync" : true
                }
                """);
        Settings loaded = new SettingsStore(file).load();
        assertEquals(ConnectionMode.LISTEN, loaded.connectionMode());
        assertEquals("127.0.0.1", loaded.substationHost());
        assertEquals(2404, loaded.port());
    }
}
