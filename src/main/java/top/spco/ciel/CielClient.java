package top.spco.ciel;

import com.google.gson.*;
import java.nio.file.Path;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.*;

/**
 * 通过固定 SPKI Pin 的 TLS 1.3 WebSocket 接入 Ciel v1 的线程安全客户端。
 *
 * <p>先使用 {@link #enroll(Path, Path)} 注册身份，再创建客户端、安装监听器和命令处理函数，
 * 最后调用 {@link #connect()}。业务请求仅在 {@link State#READY} 时发送；客户端负责应用心跳
 * 和临时故障重连，但不会自动重放业务请求。实例使用完毕后必须调用 {@link #close()} 释放身份锁。
 *
 * <p>事件 ID、命令 ID、通知类型和请求 ID 长度均为 3 至 64 个字符，只允许 ASCII 字母、数字、
 * 点、下划线和连字符。实例 ID、调用 ID、通知 ID 为 32 位小写十六进制字符串。
 * 所有 JSON 大小限制以序列化后的 UTF-8 字节数计算：业务 JSON 不超过 2048 字节，
 * 完整发送消息不超过 4096 字节。
 *
 * <p>输入校验错误同步抛出 {@link CielException}。网络、容量、超时和服务端错误通过返回的
 * {@link CompletableFuture} 异常完成，阻塞等待时可从 {@link ExecutionException#getCause()}
 * 读取错误。取消 Future 只取消本地等待并尝试移除排队发送，不保证撤销已发送的操作。
 * 需要恢复命令或通知时，应在发送前保存请求 ID 和完整参数。
 *
 * <p>监听器由有界回调线程池分发，命令处理函数由独立的有界执行线程池运行；回调可能并发，
 * 应自行保护共享数据，并避免长期阻塞。关闭时的状态通知有同步执行的特殊规则，见
 * {@link #onState(Consumer)}。
 *
 * @see CielException
 */
public final class CielClient implements AutoCloseable {
    /** 客户端连接生命周期；当前值可通过 {@link CielClient#state()} 读取。 */
    public enum State {
        /** 已加载身份，尚未开始连接。 */
        DISCONNECTED,
        /** 正在建立 TCP、TLS 和 WebSocket 连接。 */
        CONNECTING,
        /** WebSocket 已建立，正在验证服务身份。 */
        AUTHENTICATING,
        /** 身份已通过验证，可发送业务请求。 */
        READY,
        /** 当前连接已失效，正在等待退避后重新连接。 */
        RECONNECTING,
        /** 遇到不可恢复错误，已停止重连；应关闭实例后重新创建客户端。 */
        FAILED,
        /** 已关闭并释放身份锁，不能重新连接。 */
        CLOSED
    }

    /**
     * 持久服务身份，与 Rust 参考客户端的 {@code identity.json} 字段兼容。
     *
     * <p>凭据是未加密的秘密。{@link #toString()} 仅包含服务 ID 和实例 ID，
     * 调用方仍须避免记录 {@code credential()} 的返回值或完整身份文件。
     *
     * @param server {@code wss://host[:port]/ws/service} 地址，不允许用户信息、查询参数或片段
     * @param serverSpkiSha256 服务端证书 DER SPKI 的 SHA-256，64 位小写十六进制
     * @param serviceId 服务 ID，符合客户端的 ID 格式规则
     * @param instanceId 实例 ID，32 位小写十六进制
     * @param credential 服务凭据，64 位小写十六进制秘密
     */
    public record Identity(String server, String serverSpkiSha256, String serviceId, String instanceId, String credential) {
        /**
         * 校验身份字段并构造不可变身份。
         *
         * @param server 固定路径的 WSS 服务地址
         * @param serverSpkiSha256 64 位小写十六进制 SPKI Pin
         * @param serviceId 服务 ID
         * @param instanceId 32 位小写十六进制实例 ID
         * @param credential 64 位小写十六进制服务凭据
         * @throws CielException 地址、ID 或凭据格式无效时
         */
        public Identity {
            Protocol.address(server); Protocol.hex(serverSpkiSha256, 64);
            Protocol.id(serviceId); Protocol.hex(instanceId, 32); Protocol.hex(credential, 64);
        }
        /**
         * 生成脱敏身份说明。
         * @return 仅包含服务 ID 和实例 ID 的说明，不包含凭据和 Pin
         */
        @Override public String toString() { return "Identity[serviceId=" + serviceId + ", instanceId=" + instanceId + "]"; }
    }

    /**
     * 客户端连接、等待和重连配置，不改变服务器公布的心跳间隔与截止时间。
     *
     * <p>每个时长必须至少为 1 毫秒、至多为 1 天。最大重连延迟限制退避的基础值；
     * 随机抖动、最小调度间隔和服务器的 {@code Retry-After} 可能使实际等待更长。
     *
     * @param connectTimeout 建立连接、等待鉴权和 WebSocket 写入的超时
     * @param requestTimeout 从业务请求登记开始计算的等待超时，包含本地排队时间
     * @param heartbeatAckTimeout 心跳写入后等待应用层确认的超时
     * @param maxReconnectDelay 重连退避基础间隔的上限
     */
    public record Options(Duration connectTimeout, Duration requestTimeout, Duration heartbeatAckTimeout, Duration maxReconnectDelay) {
        /**
         * 校验四个时长并构造配置。
         *
         * @param connectTimeout 连接、鉴权和写入超时
         * @param requestTimeout 业务请求超时
         * @param heartbeatAckTimeout 心跳确认超时
         * @param maxReconnectDelay 基础重连间隔上限
         * @throws CielException 任一时长为空、低于 1 毫秒或超过 1 天时
         * @throws ArithmeticException 时长过大而不能换算为毫秒时
         */
        public Options {
            Protocol.duration(connectTimeout); Protocol.duration(requestTimeout);
            Protocol.duration(heartbeatAckTimeout); Protocol.duration(maxReconnectDelay);
        }
        /**
         * 返回默认配置。
         *
         * @return 连接 10 秒、请求 15 秒、心跳确认 10 秒、最大基础重连间隔 30 秒的配置
         */
        public static Options defaults() {
            return new Options(Duration.ofSeconds(10), Duration.ofSeconds(15), Duration.ofSeconds(10), Duration.ofSeconds(30));
        }
    }

    /**
     * 命令处理函数返回的执行结果；成功标志与 JSON 输出相互独立。
     *
     * @param success 是否执行成功；{@code false} 会使服务端记录进入 {@code FAILED} 状态
     * @param output 输出 JSON，Java {@code null} 转为 JSON null，序列化后不超过 2048 字节
     */
    public record CommandResult(boolean success, JsonElement output) {
        /**
         * 校验并深拷贝输出，后续修改传入 JSON 不会改变结果。
         *
         * @param success 执行成功标志
         * @param output 输出 JSON，可为 Java {@code null}
         * @throws CielException JSON 无效、嵌套过深或超过 2048 UTF-8 字节时
         */
        public CommandResult { output = Protocol.payload(output); }
        /**
         * 读取输出，不暴露结果内部的可变 JSON。
         * @return 输出 JSON 的深拷贝，JSON null 也以非空 {@link JsonElement} 返回
         */
        @Override public JsonElement output() { return output.deepCopy(); }
        /**
         * 生成不含业务输出的结果说明。
         * @return 包含成功标志的说明，隐藏业务输出
         */
        @Override public String toString() { return "CommandResult[success=" + success + ", output=<redacted>]"; }
    }

