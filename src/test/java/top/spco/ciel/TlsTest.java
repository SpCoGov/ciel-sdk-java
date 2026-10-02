package top.spco.ciel;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import javax.net.ssl.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class TlsTest {
    @TempDir Path directory;

    @Test void wrongPinExpiredCertificateAndTls12AreRejectedBeforeApplicationData() throws Exception {
        for (String test : List.of("wrong-pin", "expired", "tls12")) {
            Path keyStore = directory.resolve(test + ".p12");
            String password = "disposable-test-password";
            String executable = System.getProperty("os.name").startsWith("Windows") ? "keytool.exe" : "keytool";
            List<String> args = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                    "-genkeypair", "-alias", "test", "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=localhost",
                    "-ext", "SAN=dns:localhost", "-storetype", "PKCS12", "-keystore", keyStore.toString(),
                    "-storepass", password, "-validity", "1", "-noprompt"));
            if (test.equals("expired")) args.addAll(List.of("-startdate", "2000/01/01 00:00:00"));
            Process tool = new ProcessBuilder(args).redirectErrorStream(true)
                    .redirectOutput(directory.resolve(test + ".log").toFile()).start();
            assertTrue(tool.waitFor(15, TimeUnit.SECONDS));
            assertEquals(0, tool.exitValue(), "Test certificate generation failed");
            KeyStore keys = KeyStore.getInstance("PKCS12");
            try (var input = Files.newInputStream(keyStore)) { keys.load(input, password.toCharArray()); }
            KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            managers.init(keys, password.toCharArray());
            SSLContext tls = SSLContext.getInstance("TLS"); tls.init(managers.getKeyManagers(), null, null);
            AtomicBoolean receivedApplicationData = new AtomicBoolean();
            ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
            ExecutorService io = Executors.newFixedThreadPool(3);
            try (SSLServerSocket server = (SSLServerSocket) tls.getServerSocketFactory().createServerSocket(
                    0, 1, InetAddress.getByName("127.0.0.1"));
                 Wire wire = new Wire(clock, message -> fail("TLS failure must prevent protocol messages"),
                         error -> { }, Duration.ofSeconds(4))) {
                server.setEnabledProtocols(new String[] {test.equals("tls12") ? "TLSv1.2" : "TLSv1.3"});
                Future<?> accepted = io.submit(() -> {
                    try (SSLSocket socket = (SSLSocket) server.accept()) {
                        socket.setSoTimeout(5000);
                        socket.startHandshake();
                        receivedApplicationData.set(socket.getInputStream().read() >= 0);
                    } catch (java.io.IOException expected) { }
                });
                String pin = test.equals("wrong-pin") ? "0".repeat(64) : PinnedTls.pin((X509Certificate) keys.getCertificate("test"));
                CielException failure = Enrollment.unwrap(assertThrows(ExecutionException.class, () ->
                        wire.connect("wss://localhost:" + server.getLocalPort() + "/ws/service", pin, io).get(8, TimeUnit.SECONDS)));
                assertEquals(CielException.Kind.TLS, failure.kind(), test);
                accepted.get(8, TimeUnit.SECONDS);
                assertFalse(receivedApplicationData.get(), test);
            } finally { clock.shutdownNow(); io.shutdownNow(); }
        }
    }
}
