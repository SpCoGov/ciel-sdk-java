package top.spco.ciel;

import com.google.gson.JsonObject;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.*;

final class Enrollment {
    static CielClient.Identity enroll(Path grantPath, Path directory) {
        try (IdentityStore store = new IdentityStore(directory, true)) {
            if (store.enrolled()) throw new CielException(CielException.Kind.STORAGE, "ALREADY_ENROLLED");
            JsonObject grant = IdentityStore.read(grantPath);
            String server = Protocol.string(grant, "server");
            String pin = Protocol.hex(Protocol.string(grant, "server_spki_sha256"), 64);
            String service = Protocol.id(Protocol.string(grant, "service_id"));
            String token = Protocol.hex(Protocol.string(grant, "enrollment_token"), 64);
            Protocol.address(server);
            for (Path candidate : store.candidates()) {
                CielClient.Identity identity = IdentityStore.identity(IdentityStore.read(candidate));
                if (!identity.server().equals(server) || !identity.serverSpkiSha256().equals(pin) ||
                        !identity.serviceId().equals(service) ||
                        !candidate.getFileName().toString().equals("candidate-" + identity.instanceId() + ".json"))
                    throw new CielException(CielException.Kind.STORAGE, "CANDIDATE_TARGET_MISMATCH");
                try (Handshake handshake = new Handshake(server, pin)) {
                    JsonObject response = handshake.exchange(authenticate(identity));
                    if (Protocol.string(response, "type").equals("error")) {
                        CielException error = Protocol.serverError(response);
                        if (!error.code().equals("AUTHENTICATION_FAILED")) throw error;
                    } else {
                        authenticated(response);
                        store.activate(candidate);
                        return identity;
                    }
                }
            }
            try (Handshake handshake = new Handshake(server, pin)) {
                JsonObject request = Protocol.message("enroll");
                request.addProperty("service_id", service);
                request.addProperty("token", token);
                JsonObject offer = expect(handshake.exchange(request), "enrollment_offer");
                if (!Protocol.string(offer, "service_id").equals(service)) throw Protocol.bad();
                CielClient.Identity identity = new CielClient.Identity(server, pin, service,
                        Protocol.string(offer, "instance_id"), Protocol.string(offer, "credential"));
                Path candidate = store.save(identity);
                JsonObject commit = Protocol.message("enroll_commit");
                commit.addProperty("credential", identity.credential());
                JsonObject enrolled = expect(handshake.exchange(commit), "enrolled");
                if (!Protocol.string(enrolled, "service_id").equals(service) ||
                        !Protocol.string(enrolled, "instance_id").equals(identity.instanceId())) throw Protocol.bad();
                store.activate(candidate);
                return identity;
            }
        }
    }

    static JsonObject authenticate(CielClient.Identity identity) {
        JsonObject request = Protocol.message("authenticate");
        request.addProperty("service_id", identity.serviceId());
        request.addProperty("instance_id", identity.instanceId());
        request.addProperty("credential", identity.credential());
        return request;
    }

    static JsonObject expect(JsonObject response, String type) {
        if (Protocol.string(response, "type").equals("error")) throw Protocol.serverError(response);
        if (!Protocol.string(response, "type").equals(type)) throw Protocol.bad();
        return response;
    }

    static void authenticated(JsonObject response) {
        expect(response, "authenticated");
        Protocol.hex(Protocol.string(response, "session_id"), 32);
        long interval = Protocol.integer(response, "heartbeat_interval_seconds");
        long timeout = Protocol.integer(response, "heartbeat_timeout_seconds");
        if (interval < 1 || interval > 3600 || timeout <= interval || timeout > 86400) throw Protocol.bad();
    }

    private static final class Handshake implements AutoCloseable {
        private final BlockingQueue<Object> received = new ArrayBlockingQueue<>(64);
        private final ScheduledExecutorService clock = Executors.newSingleThreadScheduledExecutor(CielClient.threads("enroll-clock"));
        private final ExecutorService network = Executors.newFixedThreadPool(2, CielClient.threads("enroll-io"));
        private final Wire wire = new Wire(clock,
                message -> { if (!received.offer(message)) throw new CielException(CielException.Kind.CAPACITY, "RECEIVE_QUEUE_FULL"); },
                error -> received.offer(error), Duration.ofSeconds(10));

        Handshake(String server, String pin) {
            try { wire.connect(server, pin, network).get(15, TimeUnit.SECONDS); }
            catch (Exception error) {
                close();
                if (error instanceof InterruptedException) Thread.currentThread().interrupt();
                throw unwrap(error);
            }
        }

        JsonObject exchange(JsonObject request) {
            try {
                wire.send(request).get(10, TimeUnit.SECONDS);
                Object value = received.poll(15, TimeUnit.SECONDS);
                if (value == null) throw new CielException(CielException.Kind.TIMEOUT, "ENROLLMENT_TIMEOUT");
                if (value instanceof CielException error) throw error;
                return (JsonObject) value;
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CielException(CielException.Kind.CONNECTION, "ENROLLMENT_INTERRUPTED");
            } catch (ExecutionException | TimeoutException error) { throw unwrap(error); }
        }

        @Override public void close() { wire.close(); clock.shutdownNow(); network.shutdownNow(); }
    }

    static CielException unwrap(Throwable error) {
        while ((error instanceof CompletionException || error instanceof ExecutionException) && error.getCause() != null)
            error = error.getCause();
        if (error instanceof CielException ciel) return ciel;
        return new CielException(error instanceof TimeoutException ? CielException.Kind.TIMEOUT : CielException.Kind.CONNECTION,
                error instanceof TimeoutException ? "CLIENT_TIMEOUT" : "CONNECTION_FAILED", error);
    }
}