    /**
     * 服务端命令调用记录的不可变快照，不会随远端状态变化自动更新。
     *
     * <p>受理不代表执行成功。需要最新状态时使用 {@link CielClient#getCommand(String)}，
     * 等待终态时使用 {@link CielClient#awaitCommand(String, Duration)}。
     */
    public static final class CommandCall {
        private final JsonObject value;
        CommandCall(JsonObject value) {
            Protocol.hex(Protocol.string(value, "id"), 32);
            Protocol.id(Protocol.string(value, "request_id"));
            Protocol.id(Protocol.string(value, "command_id"));
            Protocol.require(value, "input", "output", "error_code", "deadline");
            Protocol.integer(value, "deadline");
            if (!Set.of("PENDING", "DISPATCHED", "SUCCEEDED", "FAILED", "UNKNOWN").contains(Protocol.string(value, "status")))
                throw Protocol.bad();
            if (!value.get("error_code").isJsonNull()) Protocol.string(value, "error_code");
            this.value = value.deepCopy();
        }
        /**
         * 读取服务端调用记录标识。
         * @return 服务端生成的 32 位小写十六进制调用 ID，可用于后续查询
         */
        public String id() { return Protocol.string(value, "id"); }
        /**
         * 读取原始业务请求标识。
         * @return 发起调用时使用的业务请求 ID，不是查询请求的 ID
         */
        public String requestId() { return Protocol.string(value, "request_id"); }
        /**
         * 返回此快照的状态。{@code UNKNOWN} 表示结果不确定，不能据此认为业务未执行。
         *
         * @return {@code PENDING}、{@code DISPATCHED}、{@code SUCCEEDED}、{@code FAILED} 或 {@code UNKNOWN}
         */
        public String status() { return Protocol.string(value, "status"); }
        /**
         * 读取此快照附带的失败或不确定原因。
         * @return 服务端原因码；未提供原因码时为 Java {@code null}
         */
        public String errorCode() { return value.get("error_code").isJsonNull() ? null : Protocol.string(value, "error_code"); }
        /**
         * 读取此快照的业务输出，不暴露内部可变 JSON。
         * @return 业务输出的深拷贝；无输出时为 JSON null，而非 Java {@code null}
         */
        public JsonElement output() { return value.get("output").deepCopy(); }
        /**
         * 判断此快照是否已终结；终结不等于执行成功。
         * @return 状态为 {@code SUCCEEDED}、{@code FAILED} 或 {@code UNKNOWN} 时返回 {@code true}
         */
        public boolean terminal() { return !status().equals("PENDING") && !status().equals("DISPATCHED"); }
        /**
         * 导出此快照的完整协议记录。
         * @return 完整调用记录的深拷贝，包括业务输入和输出，应按业务数据保护
         */
        public JsonObject json() { return value.deepCopy(); }
        /**
         * 生成脱敏调用说明。
         * @return 仅包含调用 ID 和状态的说明，不包含业务输入、输出
         */
        @Override public String toString() { return "CommandCall[id=" + id() + ", status=" + status() + "]"; }
    }

    /**
     * 提供给本地命令处理函数的执行上下文，绑定收到执行请求时的鉴权连接。
     *
     * <p>处理函数通过返回 {@link CompletionStage} 的 {@link CommandResult} 提交结果。
     * 原连接失效或超过截止时间后，迟到结果不会在新连接提交；这不保证停止业务副作用。
     */
    public final class CommandExecution {
        private final Wire owner;
        private final String callId;
        private final String commandId;
        private final JsonElement input;
        private final long deadline;
        private final AtomicBoolean finished = new AtomicBoolean();

        private CommandExecution(Wire owner, JsonObject value) {
            this.owner = owner;
            callId = Protocol.hex(Protocol.string(value, "call_id"), 32);
            commandId = Protocol.id(Protocol.string(value, "command_id"));
            Protocol.require(value, "input");
            input = value.get("input").deepCopy();
            deadline = Protocol.integer(value, "deadline");
        }
        /**
         * 读取本次执行的调用标识。
         * @return 本次执行对应的 32 位小写十六进制调用 ID
         */
        public String callId() { return callId; }
        /**
         * 读取分发到处理函数的命令标识。
         * @return 要执行的命令 ID
         */
        public String commandId() { return commandId; }
        /**
         * 读取命令输入，不暴露上下文内部的可变 JSON。
         * @return 业务输入的深拷贝；JSON null 不会转为 Java {@code null}
         */
        public JsonElement input() { return input.deepCopy(); }
        /**
         * 返回服务器指定的结果提交截止时间，不是对业务处理的取消指令。
         *
         * @return 以服务器 Unix 秒时间戳转换的截止时间
         * @throws DateTimeException 服务端时间戳超出 {@link Instant} 可表示的范围时
         */
        public Instant deadline() { return Instant.ofEpochSecond(deadline); }

        private void finish(CommandResult result) {
            if (!finished.compareAndSet(false, true)) return;
            executions.remove(callId, this);
            if (wire != owner || state != State.READY || Instant.now().getEpochSecond() >= deadline) {
                report(new CielException(CielException.Kind.CONNECTION, "EXECUTION_CONNECTION_LOST", null, callId,
                        CielException.SendStage.NOT_SENT, null));
                return;
            }
            JsonObject request = Protocol.message("command_result");
            request.addProperty("call_id", callId);
            request.addProperty("success", result.success());
            request.add("output", result.output());
            request(request, "command_result_ack", newRequestId(), callId, owner)
                    .exceptionally(error -> { report(Enrollment.unwrap(error)); return null; });
        }
        /**
         * 生成脱敏执行上下文说明。
         * @return 仅包含调用 ID 和命令 ID 的说明，不包含业务输入
         */
        @Override public String toString() { return "CommandExecution[callId=" + callId + ", commandId=" + commandId + "]"; }
    }

    private static final System.Logger LOG = System.getLogger("top.spco.ciel");
    private static final int MAX_REQUESTS = 64;
    private final IdentityStore store;
    private final Identity identity;
    private final Options options;
    private final ScheduledThreadPoolExecutor clock;
    private final ExecutorService network = Executors.newFixedThreadPool(2, threads("io"));
    private final ThreadPoolExecutor callbacks = pool("callbacks", 2, 64);
    private final ThreadPoolExecutor handlers = pool("commands", 4, 16);
    private final Map<String, Pending> pending = new ConcurrentHashMap<>();
    private final Map<String, Consumer<JsonObject>> listeners = new ConcurrentHashMap<>();
    private final Map<String, Function<CommandExecution, ? extends CompletionStage<CommandResult>>> commands = new ConcurrentHashMap<>();
    private final Map<String, CommandExecution> executions = new ConcurrentHashMap<>();
    private final Set<CompletableFuture<CommandCall>> waiters = ConcurrentHashMap.newKeySet();
    private final Semaphore requestSlots = new Semaphore(MAX_REQUESTS);
    private final Object lifecycle = new Object();
    private volatile State state = State.DISCONNECTED;
    private volatile Wire wire;
    private volatile Consumer<State> stateListener;
    private volatile Consumer<CielException> errorListener;
    private volatile Consumer<CommandCall> completionListener;
    private CompletableFuture<CielClient> firstReady;
    private ScheduledFuture<?> heartbeat;
    private ScheduledFuture<?> heartbeatDeadline;
    private ScheduledFuture<?> authenticationDeadline;
    private ScheduledFuture<?> reconnect;
    private int backoff = 1;
    private long readySince;
    private boolean awaitingHeartbeat;

