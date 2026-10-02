package top.spco.ciel;

import com.google.gson.JsonObject;
import javax.net.ssl.*;
import java.net.http.*;
import java.nio.ByteBuffer;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.Consumer;

final class Wire implements WebSocket.Listener, AutoCloseable {
    private record Outbound(String text, AtomicBoolean attempted, CompletableFuture<Void> result) { }
    private final ScheduledExecutorService clock;
    private final Consumer<JsonObject> messages;
    private final Consumer<CielException> errors;
    private final ArrayBlockingQueue<Outbound> outgoing = new ArrayBlockingQueue<>(64);
    private final AtomicReference<Outbound> heartbeat = new AtomicReference<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final CompletableFuture<WebSocket> connected = new CompletableFuture<>();
    private final StringBuilder fragments = new StringBuilder();
    private final Duration timeout;
    private final ScheduledFuture<?> pump;
    private volatile WebSocket socket;
    private volatile boolean writing;
    private volatile HttpClient http;
    private volatile long retryAfter;
    private CompletableFuture<WebSocket> opening;

    Wire(ScheduledExecutorService clock, Consumer<JsonObject> messages, Consumer<CielException> errors, Duration timeout) {
        this.clock = clock;
        this.messages = messages;
        this.errors = errors;
        this.timeout = timeout;
        // Conservative 5 messages/second, including priority heartbeats. No optional pings.
        pump = clock.scheduleWithFixedDelay(this::pump, 0, 200, TimeUnit.MILLISECONDS);
    }

