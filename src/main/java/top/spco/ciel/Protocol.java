package top.spco.ciel;

import com.google.gson.*;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.*;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

final class Protocol {
    static final Gson JSON = new GsonBuilder().serializeNulls().disableHtmlEscaping()
            .setStrictness(Strictness.STRICT).disableJdkUnsafe().create();

    static CielException input(String code) { return new CielException(CielException.Kind.INPUT, code); }
    static CielException bad() { return new CielException(CielException.Kind.PROTOCOL, "INVALID_RESPONSE"); }

    static String id(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{3,64}")) throw input("INVALID_ID");
        return value;
    }

    static String hex(String value, int length) {
        if (value == null || !value.matches("[0-9a-f]{" + length + "}")) throw input("INVALID_IDENTITY");
        return value;
    }

    static URI address(String server) {
        try {
            URI uri = URI.create(server);
            if (!"wss".equals(uri.getScheme()) || uri.getHost() == null ||
                    !"/ws/service".equals(uri.getRawPath()) || uri.getRawQuery() != null ||
                    uri.getRawFragment() != null || uri.getRawUserInfo() != null ||
                    uri.getPort() == 0 || uri.getPort() > 65535) throw input("INVALID_SERVER_ADDRESS");
            return uri;
        } catch (IllegalArgumentException | NullPointerException error) {
            throw input("INVALID_SERVER_ADDRESS");
        }
    }

    static String text(String value, int limit, boolean nonblank) {
        if (value == null || bytes(value) > limit || (nonblank && value.isBlank())) throw input("INVALID_TEXT");
        // Reject unpaired surrogates rather than silently replacing them in UTF-8.
        for (int i = 0; i < value.length(); i++) {
            char ch = value.charAt(i);
            if (Character.isHighSurrogate(ch)) {
                if (++i >= value.length() || !Character.isLowSurrogate(value.charAt(i))) throw input("INVALID_TEXT");
            } else if (Character.isLowSurrogate(ch)) throw input("INVALID_TEXT");
        }
        return value;
    }

    static int bytes(String value) { return value.getBytes(StandardCharsets.UTF_8).length; }
    static String requestId() { return UUID.randomUUID().toString().replace("-", ""); }

    static JsonObject message(String type) {
        JsonObject value = new JsonObject();
        value.addProperty("v", 1);
        value.addProperty("type", type);
        return value;
    }

    static JsonElement payload(JsonElement value) {
        JsonElement copy = value == null ? JsonNull.INSTANCE : value.deepCopy();
        String encoded;
        try { encoded = JSON.toJson(copy); }
        catch (IllegalArgumentException error) { throw input("INVALID_PAYLOAD"); }
        if (bytes(encoded) > 2048) throw input("PAYLOAD_TOO_LARGE");
        text(encoded, 2048, false);
        // Also rejects non-finite numbers and excessive nesting.
        try { parse(encoded); }
        catch (CielException error) { throw input("INVALID_PAYLOAD"); }
        return copy;
    }

    static String encode(JsonObject message) {
        String encoded = JSON.toJson(message);
        text(encoded, 4096, false);
        return encoded;
    }

    static JsonElement parse(String text) {
        if (bytes(text) > 32768) throw bad();
        try (JsonReader reader = new JsonReader(new StringReader(text))) {
            reader.setStrictness(Strictness.STRICT);
            reader.setNestingLimit(64);
            JsonElement value = read(reader);
            if (reader.peek() != JsonToken.END_DOCUMENT) throw bad();
            return value;
        } catch (IOException | IllegalStateException | NumberFormatException | CielException error) {
            throw bad(); // Parser diagnostics can include secret fragments.
        }
    }

    private static JsonElement read(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject value = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String name = text(reader.nextName(), 32768, false);
                    if (value.has(name)) throw bad();
                    value.add(name, read(reader));
                }
                reader.endObject();
                yield value;
            }
            case BEGIN_ARRAY -> {
                JsonArray value = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) value.add(read(reader));
                reader.endArray();
                yield value;
            }
            case STRING -> new JsonPrimitive(text(reader.nextString(), 32768, false));
            case NUMBER -> JsonParser.parseString(reader.nextString());
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw bad();
        };
    }

    static JsonObject object(JsonElement value) {
        if (value == null || !value.isJsonObject()) throw bad();
        return value.getAsJsonObject();
    }

    static JsonObject response(String text) {
        JsonObject value = object(parse(text));
        if (integer(value, "v") != 1) throw new CielException(CielException.Kind.PROTOCOL, "UNSUPPORTED_VERSION");
        string(value, "type");
        return value;
    }

    static String string(JsonObject value, String key) {
        JsonElement item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isString()) throw bad();
        return item.getAsString();
    }

    static long integer(JsonObject value, String key) {
        JsonElement item = value.get(key);
        if (item == null || !item.isJsonPrimitive() || !item.getAsJsonPrimitive().isNumber() ||
                !item.getAsString().matches("-?(0|[1-9][0-9]*)")) throw bad();
        try { return new BigInteger(item.getAsString()).longValueExact(); }
        catch (ArithmeticException error) { throw bad(); }
    }

    static void require(JsonObject value, String... fields) {
        for (String field : fields) if (!value.has(field)) throw bad();
    }

    static CielException serverError(JsonObject message) {
        String code = string(message, "code");
        if (!code.matches("[A-Z][A-Z0-9_]{0,63}")) throw bad();
        return new CielException(CielException.Kind.SERVER, code);
    }

    static void duration(Duration value) {
        if (value == null || value.isNegative() || value.toMillis() < 1 || value.compareTo(Duration.ofDays(1)) > 0)
            throw input("INVALID_TIMEOUT");
    }
}