    private static final class Pending {
        final Wire owner;
        final String expected;
        final String recordId;
        final JsonObject request;
        final AtomicBoolean attempted = new AtomicBoolean();
        final AtomicBoolean responding = new AtomicBoolean();
        final CompletableFuture<JsonObject> result = new CompletableFuture<>();
        volatile CompletableFuture<Void> writing;
        volatile ScheduledFuture<?> timeout;
        Pending(Wire owner, String expected, String recordId, JsonObject request) {
            this.owner = owner; this.expected = expected; this.recordId = recordId; this.request = request;
        }
    }

    /**
     * 使用默认配置加载已有身份并持有身份目录锁，尚不建立网络连接。
     *
     * @param identityDirectory 已注册且含 {@code identity.json} 的身份目录
     * @throws NullPointerException 目录为空时
     * @throws CielException 身份无法读取、格式无效、权限设置失败或目录已被其他客户端占用时
     * @see #CielClient(Path, Options)
     */
    public CielClient(Path identityDirectory) { this(identityDirectory, Options.defaults()); }

    /**
     * 使用指定配置加载已有身份并持有 {@code agent.lock}，尚不建立网络连接。
     *
     * <p>可在调用 {@link #connect()} 前安装命令处理函数，以处理鉴权后立即到来的执行请求。
     * 同一身份目录不能同时由 Java 或 Rust 客户端使用；锁保持到 {@link #close()}。
     *
     * @param identityDirectory 已注册且含 {@code identity.json} 的身份目录
     * @param options 超时和重连配置
     * @throws NullPointerException 目录或配置为空时
     * @throws CielException 身份无法读取、格式无效、权限设置失败或目录已被占用时
     */
    public CielClient(Path identityDirectory, Options options) {
        this.options = Objects.requireNonNull(options, "options");
        store = new IdentityStore(Objects.requireNonNull(identityDirectory, "identityDirectory"), false);
        try { identity = store.load(); }
        catch (RuntimeException error) { store.close(); network.shutdownNow(); callbacks.shutdownNow(); handlers.shutdownNow(); throw error; }
        clock = new ScheduledThreadPoolExecutor(1, threads("clock"));
        clock.setRemoveOnCancelPolicy(true);
    }

    /**
     * 使用管理员签发的 JSON 授权进行两阶段注册，返回持久保存的服务身份。
     *
     * <p>先验证 TLS 和 Pin，再获取并原子保存候选凭据，随后在同一连接提交注册。
     * 成功后将候选确认为 {@code identity.json}；本方法不返回持续连接的客户端。
     * 若上次提交的确认丢失，会先尝试鉴权已有候选，再使用一次性令牌；已生效候选的恢复
     * 不要求原令牌仍然有效。失败或取消后应保留候选文件，使用同一授权和目录重试。
     *
     * @param grant 包含服务器地址、Pin、服务 ID 和一次性令牌的授权 JSON 文件
     * @param identityDirectory 身份目录，不存在时创建；不能已有正式身份或正在被其他进程占用
     * @return 注册或候选恢复成功后完成的身份 Future；失败时以 {@link CielException} 异常完成，
     *         包括已有身份时的 {@code ALREADY_ENROLLED}
     * @throws NullPointerException 任一路径为空时
     * @see #connect(Path)
     */
    public static CompletableFuture<Identity> enroll(Path grant, Path identityDirectory) {
        Objects.requireNonNull(grant, "grant"); Objects.requireNonNull(identityDirectory, "identityDirectory");
        CompletableFuture<Identity> result = new CompletableFuture<>();
        Thread worker = threads("enroll").newThread(() -> {
            try { result.complete(Enrollment.enroll(grant, identityDirectory)); }
            catch (Throwable error) { result.completeExceptionally(error); }
        });
        result.whenComplete((value, error) -> { if (result.isCancelled()) worker.interrupt(); });
        worker.start();
        return result;
    }

    /**
     * 使用默认配置创建客户端并等待首次鉴权成功。
     *
     * <p>适用于无需预先安装命令处理函数的调用方。异步连接失败或 Future 被取消时自动关闭
     * 客户端；成功后调用方负责关闭返回的实例。需要提供命令时，应使用构造器、
     * {@link #onCommand(String, Function)} 和实例的 {@link #connect()}。
     *
     * @param directory 已注册的身份目录
     * @return 首次进入 {@link State#READY} 时完成的客户端 Future；临时故障持续退避重试，
     *         不可恢复错误以 {@link CielException} 异常完成
     * @throws NullPointerException 目录为空时
     * @throws CielException 身份加载或锁定失败时同步抛出
     */
    public static CompletableFuture<CielClient> connect(Path directory) {
        CielClient client = new CielClient(directory);
        CompletableFuture<CielClient> ready = client.connect();
        ready.whenComplete((value, error) -> { if (error != null) client.close(); });
        return ready;
    }

    /**
     * 读取构造客户端时加载的身份。
     * @return 客户端使用的不可变身份；其中的服务凭据仍须作为秘密保护
     */
    public Identity identity() { return identity; }
    /**
     * 读取当前生命周期状态。
     * @return 当前连接状态的即时快照，不保证读取后仍保持该状态
     */
    public State state() { return state; }
    /**
     * 生成适用于业务请求的随机 ID。
     *
     * <p>应在发送可恢复的命令或通知之前持久保存 ID 和参数。恢复原操作时复用原 ID 和相同
     * 参数；新 ID 代表新操作。此方法只生成 ID，不保存 ID 或登记请求。
     *
     * @return 32 位小写十六进制随机请求 ID
     */
    public static String newRequestId() { return Protocol.requestId(); }
    /**
     * 设置或替换连接状态监听器，不立即回放当前状态。
     *
     * <p>通常由回调线程池异步分发，通知可能并发，读取当前状态应使用 {@link #state()}。
     * {@link #close()} 产生的 {@link State#CLOSED} 通知在关闭调用线程同步执行。
     * 回调队列已满时可能无法分发状态通知。
     *
     * @param listener 状态监听器；Java {@code null} 移除监听器
     * @return 当前客户端，便于链式配置
     */
    public CielClient onState(Consumer<State> listener) { stateListener = listener; return this; }
    /**
     * 设置或替换连接、执行结果提交和回调失败的错误监听器。
     *
     * <p>通过回调线程池尽力分发，队列已满时只记录错误码。普通业务错误通过对应请求的
     * Future 返回，不保证另外触发此监听器。监听器异常不会中断接收循环。
     *
     * @param listener 错误监听器；Java {@code null} 移除监听器
     * @return 当前客户端
     */
    public CielClient onError(Consumer<CielException> listener) { errorListener = listener; return this; }
    /**
     * 设置或替换命令完成推送监听器。
     *
     * <p>推送为在线尽力投递，可能先于受理 Future 的完成通知到达，不能作为唯一恢复途径。
     * 本监听器不负责完成 {@link #awaitCommand(String, Duration)}，后者查询服务端记录。
     *
     * @param listener 接收终态调用快照的监听器；Java {@code null} 移除监听器
     * @return 当前客户端
     */
    public CielClient onCommandCompleted(Consumer<CommandCall> listener) { completionListener = listener; return this; }

