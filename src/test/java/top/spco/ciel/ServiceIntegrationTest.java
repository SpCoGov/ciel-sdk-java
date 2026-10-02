package top.spco.ciel;

import com.google.gson.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.security.cert.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Runs against a separate real Ciel process and private, disposable database. */
class ServiceIntegrationTest {
    @TempDir Path directory;

    @Test @Timeout(180) void realPinnedWssEnrollmentEventsCommandsNotificationsHeartbeatAndRecovery() throws Exception {
        String binary = System.getProperty("ciel.server.binary");
        assumeTrue(binary != null, "Pass -PcielServerBinary=/absolute/path/to/ciel to enable real-server acceptance");
        try (Server server = new Server(Path.of(binary), directory)) {
            Path workerHome = directory.resolve("worker");
            Path callerHome = directory.resolve("caller");
            String rustClient = System.getProperty("ciel.rust.client.binary");
            Path rustScenario = directory.resolve("rust-scenario.json");
            Files.writeString(rustScenario, "[{\"send\":{\"v\":1,\"type\":\"heartbeat\"},\"expect\":{\"v\":1,\"type\":\"heartbeat_ack\"}}]");
            Path grant = server.grant("java.worker");
            JsonObject wrongPin = IdentityStore.read(grant);
            wrongPin.addProperty("server_spki_sha256", "0".repeat(64));
            Path wrongGrant = directory.resolve("wrong-pin.json");
            Files.writeString(wrongGrant, Protocol.JSON.toJson(wrongPin));
            assertEquals(CielException.Kind.TLS, failure(CielClient.enroll(wrongGrant, directory.resolve("wrong-pin"))).kind());
            assertEquals(0, server.api("GET", "/api/instances", null).get("total").getAsInt());
            CielClient.Identity workerIdentity = CielClient.enroll(grant, workerHome).get(20, TimeUnit.SECONDS);
            CielClient.enroll(server.grant("java.caller"), callerHome).get(20, TimeUnit.SECONDS);
            assertEquals("ALREADY_ENROLLED", failure(CielClient.enroll(grant, workerHome)).code());
            assertThrows(CielException.class, () -> new CielClient(directory.resolve("missing")));

            AtomicInteger executions = new AtomicInteger();
            BlockingQueue<JsonObject> events = new LinkedBlockingQueue<>();
            BlockingQueue<CielClient.State> states = new LinkedBlockingQueue<>();
            BlockingQueue<CielClient.CommandCall> completions = new LinkedBlockingQueue<>();
            CompletableFuture<CielClient.CommandResult> delayedResult = new CompletableFuture<>();
            CountDownLatch delayedStarted = new CountDownLatch(1);
            try (CielClient worker = new CielClient(workerHome); CielClient caller = new CielClient(callerHome)) {
                worker.onCommand("demo.echo", execution -> {
                    executions.incrementAndGet();
                    return CompletableFuture.completedFuture(new CielClient.CommandResult(true, execution.input()));
                }).onCommand("demo.delayed", execution -> { delayedStarted.countDown(); return delayedResult; });
                caller.onEvent("demo.changed", events::add).onCommandCompleted(completions::add).onState(states::add);
                worker.connect().get(20, TimeUnit.SECONDS);
                caller.connect().get(20, TimeUnit.SECONDS);
                assertThrows(CielException.class, () -> new CielClient(workerHome));
                if (rustClient != null) {
                    Path log = directory.resolve("rust-lock.log");
                    Process peer = new ProcessBuilder(rustClient, "scenario", workerHome.toString(), rustScenario.toString())
                            .redirectErrorStream(true).redirectOutput(log.toFile()).start();
                    try {
                        assertTrue(peer.waitFor(10, TimeUnit.SECONDS));
                        assertNotEquals(0, peer.exitValue());
                        assertTrue(Files.readString(log).contains("Agent directory is already in use"));
                    } finally { if (peer.isAlive()) peer.destroyForcibly(); }
                }
                worker.registerEvent("demo.changed").get(5, TimeUnit.SECONDS);
                caller.subscribeEvent("demo.changed").get(5, TimeUnit.SECONDS);
                JsonObject payload = new JsonObject(); payload.addProperty("text", "你好 😀");
                String publicationRequest = CielClient.newRequestId();
                JsonObject publication = worker.publishEvent("demo.changed", payload, publicationRequest).get(5, TimeUnit.SECONDS);
                assertEquals(publicationRequest, Protocol.string(publication, "request_id"));
                assertEquals(1, Protocol.integer(publication, "queued_count"));
                assertEquals(payload, events.poll(5, TimeUnit.SECONDS).get("payload"));
                JsonObject repeatedPublication = worker.publishEvent("demo.changed", payload, publicationRequest).get(5, TimeUnit.SECONDS);
                assertNotEquals(Protocol.integer(publication, "publication_id"), Protocol.integer(repeatedPublication, "publication_id"));
                assertNotNull(events.poll(5, TimeUnit.SECONDS)); // Publishing is intentionally not idempotent.
                worker.registerCommand("demo.echo", "Echo JSON").get(5, TimeUnit.SECONDS);
                worker.registerCommand("demo.delayed", "Delayed result").get(5, TimeUnit.SECONDS);
                for (String command : List.of("demo.echo", "demo.delayed")) server.api("PUT",
                        "/api/commands/" + workerIdentity.instanceId() + "/" + command + "/permissions",
                        "{\"subjects\":[{\"kind\":\"instance\",\"id\":\"" + caller.identity().instanceId() + "\"}]}");
                String requestId = CielClient.newRequestId();
                CielClient.CommandCall accepted = caller.invokeCommand(workerIdentity.instanceId(), "demo.echo", payload, 30, requestId).get(5, TimeUnit.SECONDS);
                CielClient.CommandCall finished = caller.awaitCommand(accepted.id(), Duration.ofSeconds(5)).get(6, TimeUnit.SECONDS);
                assertEquals("SUCCEEDED", finished.status());
                assertEquals(payload, finished.output());
                assertEquals(accepted.id(), completions.poll(5, TimeUnit.SECONDS).id());
                assertEquals(accepted.id(), caller.invokeCommand(workerIdentity.instanceId(), "demo.echo", payload, 30, requestId).get(5, TimeUnit.SECONDS).id());
                assertEquals(1, executions.get());
                assertEquals("REQUEST_ID_CONFLICT", failure(caller.invokeCommand(workerIdentity.instanceId(), "demo.echo",
                        new JsonPrimitive("different"), 30, requestId)).code());
                List<CompletableFuture<JsonObject>> concurrent = new ArrayList<>();
                for (int i = 0; i < 70; i++) concurrent.add(caller.subscribeEvent("parallel." + i));
                assertTrue(concurrent.stream().anyMatch(CompletableFuture::isCompletedExceptionally));
                for (CompletableFuture<JsonObject> operation : concurrent) {
                    try { operation.get(16, TimeUnit.SECONDS); }
                    catch (ExecutionException error) { assertEquals(CielException.Kind.CAPACITY, Enrollment.unwrap(error).kind()); }
                }

                worker.registerNotification("backup.done", "备份完成", "SDK test").get(5, TimeUnit.SECONDS);
                server.api("PUT", "/api/notification-services/" + workerIdentity.instanceId() + "/preferences",
                        "{\"enabled\":true,\"default_channels\":[\"inbox\"]}");
                String notificationRequest = CielClient.newRequestId();
                JsonObject notification = worker.sendNotification("backup.done", "备份完成", "已保存", notificationRequest).get(5, TimeUnit.SECONDS);
                assertEquals(1, Protocol.integer(notification, "recipient_count"));
                assertEquals("DELIVERED", notification.getAsJsonArray("deliveries").get(0).getAsJsonObject().get("status").getAsString());
                String notificationId = Protocol.string(notification, "id");
                assertEquals(notificationId, Protocol.string(worker.sendNotification("backup.done", "备份完成", "已保存", notificationRequest).get(5, TimeUnit.SECONDS), "id"));
                assertEquals(notificationId, Protocol.string(worker.getNotification(notificationId).get(5, TimeUnit.SECONDS), "id"));
                assertEquals("REQUEST_ID_CONFLICT", failure(worker.sendNotification("backup.done", "changed", "已保存", notificationRequest)).code());

                String delayedRequest = CielClient.newRequestId();
                CielClient.CommandCall delayed = caller.invokeCommand(workerIdentity.instanceId(), "demo.delayed", null, 120, delayedRequest).get(5, TimeUnit.SECONDS);
                assertTrue(delayedStarted.await(5, TimeUnit.SECONDS));
                CielException timeout = failure(caller.awaitCommand(delayed.id(), Duration.ofMillis(50)));
                assertEquals("CLIENT_TIMEOUT", timeout.code());
                assertEquals(delayed.id(), timeout.recordId());
                CompletableFuture<CielClient.CommandCall> waiting = caller.awaitCommand(delayed.id(), Duration.ofSeconds(90));
                states.clear();
                server.stop();
                assertEquals(CielException.Kind.CONNECTION, failure(waiting).kind());
                eventually(() -> worker.state() == CielClient.State.RECONNECTING && caller.state() == CielClient.State.RECONNECTING, 10);
                server.start();
                eventually(() -> worker.state() == CielClient.State.READY && caller.state() == CielClient.State.READY, 20);
                assertTrue(states.contains(CielClient.State.RECONNECTING));
                assertEquals("UNKNOWN", caller.getCommand(delayed.id()).get(5, TimeUnit.SECONDS).status());
                delayedResult.complete(new CielClient.CommandResult(true, new JsonPrimitive("too late")));
                assertEquals("UNKNOWN", caller.getCommand(delayed.id()).get(5, TimeUnit.SECONDS).status());
                assertEquals(delayed.id(), caller.invokeCommand(workerIdentity.instanceId(), "demo.delayed", null, 120, delayedRequest).get(5, TimeUnit.SECONDS).id());
                worker.publishEvent("demo.changed", payload, CielClient.newRequestId()).get(5, TimeUnit.SECONDS);
                assertNotNull(events.poll(5, TimeUnit.SECONDS)); // Relationship survived reconnect without replay.

                long seen = Protocol.integer(server.api("GET", "/api/instances/" + caller.identity().instanceId(), null), "last_seen");
                CountDownLatch callbacksStarted = new CountDownLatch(2);
                CountDownLatch release = new CountDownLatch(1);
                caller.onEvent("demo.changed", message -> {
                    callbacksStarted.countDown();
                    try { release.await(); } catch (InterruptedException ignored) { Thread.currentThread().interrupt(); }
                });
                try {
                    worker.publishEvent("demo.changed", payload, CielClient.newRequestId()).get(5, TimeUnit.SECONDS);
                    worker.publishEvent("demo.changed", payload, CielClient.newRequestId()).get(5, TimeUnit.SECONDS);
                    assertTrue(callbacksStarted.await(5, TimeUnit.SECONDS));
                    eventually(() -> {
                        try { return Protocol.integer(server.api("GET", "/api/instances/" + caller.identity().instanceId(), null), "last_seen") > seen + 5; }
                        catch (Exception error) { throw new RuntimeException(error); }
                    }, 38);
                    assertEquals(CielClient.State.READY, caller.state()); // Both callback threads blocked; heartbeat still works.
                } finally { release.countDown(); }
                caller.unsubscribeEvent("demo.changed").get(5, TimeUnit.SECONDS);
                worker.unregisterEvent("demo.changed").get(5, TimeUnit.SECONDS);
                worker.unregisterCommand("demo.echo").get(5, TimeUnit.SECONDS);
                worker.unregisterCommand("demo.delayed").get(5, TimeUnit.SECONDS);
                worker.unregisterNotification("backup.done").get(5, TimeUnit.SECONDS);
                worker.close();
                assertEquals("NOT_READY", failure(worker.publishEvent("demo.changed", null, CielClient.newRequestId())).code());
            }
            if (rustClient != null) {
                Process peer = new ProcessBuilder(rustClient, "scenario", workerHome.toString(), rustScenario.toString())
                        .redirectErrorStream(true).redirectOutput(directory.resolve("rust-identity.log").toFile()).start();
                try {
                    assertTrue(peer.waitFor(20, TimeUnit.SECONDS));
                    assertEquals(0, peer.exitValue(), "Rust reference client could not use Java identity.json");
                } finally { if (peer.isAlive()) peer.destroyForcibly(); }
            }
            // The server committed, but the application never activated the candidate after its acknowledgement.
            Path recoveryHome = directory.resolve("recover");
            Path recoveryGrant = server.grant("java.recovery");
            JsonObject recovery = IdentityStore.read(recoveryGrant);
            ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
            ExecutorService io = Executors.newFixedThreadPool(2);
            BlockingQueue<JsonObject> messages = new LinkedBlockingQueue<>();
            try (IdentityStore store = new IdentityStore(recoveryHome, true);
                 Wire wire = new Wire(clock, messages::add, error -> { }, Duration.ofSeconds(5))) {
                wire.connect(Protocol.string(recovery, "server"), Protocol.string(recovery, "server_spki_sha256"), io).get(8, TimeUnit.SECONDS);
                JsonObject enroll = Protocol.message("enroll");
                enroll.addProperty("service_id", "java.recovery"); enroll.add("token", recovery.get("enrollment_token"));
                wire.send(enroll).get(5, TimeUnit.SECONDS);
                JsonObject offer = Enrollment.expect(messages.poll(5, TimeUnit.SECONDS), "enrollment_offer");
                CielClient.Identity candidate = new CielClient.Identity(Protocol.string(recovery, "server"), Protocol.string(recovery, "server_spki_sha256"),
                        "java.recovery", Protocol.string(offer, "instance_id"), Protocol.string(offer, "credential"));
                store.save(candidate);
                JsonObject commit = Protocol.message("enroll_commit"); commit.addProperty("credential", candidate.credential());
                wire.send(commit).get(5, TimeUnit.SECONDS);
                Enrollment.expect(messages.poll(5, TimeUnit.SECONDS), "enrolled");
                assertFalse(store.enrolled());
            } finally { clock.shutdownNow(); io.shutdownNow(); }
            recovery.addProperty("enrollment_token", "0".repeat(64)); // Recovery must succeed without a usable token.
            Files.writeString(recoveryGrant, Protocol.JSON.toJson(recovery));
            CielClient.enroll(recoveryGrant, recoveryHome).get(15, TimeUnit.SECONDS);
            assertTrue(Files.exists(recoveryHome.resolve("identity.json")));

            Path invalidHome = directory.resolve("invalid-credential");
            try (IdentityStore store = new IdentityStore(invalidHome, true)) {
                store.activate(store.save(new CielClient.Identity(workerIdentity.server(), workerIdentity.serverSpkiSha256(),
                        workerIdentity.serviceId(), workerIdentity.instanceId(), "0".repeat(64))));
            }
            try (CielClient invalid = new CielClient(invalidHome)) {
                assertEquals("AUTHENTICATION_FAILED", failure(invalid.connect()).code());
                assertEquals(CielClient.State.FAILED, invalid.state());
            }
        }
    }

