package top.spco.ciel;

/**
 * SDK 本地错误或 Ciel 服务端错误，保留请求关联信息以便调用方明确恢复原操作。
 *
 * <p>输入校验和身份加载错误通常同步抛出；网络请求失败通过 Future 异常完成。
 * 使用 {@code get()} 或 {@code join()} 时，应从包装异常的 cause 中取得本异常。
 * {@link #retryable()} 只描述连接恢复是否合理，不能据此自动重发事件、创建新命令或通知。
 *
 * <p>异常消息和 {@link #toString()} 只包含错误码或分类，不包含凭据和业务 JSON。
 * 请求 ID、记录 ID 可能为空；{@link #sendStage()} 用来判断是否已尝试交给 WebSocket 发送，
 * 不表示远端执行或确认状态。
 */
public final class CielException extends RuntimeException {
    /** 错误的来源和处理类别，与服务端业务错误码相互独立。 */
    public enum Kind {
        /** 本地输入字段、格式或大小不符合要求。 */
        INPUT,
        /** 身份文件、权限、原子保存或身份目录锁失败。 */
        STORAGE,
        /** TLS 协商、证书有效期或 SPKI Pin 验证失败。 */
        TLS,
        /** 网络连接失效，或客户端当前未就绪。 */
        CONNECTION,
        /** 连接、鉴权、写入、请求或本地结果等待超时。 */
        TIMEOUT,
        /** 服务端封包格式无效、类型未知或协议版本不支持。 */
        PROTOCOL,
        /** Ciel 返回的错误码或不可重试的 HTTP 握手错误。 */
        SERVER,
        /** 有界发送、请求、回调或等待容量已耗尽。 */
        CAPACITY,
        /** 客户端已关闭，或失败实例被要求重新连接。 */
        CLOSED
    }

    /** 请求的本地发送阶段；不承诺服务端是否已经产生业务副作用。 */
    public enum SendStage {
        /** 尚未尝试交给 WebSocket 发送。 */
        NOT_SENT,
        /** 已调用 WebSocket 发送；远端是否收到、执行或持久提交仍可能不确定。 */
        ATTEMPTED
    }

    /** 序列化时保留的错误分类。 */
    private final Kind kind;
    /** 序列化时保留的本地或服务端错误码。 */
    private final String code;
    /** 已知请求 ID；非请求错误可为空。 */
    private final String requestId;
    /** 已知业务记录 ID；不可用时为空。 */
    private final String recordId;
    /** 本地请求发送阶段。 */
    private final SendStage sendStage;

    CielException(Kind kind, String code) { this(kind, code, null, null, SendStage.NOT_SENT, null); }

    CielException(Kind kind, String code, Throwable cause) {
        this(kind, code, null, null, SendStage.NOT_SENT, cause);
    }

    CielException(Kind kind, String code, String requestId, String recordId, SendStage stage, Throwable cause) {
        super(code, cause);
        this.kind = kind;
        this.code = code;
        this.requestId = requestId;
        this.recordId = recordId;
        this.sendStage = stage;
    }

    /**
     * 读取错误的来源分类。
     * @return 本地输入、存储、连接、协议或服务端等错误分类
     */
    public Kind kind() { return kind; }
    /**
     * 读取可供应用判断恢复策略的原因码。
     * @return 原样保留的服务端错误码或 SDK 本地错误码，例如 {@code REQUEST_ID_CONFLICT}
     */
    public String code() { return code; }
    /**
     * 读取与失败请求关联的标识。
     * @return 与失败请求关联的请求 ID；连接级或未登记请求错误可为 Java {@code null}
     */
    public String requestId() { return requestId; }
    /**
     * 读取可用于后续状态查询的业务记录标识。
     * @return 已知调用 ID 或通知 ID；原操作尚未取得记录 ID 时为 Java {@code null}
     */
    public String recordId() { return recordId; }
    /**
     * 读取本地发送进度，不推断服务端执行状态。
     * @return 请求的本地发送阶段，不能据此把未确认操作当作未执行
     */
    public SendStage sendStage() { return sendStage; }

    CielException forRequest(String id, String record, boolean attempted) {
        return new CielException(kind, code, id, record,
                attempted ? SendStage.ATTEMPTED : SendStage.NOT_SENT, getCause());
    }

    /**
     * 判断连接恢复是否合理，不授权业务请求重放。
     *
     * <p>连接和超时类错误返回 {@code true}；服务端错误仅对临时占用、限流和连接类错误码
     * 返回 {@code true}。实际重连仍由客户端生命周期管理；调用方恢复命令或通知时必须
     * 遵守原请求 ID 和相同参数的规则，事件发布不能自动重试。
     *
     * @return 连接恢复可考虑重试时为 {@code true}，否则为 {@code false}
     */
    public boolean retryable() {
        return kind == Kind.CONNECTION || kind == Kind.TIMEOUT ||
                (kind == Kind.SERVER && switch (code) {
                    case "INSTANCE_ALREADY_CONNECTED", "RATE_LIMITED", "CONNECTION_TIMEOUT",
                         "CONNECTION_CLOSED", "WRITE_TIMEOUT" -> true;
                    default -> false;
                });
    }

    /**
     * 生成脱敏错误说明。
     * @return 仅包含错误分类和错误码的说明，不包含请求封包或业务数据
     */
    @Override public String toString() { return "CielException[" + kind + ", " + code + "]"; }
}