    /**
     * 为事件设置或替换本地监听器，不创建服务器订阅关系。
     *
     * <p>需另外调用 {@link #subscribeEvent(String)}。监听器接收完整事件封包的深拷贝，
     * 含 {@code event_id}、{@code publication_id}、发布方、时间和 {@code payload}。
     * 同一监听器可能被并发调用，应自行保护共享数据。
     *
     * @param eventId 事件 ID，符合客户端的 ID 格式规则
     * @param listener 非空事件监听器
     * @return 当前客户端
     * @throws CielException 事件 ID 格式无效时
     * @throws NullPointerException 监听器为空时
     * @see #removeEventListener(String)
     */
    public CielClient onEvent(String eventId, Consumer<JsonObject> listener) {
        listeners.put(Protocol.id(eventId), Objects.requireNonNull(listener)); return this;
    }
    /**
     * 移除本地事件监听器；已排队的通知仍可能执行，不取消服务器订阅。
     *
     * @param eventId 要移除监听器的事件 ID
     * @throws CielException 事件 ID 格式无效时
     * @see #unsubscribeEvent(String)
     */
    public void removeEventListener(String eventId) { listeners.remove(Protocol.id(eventId)); }
    /**
     * 安装或替换本地命令处理函数，不向服务器注册命令。
     *
     * <p>应先安装处理函数，再连接和调用 {@link #registerCommand(String, String)}。
     * 处理函数在独立线程池运行，必须返回非空的异步结果；抛出异常、异步失败或返回空值时，
     * SDK 在原连接有效且未过期的条件下提交 {@code HANDLER_FAILED} 失败结果。
     * 容量不足时提交 {@code HANDLER_BUSY}。断线和截止时间不保证停止处理函数的副作用。
     *
     * @param commandId 命令 ID，符合客户端的 ID 格式规则
     * @param handler 非空处理函数，接收执行上下文并返回 {@link CommandResult} 的完成阶段
     * @return 当前客户端
     * @throws CielException 命令 ID 格式无效时
     * @throws NullPointerException 处理函数为空时
     */
    public CielClient onCommand(String commandId, Function<CommandExecution, ? extends CompletionStage<CommandResult>> handler) {
        commands.put(Protocol.id(commandId), Objects.requireNonNull(handler)); return this;
    }

    /**
     * 开始连接并等待首次进入 {@link State#READY}。
     *
     * <p>首次连接期间重复调用共享同一个 Future；临时故障会继续退避重试，取消该 Future
     * 会关闭客户端。首次 READY 后该 Future 不会重置，因此重连期间再次调用也可能返回
     * 已完成的首次连接 Future；应使用 {@link #state()} 或状态监听器判断当前是否 READY。
     * 业务请求不因重连而自动重发。
     *
     * @return 首次鉴权成功时以当前客户端完成的 Future；不可恢复错误、已关闭或失败的实例
     *         以 {@link CielException} 异常完成
     */
    public CompletableFuture<CielClient> connect() {
        synchronized (lifecycle) {
            if (state == State.CLOSED || state == State.FAILED) return CompletableFuture.failedFuture(new CielException(CielException.Kind.CLOSED, "CLIENT_CLOSED"));
            if (state == State.READY) return CompletableFuture.completedFuture(this);
            if (firstReady != null) return firstReady;
            firstReady = new CompletableFuture<>();
            firstReady.whenComplete((client, error) -> { if (firstReady.isCancelled()) close(); });
            clock.execute(this::open);
            return firstReady;
        }
    }

    private void open() {
        synchronized (lifecycle) {
            if (state == State.CLOSED || state == State.FAILED) return;
            transition(State.CONNECTING);
            Wire[] reference = new Wire[1];
            reference[0] = new Wire(clock, value -> receive(reference[0], value),
                    error -> lost(reference[0], error), options.connectTimeout());
            Wire current = reference[0];
            wire = current;
            current.connect(identity.server(), identity.serverSpkiSha256(), network).whenComplete((socket, error) -> {
                synchronized (lifecycle) {
                    if (wire != current) return;
                    if (error != null) { lost(current, Enrollment.unwrap(error)); return; }
                    transition(State.AUTHENTICATING);
                    authenticationDeadline = clock.schedule(() -> lost(current,
                            new CielException(CielException.Kind.TIMEOUT, "AUTHENTICATION_TIMEOUT")),
                            options.connectTimeout().toMillis(), TimeUnit.MILLISECONDS);
                    current.send(Enrollment.authenticate(identity)).exceptionally(failure -> { lost(current, Enrollment.unwrap(failure)); return null; });
                }
            });
        }
    }

    private void receive(Wire source, JsonObject message) {
        synchronized (lifecycle) {
            if (wire != source || state == State.CLOSED || state == State.FAILED) return;
            String type = Protocol.string(message, "type");
            if (state == State.AUTHENTICATING) {
                if (type.equals("error")) { lost(source, Protocol.serverError(message)); return; }
                Enrollment.authenticated(message);
                cancel(authenticationDeadline);
                readySince = System.nanoTime();
                transition(State.READY);
                long interval = Protocol.integer(message, "heartbeat_interval_seconds");
                heartbeat = clock.scheduleWithFixedDelay(() -> beat(source), interval, interval, TimeUnit.SECONDS);
                dispatch(() -> firstReady.complete(this));
                return;
            }
            if (state != State.READY) throw Protocol.bad();
            switch (type) {
                case "heartbeat_ack" -> {
                    Protocol.integer(message, "server_time");
                    awaitingHeartbeat = false;
                    cancel(heartbeatDeadline);
                }
                case "event" -> {
                    String event = Protocol.id(Protocol.string(message, "event_id"));
                    Protocol.id(Protocol.string(message, "service_id"));
                    Protocol.hex(Protocol.string(message, "instance_id"), 32);
                    Protocol.integer(message, "publication_id"); Protocol.integer(message, "created_at");
                    Protocol.require(message, "payload");
                    Consumer<JsonObject> listener = listeners.get(event);
                    if (listener != null) dispatch(() -> listener.accept(message.deepCopy()));
                }
                case "command_execute" -> execute(source, message);
                case "command_completed" -> {
                    CommandCall call = new CommandCall(Protocol.object(message.get("call")));
                    if (!call.terminal()) throw Protocol.bad();
                    Consumer<CommandCall> listener = completionListener;
                    if (listener != null) dispatch(() -> listener.accept(call));
                }
                case "error" -> {
                    CielException error = Protocol.serverError(message);
                    if (!message.has("request_id")) { lost(source, error); return; }
                    String id = Protocol.id(Protocol.string(message, "request_id"));
                    Pending request = pending.get(id);
                    if (request != null && request.owner == source && request.responding.compareAndSet(false, true))
                        dispatch(() -> request.result.completeExceptionally(error.forRequest(id, request.recordId, request.attempted.get())));
                }
                default -> response(source, type, message);
            }
        }
    }

