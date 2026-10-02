/**
 * Ciel v1 服务接入 SDK，最低运行环境为 Java 17。
 *
 * <p>{@link top.spco.ciel.CielClient} 提供注册、连接、事件、命令和通知接口。
 * 连接使用 TLS 1.3 和管理员授权中固定的 SPKI Pin，不携带浏览器 Cookie 或 Origin。
 * 身份文件与 Rust 参考客户端兼容，通过操作系统文件权限和共享文件锁保护，文件内容未加密。
 *
 * <p>除同步输入校验外，业务操作通过 {@link java.util.concurrent.CompletableFuture} 返回。
 * 所有接口的参数、结果字段、取消语义和恢复规则见各方法说明；错误分类、原因码和发送阶段
 * 见 {@link top.spco.ciel.CielException}。临时连接故障可以自动重连，业务操作不自动重放。
 *
 * <p>命令提供方应在连接前安装处理函数：
 * <pre>{@code
 * try (CielClient client = new CielClient(identityDirectory)) {
 *     client.onCommand("demo.echo", execution -> CompletableFuture.completedFuture(
 *             new CielClient.CommandResult(true, execution.input())));
 *     client.connect().get();
 *     client.registerCommand("demo.echo", "Return input JSON").get();
 *     // 应用保持运行，退出时关闭客户端。
 * }
 * }</pre>
 *
 * @since 0.1.0
 */
package top.spco.ciel;
