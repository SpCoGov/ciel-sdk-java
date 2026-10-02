package top.spco.ciel;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

final class IdentityStore implements AutoCloseable {
    private final Path directory;
    private final FileChannel channel;
    private final FileLock lock;

    IdentityStore(Path directory, boolean create) {
        this.directory = directory.toAbsolutePath().normalize();
        FileChannel opened = null;
        try {
            if (create) Files.createDirectories(this.directory);
            if (!Files.isDirectory(this.directory, LinkOption.NOFOLLOW_LINKS)) throw new IOException("INVALID_IDENTITY_DIRECTORY");
            protect(this.directory, true);
            Path lockPath = this.directory.resolve("agent.lock");
            if (Files.isSymbolicLink(lockPath)) throw new IOException("INVALID_IDENTITY_LOCK");
            opened = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
            protect(lockPath, false);
            FileLock acquired = opened.tryLock();
            if (acquired == null) throw new CielException(CielException.Kind.STORAGE, "IDENTITY_IN_USE");
            this.channel = opened;
            this.lock = acquired;
        } catch (IOException | RuntimeException error) {
            if (opened != null) try { opened.close(); } catch (IOException ignored) { }
            if (error instanceof CielException ciel) throw ciel;
            if (error instanceof OverlappingFileLockException)
                throw new CielException(CielException.Kind.STORAGE, "IDENTITY_IN_USE");
            throw new CielException(CielException.Kind.STORAGE, "IDENTITY_STORAGE_FAILED", error);
        }
    }

    static void protect(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString(directory ? "rwx------" : "rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("PRIVATE_PERMISSIONS_UNSUPPORTED");
        String user = System.getProperty("user.name");
        String domain = System.getenv("USERDOMAIN");
        UserPrincipal principal = path.getFileSystem().getUserPrincipalLookupService()
                .lookupPrincipalByName(domain == null ? user : domain + "\\" + user);
        AclEntry.Builder rule = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(principal)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) rule.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(rule.build()));
    }

    static JsonObject read(Path path) {
        try {
            if (Files.isSymbolicLink(path)) throw new IOException("SYMLINK_IDENTITY_REJECTED");
            // The channel bound also protects against a file growing after a size check.
            try (FileChannel file = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
                if (file.size() > 16384) throw new IOException("IDENTITY_FILE_TOO_LARGE");
                ByteBuffer buffer = ByteBuffer.allocate(16385);
                while (file.read(buffer) > 0 && buffer.hasRemaining()) { }
                if (buffer.position() > 16384) throw new IOException("IDENTITY_FILE_TOO_LARGE");
                buffer.flip();
                String json = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(buffer).toString();
                return Protocol.object(Protocol.parse(json));
            }
        } catch (IOException | RuntimeException error) {
            throw new CielException(CielException.Kind.STORAGE, "INVALID_IDENTITY_FILE");
        }
    }

    static CielClient.Identity identity(JsonObject value) {
        return new CielClient.Identity(Protocol.string(value, "server"), Protocol.string(value, "server_spki_sha256"),
                Protocol.string(value, "service_id"), Protocol.string(value, "instance_id"), Protocol.string(value, "credential"));
    }

    boolean enrolled() { return Files.exists(directory.resolve("identity.json"), LinkOption.NOFOLLOW_LINKS); }
    CielClient.Identity load() {
        Path file = directory.resolve("identity.json");
        try {
            if (Files.isSymbolicLink(file)) throw new IOException("SYMLINK_IDENTITY_REJECTED");
            protect(file, false);
            return identity(read(file));
        } catch (IOException error) { throw new CielException(CielException.Kind.STORAGE, "INVALID_IDENTITY_FILE"); }
    }

    List<Path> candidates() {
        try (var paths = Files.list(directory)) {
            return paths.filter(path -> path.getFileName().toString().matches("candidate-[0-9a-f]{32}\\.json"))
                    .sorted().toList();
        } catch (IOException error) { throw new CielException(CielException.Kind.STORAGE, "IDENTITY_STORAGE_FAILED", error); }
    }

    Path save(CielClient.Identity identity) {
        JsonObject value = new JsonObject();
        value.addProperty("server", identity.server());
        value.addProperty("server_spki_sha256", identity.serverSpkiSha256());
        value.addProperty("service_id", identity.serviceId());
        value.addProperty("instance_id", identity.instanceId());
        value.addProperty("credential", identity.credential());
        Path temporary = directory.resolve("pending-" + Protocol.requestId() + ".tmp");
        Path candidate = directory.resolve("candidate-" + identity.instanceId() + ".json");
        try {
            if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) throw new IOException("CANDIDATE_EXISTS");
            try (FileChannel file = FileChannel.open(temporary, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                protect(temporary, false);
                ByteBuffer bytes = java.nio.charset.StandardCharsets.UTF_8.encode(Protocol.JSON.toJson(value));
                while (bytes.hasRemaining()) file.write(bytes);
                file.force(true);
            }
            Files.move(temporary, candidate, StandardCopyOption.ATOMIC_MOVE);
            syncDirectory();
            return candidate;
        } catch (IOException error) { throw new CielException(CielException.Kind.STORAGE, "IDENTITY_SAVE_FAILED", error); }
    }

    void activate(Path candidate) {
        try {
            if (enrolled()) throw new IOException("ALREADY_ENROLLED");
            Files.move(candidate, directory.resolve("identity.json"), StandardCopyOption.ATOMIC_MOVE);
            syncDirectory();
        } catch (IOException error) { throw new CielException(CielException.Kind.STORAGE, "IDENTITY_ACTIVATION_FAILED", error); }
    }

    private void syncDirectory() throws IOException {
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class) != null)
            try (FileChannel file = FileChannel.open(directory, StandardOpenOption.READ)) { file.force(true); }
    }

    @Override public void close() {
        try { lock.release(); } catch (IOException ignored) { }
        try { channel.close(); } catch (IOException ignored) { }
    }
}