    private void response(Wire source, String type, JsonObject message) {
        if (!Set.of("event_registered", "event_unregistered", "event_subscribed", "event_unsubscribed", "event_published",
                "command_registered", "command_unregistered", "command_accepted", "command_status", "command_result_ack",
                "notification_registered", "notification_unregistered", "notification_result", "notification_status").contains(type)) throw Protocol.bad();
        String id = Protocol.id(Protocol.string(message, "request_id"));
        Pending request = pending.get(id);
        if (request == null || request.owner != source) return; // A known, late response to an expired request.
        if (!request.expected.equals(type)) throw Protocol.bad();
        String field = switch (type) {
            case "event_registered", "event_unregistered", "event_subscribed", "event_unsubscribed", "event_published" -> "event_id";
            case "command_registered", "command_unregistered" -> "command_id";
            case "notification_registered", "notification_unregistered" -> "notification_type";
            case "command_result_ack" -> "call_id";
            default -> null;
        };
        if (field != null && !Protocol.string(message, field).equals(Protocol.string(request.request, field))) throw Protocol.bad();
        if (type.equals("event_published")) {
            Protocol.integer(message, "publication_id"); Protocol.integer(message, "subscriber_count"); Protocol.integer(message, "queued_count");
        } else if (type.equals("command_accepted") || type.equals("command_status")) {
            CommandCall call = new CommandCall(Protocol.object(message.get("call")));
            if (type.equals("command_accepted") && !call.requestId().equals(id)) throw Protocol.bad();
            if (type.equals("command_accepted") && (!Protocol.string(call.value, "command_id").equals(Protocol.string(request.request, "command_id")) ||
                    !Protocol.string(call.value, "target_instance_id").equals(Protocol.string(request.request, "target_instance_id")))) throw Protocol.bad();
            if (request.recordId != null && !call.id().equals(request.recordId)) throw Protocol.bad();
        } else if (type.equals("notification_result") || type.equals("notification_status")) {
            JsonObject notification = Protocol.object(message.get("notification"));
            Protocol.hex(Protocol.string(notification, "id"), 32);
            Protocol.id(Protocol.string(notification, "request_id"));
            Protocol.id(Protocol.string(notification, "notification_type"));
            Protocol.integer(notification, "recipient_count"); Protocol.integer(notification, "created_at");
            if (!notification.has("deliveries") || !notification.get("deliveries").isJsonArray()) throw Protocol.bad();
            for (JsonElement item : notification.getAsJsonArray("deliveries")) {
                JsonObject delivery = Protocol.object(item);
                Protocol.string(delivery, "notifier_id"); Protocol.integer(delivery, "count");
                if (!Set.of("PENDING", "SENDING", "DELIVERED", "SKIPPED", "FAILED").contains(Protocol.string(delivery, "status"))) throw Protocol.bad();
                Protocol.require(delivery, "error_code");
                if (!delivery.get("error_code").isJsonNull()) Protocol.string(delivery, "error_code");
            }
            if (type.equals("notification_result") && !Protocol.string(notification, "request_id").equals(id)) throw Protocol.bad();
            if (type.equals("notification_result") && !Protocol.string(notification, "notification_type")
                    .equals(Protocol.string(request.request, "notification_type"))) throw Protocol.bad();
            if (request.recordId != null && !Protocol.string(notification, "id").equals(request.recordId)) throw Protocol.bad();
        }
        if (request.responding.compareAndSet(false, true)) dispatch(() -> request.result.complete(message.deepCopy()));
    }

    private void beat(Wire source) {
        synchronized (lifecycle) {
            if (wire != source || state != State.READY || awaitingHeartbeat) return;
            awaitingHeartbeat = true;
            source.send(Protocol.message("heartbeat")).whenComplete((ignored, error) -> {
                synchronized (lifecycle) {
                    if (wire != source) return;
                    if (error != null) lost(source, Enrollment.unwrap(error));
                    else if (awaitingHeartbeat) heartbeatDeadline = clock.schedule(() -> lost(source,
                            new CielException(CielException.Kind.TIMEOUT, "HEARTBEAT_TIMEOUT")),
                            options.heartbeatAckTimeout().toMillis(), TimeUnit.MILLISECONDS);
                }
            });
        }
    }

    private void lost(Wire source, CielException error) {
        synchronized (lifecycle) {
            if (wire != source || state == State.CLOSED || state == State.FAILED) return;
            wire = null;
            long retryAfter = source.retryAfterSeconds();
            source.close();
            cancel(heartbeat); cancel(heartbeatDeadline); cancel(authenticationDeadline);
            awaitingHeartbeat = false;
            executions.values().forEach(execution -> execution.finished.set(true));
            executions.clear();
            pending.forEach((id, request) -> request.result.completeExceptionally(error.forRequest(id, request.recordId, request.attempted.get())));
            waiters.forEach(waiter -> waiter.completeExceptionally(error));
            report(error);
            if (!error.retryable()) {
                transition(State.FAILED);
                if (firstReady != null) firstReady.completeExceptionally(error);
                return;
            }
            if (readySince != 0 && System.nanoTime() - readySince >= TimeUnit.SECONDS.toNanos(30)) backoff = 1;
            readySince = 0;
            transition(State.RECONNECTING);
            long base = Math.min(backoff, options.maxReconnectDelay().toSeconds());
            long delay = Math.max(retryAfter * 1000, Math.max(100, base * 1000) + ThreadLocalRandom.current().nextLong(251));
            backoff = Math.min(backoff * 2, 30);
            reconnect = clock.schedule(this::open, delay, TimeUnit.MILLISECONDS);
        }
    }