    CompletableFuture<WebSocket> connect(String server, String pin, Executor executor) {
        try {
            SSLParameters tls = new SSLParameters();
            tls.setProtocols(new String[] {"TLSv1.3"});
            http = HttpClient.newBuilder().sslContext(PinnedTls.context(pin)).sslParameters(tls)
                    .executor(executor).connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
            opening = http.newWebSocketBuilder().connectTimeout(timeout)
                    .buildAsync(Protocol.address(server), this);
            opening.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS).whenComplete((ws, error) -> {
                if (error != null) fail(classify(error));
                else if (closed.get()) ws.abort();
            });
        } catch (RuntimeException error) { fail(classify(error)); }
        return connected;
    }

    CompletableFuture<Void> send(JsonObject value, AtomicBoolean attempted) {
        Outbound item = new Outbound(Protocol.encode(value), attempted, new CompletableFuture<>());
        item.result.whenComplete((ignored, error) -> {
            if (item.result.isCancelled()) { outgoing.remove(item); heartbeat.compareAndSet(item, null); }
        });
        if (closed.get()) item.result.completeExceptionally(new CielException(CielException.Kind.CONNECTION, "CONNECTION_CLOSED"));
        else if (Protocol.string(value, "type").equals("heartbeat")) {
            if (!heartbeat.compareAndSet(null, item)) item.result.completeExceptionally(new CielException(CielException.Kind.CAPACITY, "HEARTBEAT_PENDING"));
        } else if (!outgoing.offer(item)) item.result.completeExceptionally(new CielException(CielException.Kind.CAPACITY, "SEND_QUEUE_FULL"));
        return item.result;
    }

    CompletableFuture<Void> send(JsonObject value) { return send(value, new AtomicBoolean()); }
    long retryAfterSeconds() { return retryAfter; }

    private void pump() {
        if (closed.get() || socket == null || writing) return;
        Outbound item = heartbeat.getAndSet(null);
        if (item == null) item = outgoing.poll();
        if (item == null || item.result.isDone()) return;
        Outbound current = item;
        writing = true;
        try {
            current.attempted.set(true);
            socket.sendText(current.text, true).orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((ignored, error) -> {
                        writing = false;
                        if (error == null) current.result.complete(null);
                        else {
                            CielException failure = classify(error);
                            current.result.completeExceptionally(failure);
                            fail(failure);
                        }
                    });
        } catch (RuntimeException error) {
            writing = false;
            CielException failure = classify(error);
            current.result.completeExceptionally(failure);
            fail(failure);
        }
    }

    @Override public void onOpen(WebSocket socket) {
        this.socket = socket;
        if (closed.get()) socket.abort();
        else { connected.complete(socket); socket.request(1); }
    }

    @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
        try {
            if (fragments.length() + data.length() > 32768) throw Protocol.bad();
            fragments.append(data);
            if (Protocol.bytes(fragments.toString()) > 32768) throw Protocol.bad();
            if (last) {
                JsonObject message = Protocol.response(fragments.toString());
                fragments.setLength(0);
                if (!closed.get()) messages.accept(message);
            }
        } catch (CielException error) { fail(error.kind() == CielException.Kind.INPUT ? Protocol.bad() : error); }
        catch (RuntimeException error) { fail(Protocol.bad()); }
        if (!closed.get()) socket.request(1);
        return null;
    }

    @Override public CompletionStage<?> onBinary(WebSocket socket, ByteBuffer data, boolean last) {
        fail(Protocol.bad());
        return null;
    }

    @Override public CompletionStage<?> onPing(WebSocket socket, ByteBuffer message) { socket.request(1); return null; }
    @Override public CompletionStage<?> onPong(WebSocket socket, ByteBuffer message) { socket.request(1); return null; }
    @Override public CompletionStage<?> onClose(WebSocket socket, int status, String reason) {
        fail(new CielException(CielException.Kind.CONNECTION, "CONNECTION_CLOSED"));
        return null;
    }
    @Override public void onError(WebSocket socket, Throwable error) { fail(classify(error)); }

    private CielException classify(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        if (error instanceof CielException ciel) return ciel;
        if (error instanceof WebSocketHandshakeException handshake) {
            int status = handshake.getResponse().statusCode();
            handshake.getResponse().headers().firstValue("Retry-After").ifPresent(value -> {
                try { retryAfter = Math.max(0, Long.parseLong(value)); }
                catch (NumberFormatException ignored) {
                    try { retryAfter = Math.max(0, Duration.between(Instant.now(), ZonedDateTime.parse(value,
                            DateTimeFormatter.RFC_1123_DATE_TIME).toInstant()).getSeconds()); }
                    catch (RuntimeException invalid) { retryAfter = 60; }
                }
                retryAfter = Math.min(retryAfter, 86400);
            });
            return new CielException(status == 429 || status >= 500 ? CielException.Kind.CONNECTION : CielException.Kind.SERVER,
                    "HTTP_" + status);
        }
        for (Throwable current = error; current != null; current = current.getCause())
            if (current instanceof SSLException || current instanceof java.security.cert.CertificateException)
                return new CielException(CielException.Kind.TLS, "TLS_VERIFICATION_FAILED", error);
        return new CielException(error instanceof TimeoutException ? CielException.Kind.TIMEOUT : CielException.Kind.CONNECTION,
                error instanceof TimeoutException ? "CONNECTION_TIMEOUT" : "CONNECTION_FAILED", error);
    }

    private void fail(CielException error) {
        if (closed.compareAndSet(false, true)) {
            shutdown(error);
            errors.accept(error);
        }
    }

    private void shutdown(CielException error) {
        pump.cancel(false);
        if (opening != null && !opening.isDone()) opening.cancel(true);
        if (socket != null) socket.abort();
        connected.completeExceptionally(error);
        Outbound priority = heartbeat.getAndSet(null);
        if (priority != null) priority.result.completeExceptionally(error);
        Outbound item;
        while ((item = outgoing.poll()) != null) item.result.completeExceptionally(error);
        http = null; // Java 17 has no HttpClient.close; aborted sockets release active network resources.
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) shutdown(new CielException(CielException.Kind.CLOSED, "CLIENT_CLOSED"));
    }
}
