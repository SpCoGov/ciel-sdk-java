package top.spco.ciel;

import com.google.gson.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class SdkTest {
    @TempDir Path directory;

    @Test void strictProtocolAndUtf8Limits() {
        for (String invalid : List.of("{\"v\":1,\"v\":1,\"type\":\"x\"}",
                "{\"v\":1,\"type\":\"x\"} {}", "{v:1,type:'x'}", "{\"v\":1.0,\"type\":\"x\"}",
                "{\"v\":\"1\",\"type\":\"x\"}", "{\"v\":2,\"type\":\"x\"}",
                "{\"v\":1,\"type\":false}", "{\"v\":1,\"type\":\"x\",\"data\":NaN}",
                "{\"v\":1,\"type\":\"\\ud800\"}",
                "[".repeat(65) + "0" + "]".repeat(65)))
            assertThrows(CielException.class, () -> Protocol.response(invalid));
        JsonObject response = Protocol.response("{\"v\":1,\"type\":\"event\",\"publication_id\":9007199254740993}");
        assertEquals(9007199254740993L, Protocol.integer(response, "publication_id"));
        assertThrows(CielException.class, () -> Protocol.integer(Protocol.object(Protocol.parse("{\"n\":9223372036854775808}")), "n"));
        assertDoesNotThrow(() -> Protocol.payload(new JsonPrimitive("x".repeat(2046))));
        assertThrows(CielException.class, () -> Protocol.payload(new JsonPrimitive("中".repeat(683))));
        assertThrows(CielException.class, () -> Protocol.payload(new JsonPrimitive(Double.NaN)));
        assertThrows(CielException.class, () -> Protocol.payload(new JsonPrimitive("\ud800")));
        assertThrows(CielException.class, () -> Protocol.text("\udc00", 100, false));
        assertEquals(JsonNull.INSTANCE, Protocol.payload(null));
        for (String address : List.of("ws://localhost/ws/service", "wss://u:p@localhost/ws/service",
                "wss://localhost/ws/service?token=x", "wss://localhost:0/ws/service", "wss://localhost/ws/service#x"))
            assertThrows(CielException.class, () -> Protocol.address(address));
        assertThrows(CielException.class, () -> Protocol.id("中文"));
        assertThrows(CielException.class, () -> Protocol.id("a"));
        assertThrows(CielException.class, () -> Protocol.duration(Duration.ofNanos(1)));
    }

    @Test void identityDurabilityLockPermissionsAndRedaction() throws Exception {
        CielClient.Identity identity = new CielClient.Identity("wss://localhost:9443/ws/service", "a".repeat(64),
                "test.worker", "b".repeat(32), "c".repeat(64));
        Path home = directory.resolve("agent");
        try (IdentityStore store = new IdentityStore(home, true)) {
            CielException busy = assertThrows(CielException.class, () -> new IdentityStore(home, false));
            assertEquals("IDENTITY_IN_USE", busy.code());
            Path candidate = store.save(identity);
            assertFalse(store.enrolled());
            assertEquals(identity, IdentityStore.identity(IdentityStore.read(candidate)));
            assertFalse(IdentityStore.read(candidate).has("enrollment_token"));
            store.activate(candidate);
            assertEquals(identity, store.load());
            assertTrue(store.candidates().isEmpty());
        }
        try (IdentityStore store = new IdentityStore(home, false)) { assertEquals(identity, store.load()); }
        Path file = home.resolve("identity.json");
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class);
        if (posix != null) assertEquals(PosixFilePermissions.fromString("rw-------"), posix.readAttributes().permissions());
        else {
            List<AclEntry> acl = Files.getFileAttributeView(file, AclFileAttributeView.class).getAcl();
            assertEquals(1, acl.size());
            assertEquals(AclEntryType.ALLOW, acl.get(0).type());
            assertTrue(acl.get(0).principal().getName().endsWith(System.getProperty("user.name")));
        }
        assertFalse(identity.toString().contains(identity.credential()));
        assertFalse(new CielClient.CommandResult(true, new JsonPrimitive("secret-content")).toString().contains("secret-content"));
        Files.writeString(file, "{\"credential\":\"secret-content\",\"credential\":\"x\"}");
        CielException invalid = assertThrows(CielException.class, () -> IdentityStore.read(file));
        assertFalse(invalid.toString().contains("secret-content"));
        assertNull(invalid.getCause());
        assertThrows(CielException.class, () -> new CielClient(home));
        try (IdentityStore unlocked = new IdentityStore(home, false)) { assertTrue(unlocked.enrolled()); }
    }

    @Test void boundedTransportFragmentationPriorityCancellationAndBinaryRejection() throws Exception {
        ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor();
        CountDownLatch start = new CountDownLatch(1);
        clock.execute(() -> { try { start.await(); } catch (InterruptedException ignored) { } });
        BlockingQueue<JsonObject> received = new LinkedBlockingQueue<>();
        BlockingQueue<CielException> errors = new LinkedBlockingQueue<>();
        FakeSocket socket = new FakeSocket();
        try (Wire wire = new Wire(clock, received::add, errors::add, Duration.ofSeconds(1))) {
            wire.onOpen(socket);
            AtomicBoolean cancelledAttempt = new AtomicBoolean();
            CompletableFuture<Void> cancelled = wire.send(Protocol.message("event_register"), cancelledAttempt);
            cancelled.cancel(false);
            CompletableFuture<Void> normal = wire.send(Protocol.message("event_subscribe"));
            CompletableFuture<Void> heartbeat = wire.send(Protocol.message("heartbeat"));
            for (int i = 0; i < 63; i++) wire.send(Protocol.message("event_register"));
            assertEquals("SEND_QUEUE_FULL", Enrollment.unwrap(assertThrows(ExecutionException.class,
                    () -> wire.send(Protocol.message("event_register")).get())).code());
            start.countDown();
            heartbeat.get(2, TimeUnit.SECONDS);
            assertEquals("heartbeat", Protocol.string(Protocol.response(socket.sent.poll(1, TimeUnit.SECONDS)), "type"));
            normal.get(2, TimeUnit.SECONDS);
            assertFalse(cancelledAttempt.get());
            wire.onText(socket, "{\"v\":1,\"type\":", false);
            wire.onPing(socket, ByteBuffer.allocate(0));
            assertTrue(received.isEmpty());
            wire.onText(socket, "\"heartbeat_ack\",\"server_time\":1}", true);
            assertEquals("heartbeat_ack", Protocol.string(received.poll(1, TimeUnit.SECONDS), "type"));
            wire.onBinary(socket, ByteBuffer.wrap(new byte[] {1}), true);
            assertEquals(CielException.Kind.PROTOCOL, errors.poll(1, TimeUnit.SECONDS).kind());
            assertTrue(socket.aborted);
        } finally { start.countDown(); clock.shutdownNow(); }
    }

    static final class FakeSocket implements WebSocket {
        final BlockingQueue<String> sent = new LinkedBlockingQueue<>();
        volatile boolean aborted;
        public CompletableFuture<WebSocket> sendText(CharSequence text, boolean last) { sent.add(text.toString()); return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendBinary(ByteBuffer data, boolean last) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPing(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendPong(ByteBuffer message) { return CompletableFuture.completedFuture(this); }
        public CompletableFuture<WebSocket> sendClose(int status, String reason) { return CompletableFuture.completedFuture(this); }
        public void request(long count) { }
        public String getSubprotocol() { return ""; }
        public boolean isOutputClosed() { return aborted; }
        public boolean isInputClosed() { return aborted; }
        public void abort() { aborted = true; }
    }
}