    private CompletableFuture<JsonObject> request(JsonObject message, String expected, String id, String record, Wire bound) {
        Protocol.id(id);
        message.addProperty("request_id", id);
        Protocol.encode(message);
        synchronized (lifecycle) {
            Wire owner = wire;
            if (state != State.READY || owner == null || (bound != null && bound != owner))
                return CompletableFuture.failedFuture(new CielException(CielException.Kind.CONNECTION, "NOT_READY").forRequest(id, record, false));
            if (!requestSlots.tryAcquire()) return CompletableFuture.failedFuture(new CielException(CielException.Kind.CAPACITY, "TOO_MANY_REQUESTS").forRequest(id, record, false));
            Pending request = new Pending(owner, expected, record, message.deepCopy());
            if (pending.putIfAbsent(id, request) != null) {
                requestSlots.release();
                return CompletableFuture.failedFuture(Protocol.input("REQUEST_ID_IN_USE").forRequest(id, record, false));
            }
            request.result.whenComplete((value, error) -> {
                if (pending.remove(id, request)) requestSlots.release();
                cancel(request.timeout);
                if (request.writing != null && !request.writing.isDone()) request.writing.cancel(false);
            });
            request.timeout = clock.schedule(() -> {
                if (request.responding.compareAndSet(false, true)) {
                    if (request.writing != null) request.writing.cancel(false);
                    dispatch(() -> request.result.completeExceptionally(new CielException(CielException.Kind.TIMEOUT,
                            "CLIENT_TIMEOUT").forRequest(id, record, request.attempted.get())));
                }
            }, options.requestTimeout().toMillis(), TimeUnit.MILLISECONDS);
            request.writing = owner.send(message, request.attempted);
            request.writing.whenComplete((value, error) -> {
                if (error != null && !request.responding.get() && !request.result.isDone()) request.result.completeExceptionally(Enrollment.unwrap(error)
                        .forRequest(id, record, request.attempted.get()));
            });
            return request.result;
        }
    }

    private CompletableFuture<JsonObject> event(String action, String event, JsonElement payload, String id) {
        JsonObject request = Protocol.message(action);
        request.addProperty("event_id", Protocol.id(event));
        if (action.equals("event_publish")) request.add("payload", Protocol.payload(payload));
        String response = switch (action) {
            case "event_register" -> "event_registered";
            case "event_unregister" -> "event_unregistered";
            case "event_subscribe" -> "event_subscribed";
            case "event_unsubscribe" -> "event_unsubscribed";
            default -> "event_published";
        };
        return request(request, response, id, null, null);
    }

    /**
     * 为当前实例注册事件发布关系；关系由服务器持久保存，重复注册不会增加关系。
     *
     * @param id 事件 ID
     * @return 含 {@code event_id} 和 {@code request_id} 的 {@code event_registered} 确认封包 Future
     * @throws CielException 事件 ID 格式无效时
     */
    public CompletableFuture<JsonObject> registerEvent(String id) { return event("event_register", id, null, newRequestId()); }
    /**
     * 移除当前实例的事件发布关系，不取消订阅关系。
     *
     * @param id 事件 ID
     * @return 含 {@code event_id} 和 {@code request_id} 的 {@code event_unregistered} 确认封包 Future
     * @throws CielException 事件 ID 格式无效时
     */
    public CompletableFuture<JsonObject> unregisterEvent(String id) { return event("event_unregister", id, null, newRequestId()); }
    /**
     * 为当前实例创建持久事件订阅；可以早于发布方注册，不安装本地监听器。
     *
     * @param id 事件 ID
     * @return 含 {@code event_id} 和 {@code request_id} 的 {@code event_subscribed} 确认封包 Future
     * @throws CielException 事件 ID 格式无效时
     * @see #onEvent(String, Consumer)
     */
    public CompletableFuture<JsonObject> subscribeEvent(String id) { return event("event_subscribe", id, null, newRequestId()); }
    /**
     * 移除当前实例的服务器订阅，不移除本地监听器；已排队推送仍可能到达。
     *
     * @param id 事件 ID
     * @return 含 {@code event_id} 和 {@code request_id} 的 {@code event_unsubscribed} 确认封包 Future
     * @throws CielException 事件 ID 格式无效时
     */
    public CompletableFuture<JsonObject> unsubscribeEvent(String id) { return event("event_unsubscribe", id, null, newRequestId()); }
    /**
     * 发布已注册事件，向在线订阅实例尽力投递。
     *
     * <p>发布不具备幂等性：相同请求 ID 的重复发布仍会创建新的发布记录。断线或超时后
     * 不自动重试；事件没有离线补发或接收确认。{@code queued_count} 只表示进入服务器
     * 发送队列的数量，不表示订阅方实际收到的数量。
     *
     * @param event 已为当前实例注册的事件 ID
     * @param payload 业务 JSON，可为 Java {@code null}，发送前深拷贝且不超过 2048 UTF-8 字节
     * @param requestId 调用方指定的请求 ID，不能与当前在途请求重复
     * @return 含 {@code request_id}、{@code publication_id}、{@code subscriber_count} 和
     *         {@code queued_count} 的 {@code event_published} 确认封包 Future
     * @throws CielException ID 无效、JSON 无效或消息超过大小限制时
     */
    public CompletableFuture<JsonObject> publishEvent(String event, JsonElement payload, String requestId) { return event("event_publish", event, payload, requestId); }

    /**
     * 为当前实例注册命令，要求事先安装相应本地处理函数。
     *
     * <p>命令记录由服务器持久保存；本地处理函数不会随客户端重建自动恢复。
     * 调用权限仍须由 Ciel 管理员授予调用实例。
     *
     * @param id 命令 ID
     * @param description 非空引用的描述字符串，可为空串，最多 1024 UTF-8 字节
     * @return 含 {@code command_id} 和 {@code request_id} 的 {@code command_registered} 确认封包 Future
     * @throws CielException ID 或描述无效、消息过大或缺少本地处理函数时
     * @see #onCommand(String, Function)
     */
    public CompletableFuture<JsonObject> registerCommand(String id, String description) {
        Protocol.id(id);
        if (!commands.containsKey(id)) throw Protocol.input("COMMAND_HANDLER_REQUIRED");
        JsonObject request = Protocol.message("command_register");
        request.addProperty("command_id", id);
        request.addProperty("description", Protocol.text(description, 1024, false));
        return request(request, "command_registered", newRequestId(), null, null);
    }

    /**
     * 注销服务器命令，并在收到成功确认后移除对应本地处理函数。
     *
     * <p>不保证取消已经开始的执行；失败时保留本地处理函数。
     *
     * @param id 命令 ID
     * @return 含 {@code command_id} 和 {@code request_id} 的 {@code command_unregistered} 确认封包 Future
     * @throws CielException 命令 ID 格式无效时
     */
    public CompletableFuture<JsonObject> unregisterCommand(String id) {
        JsonObject request = Protocol.message("command_unregister");
        request.addProperty("command_id", Protocol.id(id));
        return map(request(request, "command_unregistered", newRequestId(), null, null), response -> {
            commands.remove(id); return response;
        });
    }