    static CielException failure(CompletableFuture<?> future) throws Exception {
        return Enrollment.unwrap(assertThrows(ExecutionException.class, () -> future.get(25, TimeUnit.SECONDS)));
    }

    static void eventually(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Condition did not become true before deadline");
            Thread.sleep(100);
        }
    }

    static final class Server implements AutoCloseable {
        final Path binary, directory, data, web;
        final int port;
        final String origin;
        Process process;
        HttpClient http;
        String cookie;

        Server(Path binary, Path directory) throws Exception {
            this.binary = binary.toAbsolutePath(); this.directory = directory;
            assertTrue(Files.isRegularFile(binary), "Ciel binary must exist");
            data = directory.resolve("server-data"); web = directory.resolve("web");
            Files.createDirectories(web); Files.writeString(web.resolve("index.html"), "SDK acceptance test");
            try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) { port = socket.getLocalPort(); }
            origin = "https://localhost:" + port;
            try {
                start();
                JsonObject bootstrap = IdentityStore.read(data.resolve("bootstrap-admin.json"));
                JsonObject credentials = new JsonObject();
                credentials.add("username", bootstrap.get("username")); credentials.add("password", bootstrap.get("password"));
                HttpResponse<String> response = send("POST", "/api/auth/login", Protocol.JSON.toJson(credentials));
                assertEquals(200, response.statusCode());
                cookie = response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            } catch (Throwable error) { close(); throw error; }
        }

        void start() throws Exception {
            ProcessBuilder builder = new ProcessBuilder(binary.toString()).directory(directory.toFile())
                    .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(directory.resolve("server.log").toFile()));
            builder.environment().put("CIEL_DATA_DIR", data.toString());
            builder.environment().put("CIEL_WEB_DIR", web.toString());
            builder.environment().put("CIEL_LISTEN", "127.0.0.1:" + port);
            builder.environment().put("CIEL_ORIGIN", origin);
            process = builder.start();
            eventually(() -> Files.exists(data.resolve("tls/server.crt")), 10);
            X509Certificate certificate;
            try (var stream = Files.newInputStream(data.resolve("tls/server.crt"))) {
                certificate = (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(stream);
            }
            http = HttpClient.newBuilder().sslContext(PinnedTls.context(PinnedTls.pin(certificate)))
                    .connectTimeout(Duration.ofSeconds(2)).build();
            eventually(() -> {
                if (!process.isAlive()) throw new AssertionError("Isolated Ciel exited; inspect server.log");
                try { return send("GET", "/", null).statusCode() == 200; } catch (Exception ignored) { return false; }
            }, 15);
        }

        Path grant(String service) throws Exception {
            JsonObject request = new JsonObject(); request.addProperty("service_id", service);
            JsonObject grant = api("POST", "/api/enrollments", Protocol.JSON.toJson(request));
            Path file = directory.resolve(service + "-grant.json");
            Files.writeString(file, Protocol.JSON.toJson(grant)); IdentityStore.protect(file, false);
            return file;
        }

        JsonObject api(String method, String path, String json) throws Exception {
            HttpResponse<String> response = send(method, path, json);
            assertTrue(response.statusCode() >= 200 && response.statusCode() < 300,
                    () -> "Test administration API failed: " + method + " " + path + " " + response.statusCode());
            return Protocol.object(Protocol.parse(response.body()));
        }

        HttpResponse<String> send(String method, String path, String json) throws Exception {
            HttpRequest.Builder request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(5))
                    .header("Origin", origin).header("X-Ciel-Request", "1");
            if (cookie != null) request.header("Cookie", cookie);
            if (json != null) request.header("Content-Type", "application/json");
            return http.send(request.method(method, json == null ? HttpRequest.BodyPublishers.noBody() :
                    HttpRequest.BodyPublishers.ofString(json)).build(), HttpResponse.BodyHandlers.ofString());
        }

        void stop() throws Exception {
            if (process != null && process.isAlive()) {
                process.destroy();
                if (!process.waitFor(5, TimeUnit.SECONDS)) process.destroyForcibly().waitFor(5, TimeUnit.SECONDS);
            }
        }
        public void close() { try { stop(); } catch (Exception ignored) { } }
    }
}
