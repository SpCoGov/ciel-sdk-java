package top.spco.ciel;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class ManagedTest {
    @TempDir Path directory;

    @Test void managedEnvironmentAndConfig() throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin",
                File.separatorChar == '\\' ? "java.exe" : "java").toString();
        String classpath = Stream.of(Probe.class, CielClient.class, JsonObject.class)
                .map(type -> {
                    try { return Path.of(type.getProtectionDomain().getCodeSource().getLocation().toURI()).toString(); }
                    catch (java.net.URISyntaxException e) { throw new IllegalStateException(e); }
                }).collect(Collectors.joining(File.pathSeparator));
        Path config = directory.resolve("config.json");
        Files.writeString(config, "{\"value\":\"" + "x".repeat(65524) + "\"}");
        assertEquals(65536, Files.size(config));
        for (String mode : new String[]{"missing", "configured"}) {
            ProcessBuilder builder = new ProcessBuilder(java, "-cp", classpath, Probe.class.getName(), mode);
            for (String key : new String[]{"CIEL_IDENTITY_DIR", "CIEL_CONFIG_PATH", "CIEL_WORK_DIR"})
                builder.environment().remove(key);
            if (mode.equals("configured")) {
                builder.environment().put("CIEL_CONFIG_PATH", config.toString());
                builder.environment().put("CIEL_WORK_DIR", directory.resolve(".").toString());
                builder.environment().put("CIEL_IDENTITY_DIR", directory.resolve("absent").toString());
            }
            Process process = builder.redirectErrorStream(true).start();
            try {
                assertTrue(process.waitFor(20, TimeUnit.SECONDS));
                assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
            } finally { process.destroyForcibly(); }
        }
    }

    public static class Probe {
        private static void expect(String code, Runnable action) {
            try { action.run(); }
            catch (CielException e) {
                if (code.equals(e.code()) && e.kind() == CielException.Kind.STORAGE) return;
                throw e;
            }
            throw new AssertionError(code);
        }
        public static void main(String[] args) throws Exception {
            if (args[0].equals("service")) {
                try (CielClient client = new CielClient(CielClient.managedIdentityDir())) {
                    client.onCommand("demo.echo", execution ->
                            CompletableFuture.completedFuture(new CielClient.CommandResult(true, execution.input())));
                    client.onNotifier("demo.notifier", delivery ->
                            CompletableFuture.completedFuture(CielClient.NotifierResult.delivered()));
                    client.connect().get(15, TimeUnit.SECONDS);
                    client.registerCommand("demo.echo", "Echo JSON").get(5, TimeUnit.SECONDS);
                    client.registerNotifier("demo.notifier", "Test notifier", "").get(5, TimeUnit.SECONDS);
                    String label = CielClient.loadManagedConfig().get("label").getAsString();
                    Path work = CielClient.managedWorkDir();
                    Files.writeString(work.resolve("starting"), client.identity().instanceId() + " " + label);
                    Files.move(work.resolve("starting"), work.resolve("ready"));
                    System.in.read();
                }
                return;
            }
            if (args[0].equals("missing")) {
                expect("MANAGED_IDENTITY_DIR_MISSING", CielClient::managedIdentityDir);
                expect("MANAGED_IDENTITY_DIR_MISSING", CielClient::connectManaged);
                expect("MANAGED_IDENTITY_DIR_MISSING", () -> CielClient.connectManaged(CielClient.Options.defaults()));
                expect("MANAGED_CONFIG_PATH_MISSING", CielClient::loadManagedConfig);
                expect("MANAGED_WORK_DIR_MISSING", CielClient::managedWorkDir);
                return;
            }
            Path config = Path.of(System.getenv("CIEL_CONFIG_PATH"));
            if (!CielClient.managedWorkDir().equals(config.getParent())) throw new AssertionError("work directory");
            JsonObject first = CielClient.loadManagedConfig();
            if (first.get("value").getAsString().length() != 65524) throw new AssertionError("config bound");
            first.remove("value");
            if (!CielClient.loadManagedConfig().has("value")) throw new AssertionError("independent config");
            Files.writeString(config, " ".repeat(65535) + "{}");
            expect("INVALID_CONFIG_FILE", CielClient::loadManagedConfig);
            Files.writeString(config, "[]");
            expect("INVALID_CONFIG_FILE", CielClient::loadManagedConfig);
            Files.write(config, new byte[]{(byte) 0xff});
            expect("INVALID_CONFIG_FILE", CielClient::loadManagedConfig);
            Files.writeString(config, "{\"value\":1,\"value\":2}");
            expect("INVALID_CONFIG_FILE", CielClient::loadManagedConfig);
            expect("IDENTITY_STORAGE_FAILED", CielClient::connectManaged);
            Path identityDir = CielClient.managedIdentityDir();
            if (!identityDir.equals(config.getParent().resolve("absent"))) throw new AssertionError("identity directory");
        }
    }

    @Test void configRejectsLinkedFile() throws IOException {
        Path target = Files.writeString(directory.resolve("real.json"), "{}");
        Path link = directory.resolve("link.json");
        try { Files.createSymbolicLink(link, target); }
        catch (UnsupportedOperationException | IOException e) {
            assumeTrue(false, "Symbolic links unavailable");
        }
        CielException error = assertThrows(CielException.class,
                () -> IdentityStore.read(link, 65536, "INVALID_CONFIG_FILE"));
        assertEquals("INVALID_CONFIG_FILE", error.code());
        assertNull(error.getCause());
    }
}