    /**
     * 请求调用目标实例上的命令，返回服务端受理时的调用快照。
     *
     * <p>受理不表示执行成功，返回快照也可能已经进入终态。等待结果使用
     * {@link #awaitCommand(String, Duration)}。发送前应保存请求 ID 和参数；结果不确定且
     * 无调用 ID 时，使用原请求 ID 和完全相同的目标、命令、输入、超时恢复原记录。
     * 参数改变时服务端返回 {@code REQUEST_ID_CONFLICT}；记录已清理时保留
     * {@code CALL_RECORD_EXPIRED}，SDK 不自动生成新 ID 或重新执行。
     *
     * @param targetInstance 目标实例 ID，32 位小写十六进制
     * @param command 目标实例注册的命令 ID；调用实例需有对应权限
     * @param input 输入 JSON，可为 Java {@code null}，深拷贝且不超过 2048 UTF-8 字节
     * @param timeoutSeconds 服务端调用超时，1 至 300 秒，不等同于客户端请求等待超时
     * @param requestId 发送前持久保存的业务请求 ID，恢复原操作时复用同一 ID 和参数
     * @return 以 {@code command_accepted} 中的调用快照完成的 Future；服务端拒绝以
     *         {@link CielException} 异常完成
     * @throws CielException ID、超时或 JSON 无效，或消息超过大小限制时
     */
    public CompletableFuture<CommandCall> invokeCommand(String targetInstance, String command, JsonElement input, int timeoutSeconds, String requestId) {
        if (timeoutSeconds < 1 || timeoutSeconds > 300) throw Protocol.input("INVALID_TIMEOUT");
        JsonObject request = Protocol.message("command_invoke");
        request.addProperty("target_instance_id", Protocol.hex(targetInstance, 32));
        request.addProperty("command_id", Protocol.id(command));
        request.add("input", Protocol.payload(input));
        request.addProperty("timeout_seconds", timeoutSeconds);
        return map(request(request, "command_accepted", requestId, null, null),
                response -> new CommandCall(Protocol.object(response.get("call"))));
    }

    /**
     * 查询已有命令调用的最新状态，不触发新的业务执行。
     *
     * @param callId 已知的 32 位小写十六进制调用 ID
     * @return 最新调用快照 Future；无权查询或记录不可用时以 {@link CielException} 异常完成
     * @throws CielException 调用 ID 格式无效时
     */
    public CompletableFuture<CommandCall> getCommand(String callId) {
        JsonObject request = Protocol.message("command_get");
        request.addProperty("call_id", Protocol.hex(callId, 32));
        return map(request(request, "command_status", newRequestId(), callId, null),
                response -> new CommandCall(Protocol.object(response.get("call"))));
    }

    /**
     * 查询已有调用直至进入 {@code SUCCEEDED}、{@code FAILED} 或 {@code UNKNOWN} 终态。
     *
     * <p>先立即查询，未终结时在上次查询返回后约 1 秒再次查询；不发起新的命令执行。
     * 断线、单次查询失败或本地等待超时都会结束 Future，不自动跨重连继续等待。
     * 本地超时返回带调用 ID 的 {@code CLIENT_TIMEOUT}，之后仍可查询原记录。
     * 取消等待不保证取消服务端执行，也不保证取消已经发出的状态查询。
     *
     * @param callId 已知的 32 位小写十六进制调用 ID
     * @param timeout 本地总等待上限，至少 1 毫秒、至多 1 天
     * @return 终态快照 Future；连接未就绪、等待容量已满、查询失败或超时以
     *         {@link CielException} 异常完成
     * @throws CielException 调用 ID 或时长无效时
     * @throws ArithmeticException 时长过大而不能换算为毫秒时
     */
    public CompletableFuture<CommandCall> awaitCommand(String callId, Duration timeout) {
        Protocol.hex(callId, 32); Protocol.duration(timeout);
        synchronized (lifecycle) {
            if (state != State.READY) return CompletableFuture.failedFuture(new CielException(CielException.Kind.CONNECTION,
                    "NOT_READY", null, callId, CielException.SendStage.NOT_SENT, null));
            if (waiters.size() >= MAX_REQUESTS) return CompletableFuture.failedFuture(new CielException(CielException.Kind.CAPACITY, "TOO_MANY_WAITERS"));
            CompletableFuture<CommandCall> result = new CompletableFuture<>();
            waiters.add(result);
            ScheduledFuture<?> expiry = clock.schedule(() -> dispatch(() -> result.completeExceptionally(new CielException(CielException.Kind.TIMEOUT,
                    "CLIENT_TIMEOUT", null, callId, CielException.SendStage.NOT_SENT, null))), timeout.toMillis(), TimeUnit.MILLISECONDS);
            result.whenComplete((value, error) -> { waiters.remove(result); expiry.cancel(false); });
            poll(callId, result);
            return result;
        }
    }

    private void poll(String callId, CompletableFuture<CommandCall> result) {
        if (result.isDone()) return;
        getCommand(callId).whenComplete((call, error) -> {
            if (result.isDone()) return;
            if (error != null) result.completeExceptionally(error);
            else if (call.terminal()) result.complete(call);
            else try { clock.schedule(() -> poll(callId, result), 1, TimeUnit.SECONDS); }
                catch (RejectedExecutionException closed) { result.completeExceptionally(new CielException(CielException.Kind.CLOSED, "CLIENT_CLOSED")); }
        });
    }

    private void execute(Wire source, JsonObject message) {
        CommandExecution execution = new CommandExecution(source, message);
        if (executions.putIfAbsent(execution.callId, execution) != null) throw Protocol.bad();
        if (executions.size() > 16) { execution.finish(failureResult("HANDLER_BUSY")); return; }
        long delay = Math.max(0, execution.deadline - Instant.now().getEpochSecond());
        ScheduledFuture<?> expiry = clock.schedule(() -> {
            if (execution.finished.compareAndSet(false, true)) executions.remove(execution.callId, execution);
        }, Math.min(delay, 300), TimeUnit.SECONDS);
        Function<CommandExecution, ? extends CompletionStage<CommandResult>> handler = commands.get(execution.commandId);
        if (handler == null) { expiry.cancel(false); execution.finish(failureResult("NO_HANDLER")); return; }
        try {
            handlers.execute(() -> {
                if (execution.finished.get()) return;
                try {
                    CompletionStage<CommandResult> result = Objects.requireNonNull(handler.apply(execution));
                    result.whenComplete((output, error) -> {
                        expiry.cancel(false);
                        execution.finish(error == null && output != null ? output : failureResult("HANDLER_FAILED"));
                    });
                } catch (Throwable error) { expiry.cancel(false); execution.finish(failureResult("HANDLER_FAILED")); }
            });
        } catch (RejectedExecutionException error) { expiry.cancel(false); execution.finish(failureResult("HANDLER_BUSY")); }
    }

    private static CommandResult failureResult(String code) {
        JsonObject output = new JsonObject(); output.addProperty("code", code);
        return new CommandResult(false, output);
    }

    /**
     * 为当前实例注册通知类型及其显示信息，由服务器持久保存。
     *
     * @param type 通知类型 ID
     * @param name 非空白显示名称，最多 128 UTF-8 字节
     * @param description 非空引用的描述字符串，可为空串，最多 1024 UTF-8 字节
     * @return 含 {@code notification_type} 和 {@code request_id} 的
     *         {@code notification_registered} 确认封包 Future
     * @throws CielException 类型 ID、文本无效或消息超过大小限制时
     */
    public CompletableFuture<JsonObject> registerNotification(String type, String name, String description) {
        JsonObject request = Protocol.message("notification_register");
        request.addProperty("notification_type", Protocol.id(type));
        request.addProperty("name", Protocol.text(name, 128, true));
        request.addProperty("description", Protocol.text(description, 1024, false));
        return request(request, "notification_registered", newRequestId(), null, null);
    }

    /**
     * 注销当前实例的通知类型，不撤销已创建的通知或投递。
     *
     * @param type 通知类型 ID
     * @return 含 {@code notification_type} 和 {@code request_id} 的
     *         {@code notification_unregistered} 确认封包 Future
     * @throws CielException 类型 ID 格式无效时
     */
    public CompletableFuture<JsonObject> unregisterNotification(String type) {
        JsonObject request = Protocol.message("notification_unregister");
        request.addProperty("notification_type", Protocol.id(type));
        return request(request, "notification_unregistered", newRequestId(), null, null);
    }

    /**
     * 发送已注册类型的纯文本通知，收件人和渠道由 Ciel 的用户偏好决定。
     *
     * <p>返回创建后的投递汇总，不等待所有渠道终结，也不存在通知完成推送。
     * 状态不确定时用原请求 ID 和相同类型、标题、正文恢复，或用已知通知 ID 查询；
     * 新请求 ID 会创建新通知。{@code recipient_count} 不等于成功人数，
     * 邮件 {@code DELIVERED} 表示 SMTP 接受，不代表用户已读。
     *
     * @param type 当前实例注册的通知类型 ID
     * @param title 非空白纯文本标题，最多 200 UTF-8 字节
     * @param body 非空白纯文本正文，最多 2048 UTF-8 字节
     * @param requestId 发送前持久保存的业务请求 ID，恢复原通知时复用同一 ID 和参数
     * @return 通知记录 JSON Future，含 {@code id}、{@code request_id}、{@code notification_type}、
     *         {@code created_at}、{@code recipient_count} 和 {@code deliveries}；
     *         各投递项保留 {@code notifier_id}、{@code status}、{@code error_code} 和 {@code count}
     * @throws CielException ID、文本无效或完整消息超过 4096 UTF-8 字节时
     * @see #getNotification(String)
     */
    public CompletableFuture<JsonObject> sendNotification(String type, String title, String body, String requestId) {
        JsonObject request = Protocol.message("notification_send");
        request.addProperty("notification_type", Protocol.id(type));
        request.addProperty("title", Protocol.text(title, 200, true));
        request.addProperty("body", Protocol.text(body, 2048, true));
        return map(request(request, "notification_result", requestId, null, null), response -> response.getAsJsonObject("notification"));
    }

    /**
     * 查询当前实例创建的通知投递汇总，不创建通知或重试投递。
     *
     * @param notificationId 已知的 32 位小写十六进制通知 ID
     * @return 与 {@link #sendNotification(String, String, String, String)} 相同结构的最新汇总 Future，
     *         保留各渠道的 {@code PENDING}、{@code SENDING}、{@code DELIVERED}、
     *         {@code SKIPPED}、{@code FAILED} 状态和原因码
     * @throws CielException 通知 ID 格式无效时
     */
    public CompletableFuture<JsonObject> getNotification(String notificationId) {
        JsonObject request = Protocol.message("notification_get");
        request.addProperty("notification_id", Protocol.hex(notificationId, 32));
        return map(request(request, "notification_status", newRequestId(), notificationId, null),
                response -> response.getAsJsonObject("notification"));
    }

    private static <T, U> CompletableFuture<U> map(CompletableFuture<T> source, Function<T, U> transform) {
        CompletableFuture<U> mapped = source.thenApply(transform);
        mapped.whenComplete((value, error) -> { if (mapped.isCancelled()) source.cancel(false); });
        return mapped;
    }

    private void transition(State next) {
        state = next;
        Consumer<State> listener = stateListener;
        if (listener != null) try { dispatch(() -> listener.accept(next)); }
            catch (CielException full) { LOG.log(System.Logger.Level.WARNING, "Ciel state listener capacity exhausted"); }
    }

    private void report(CielException error) {
        LOG.log(System.Logger.Level.WARNING, "Ciel: {0}", error.code());
        Consumer<CielException> listener = errorListener;
        if (listener != null) try { callbacks.execute(() -> {
            try { listener.accept(error); } catch (RuntimeException ignored) { LOG.log(System.Logger.Level.WARNING, "Ciel error listener failed"); }
        }); } catch (RejectedExecutionException ignored) { LOG.log(System.Logger.Level.WARNING, "Ciel callback capacity exhausted"); }
    }

    private void dispatch(Runnable task) {
        try { callbacks.execute(() -> {
            try { task.run(); } catch (Throwable error) { report(new CielException(CielException.Kind.INPUT, "CALLBACK_FAILED")); }
        }); } catch (RejectedExecutionException error) {
            Wire owner = wire;
            if (owner != null) lost(owner, new CielException(CielException.Kind.CAPACITY, "CALLBACK_QUEUE_FULL"));
            throw new CielException(CielException.Kind.CAPACITY, "CALLBACK_QUEUE_FULL");
        }
    }

    static ThreadFactory threads(String role) {
        AtomicInteger sequence = new AtomicInteger();
        return task -> { Thread thread = new Thread(task, "ciel-" + role + "-" + sequence.incrementAndGet()); thread.setDaemon(true); return thread; };
    }
    private static ThreadPoolExecutor pool(String name, int threads, int queue) {
        return new ThreadPoolExecutor(threads, threads, 0, TimeUnit.SECONDS, new ArrayBlockingQueue<>(queue), threads(name));
    }
    private static void cancel(Future<?> task) { if (task != null) task.cancel(false); }

    /**
     * 幂等关闭客户端，停止重连和心跳，终止连接并释放身份目录锁。
     *
     * <p>未完成请求和等待以 {@code CLIENT_CLOSED} 异常完成；拒绝新的后台任务并尝试
     * 中断本地命令处理线程。已经排队的回调可能继续执行，关闭也不保证撤销业务副作用。
     * 若设置了状态监听器，会在关闭调用线程同步通知 {@link State#CLOSED}。
     * 已关闭实例不能重新连接。
     */
    @Override public void close() {
        synchronized (lifecycle) {
            if (state == State.CLOSED) return;
            state = State.CLOSED;
            Wire current = wire; wire = null;
            if (current != null) current.close();
            cancel(heartbeat); cancel(heartbeatDeadline); cancel(authenticationDeadline); cancel(reconnect);
            CielException error = new CielException(CielException.Kind.CLOSED, "CLIENT_CLOSED");
            pending.forEach((id, request) -> request.result.completeExceptionally(error.forRequest(id, request.recordId, request.attempted.get())));
            waiters.forEach(waiter -> waiter.completeExceptionally(error));
            if (firstReady != null) firstReady.completeExceptionally(error);
            executions.values().forEach(execution -> execution.finished.set(true));
            executions.clear();
            store.close();
            clock.shutdownNow(); network.shutdownNow(); handlers.shutdownNow(); callbacks.shutdown();
            Consumer<State> listener = stateListener;
            if (listener != null) try { listener.accept(State.CLOSED); } catch (RuntimeException ignored) { }
        }
    }
}
