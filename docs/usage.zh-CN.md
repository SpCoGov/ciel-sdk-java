# CIEL Java SDK 使用指南

[English](usage.md) · [返回 README](../README.zh-CN.md)

## 环境要求

| 用途 | 要求 |
| --- | --- |
| 运行 SDK | Java 17 或更高版本 |
| 构建 SDK | JDK 25，使用仓库自带的 Gradle Wrapper |
| 运行两套测试 | JDK 17 和 JDK 25 |
| 连接 Ciel | 已运行的 Ciel 服务端，以及管理员创建的注册授权 |

运行时唯一的外部依赖是 **Gson 2.14.0**。HTTP、WebSocket、TLS、异步任务、文件锁及日志均使用 JDK 标准库。日志接口为 `System.Logger`，日志后端由宿主应用选择。

## 安装

依赖坐标：`top.spco.ciel:ciel-sdk-java:0.1.0`。

本地使用时，将 SDK 安装到**本机 Maven 仓库**。项目也提供用于手动上传 Maven Central 的签名打包 workflow；生成上传包不会发布依赖，操作步骤见[发布到 Maven Central](#发布到-maven-central)。

将 `JAVA_HOME` 指向 JDK 25，在 SDK 仓库根目录执行：

```powershell
.\gradlew.bat publishToMavenLocal
```

Linux 或 macOS 使用 `./gradlew publishToMavenLocal`。

在消费项目的 `build.gradle.kts` 中添加：

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("top.spco.ciel:ciel-sdk-java:0.1.0")
}
```

Gradle 会自动解析 Gson 的传递依赖。JUnit 仅用于 SDK 自身测试；使用 SDK 无需引入 Log4j、Spring 或 JNI。

## 发布到 Maven Central

[Build Maven Central bundle](../.github/workflows/central-bundle.yml) 是手动触发的 GitHub Actions workflow。它使用 Java 17 和 25 构建、测试，在内存中签名 Maven 发布文件，并生成可下载的 ZIP。它不会自动上传 Central 或发布版本，本机和 runner 都不需要安装 GPG。

1. 在可信环境中生成 PGP 签名密钥，将公钥发布到 [Central 支持的公钥服务器](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key)。已验证的 `top.spco` 命名空间涵盖本 SDK 的 `top.spco.ciel` 分组。
2. 在仓库的 **Settings → Secrets and variables → Actions** 中添加 `SIGNING_KEY`，内容为包含首尾标记的完整 ASCII-armored 私钥；添加 `SIGNING_PASSWORD` 保存私钥密码，未加密私钥可不设置此项。私钥放在 Secrets 中，不写入源码。这条手动上传流程不需要 Central Portal 发布令牌。
3. 将 SDK 源码和 workflow 推送到仓库默认分支。在 `build.gradle.kts` 中设置本次发布的版本号，然后选择 **Actions → Build Maven Central bundle → Run workflow**。
4. 在该次运行的 **Artifacts** 中下载 `ciel-sdk-java-<版本>-central.zip`。上传 action 直接提供该 ZIP，不会再套一层 ZIP。在 Central Portal 点击 **Publish Component**，上传此文件，检查验证结果，通过后发布。

ZIP 使用 Maven 仓库目录结构，例如 `top/spco/ciel/ciel-sdk-java/0.1.0/`，包含库、sources、Javadoc、POM、Gradle 模块元数据、PGP `.asc` 签名和校验文件。Gradle 生成并检查必需文件，workflow 还会检查 ZIP 完整性。详见 [Central 上传要求](https://central.sonatype.org/publish/publish-portal-upload/)。

同一任务也可通过 `./gradlew centralBundle` 执行，Windows 使用 `.\gradlew.bat centralBundle`，前提是进程环境提供 `SIGNING_KEY`，以及需要时的 `SIGNING_PASSWORD`。输出为 `build/central-bundle/ciel-sdk-java-<版本>-central.zip`。打包任务缺少签名凭据时会失败，普通构建和 `publishToMavenLocal` 不需要这些凭据。签名必须对应 ZIP 内的原始文件，签名完成后不要重建文件再上传。

发布 workflow 会在两套 Java 环境运行单元和 TLS 测试。真实服务集成测试需要 `cielServerBinary`，在此 workflow 中会跳过。版本发布到 Central 后，后续发布需使用新的版本号。

## 首次注册与连接

管理员在 Ciel 中创建注册授权，将 JSON 保存为 `grant.json`。授权包含服务器 WSS 地址、SPKI Pin、服务 ID 和一次性令牌。为该实例选择一个私有身份目录，授权和身份文件均不应提交到版本控制。

```java
import top.spco.ciel.CielClient;
import java.nio.file.Path;

Path directory = Path.of("private-worker");
CielClient.enroll(Path.of("grant.json"), directory).get();
try (CielClient client = CielClient.connect(directory).get()) {
    System.out.println(client.identity().instanceId());
}
```

新实例首次运行时执行 `enroll`，后续启动直接使用已有身份目录连接。已有正式身份时调用 `enroll` 会返回 `ALREADY_ENROLLED`。

注册先原子保存 `candidate-<instance_id>.json` 并刷盘，再在同一连接提交；确认后成为 `identity.json`。确认丢失时，重新执行 `enroll` 会先鉴权候选身份，恢复成功后不再消费令牌。

身份格式和 `agent.lock` 与 Rust 参考客户端一致，同一身份目录不能被两个进程同时使用。Unix 文件权限为 600、目录为 700；Windows ACL 仅允许当前用户。身份文件没有加密，目录应位于可信的本地文件系统。

SDK 校验服务地址、TLS 1.3、证书有效期、握手签名和 SPKI Pin。管理员授权中的 Pin 是信任来源，不要求公共 CA；连接不携带浏览器 Cookie 或 `Origin` 请求头。

## 事件

以下示例假设已有连接成功的 `CielClient client`。应先安装回调，再订阅事件：

```java
client.onEvent("demo.changed", event -> System.out.println(event.get("publication_id")));
client.registerEvent("demo.changed").get();
client.subscribeEvent("demo.changed").get();
client.publishEvent("demo.changed", com.google.gson.JsonParser.parseString("{\"value\":1}"),
        CielClient.newRequestId()).get();
client.unsubscribeEvent("demo.changed").get();
client.unregisterEvent("demo.changed").get();
```

注册和订阅关系由 Ciel 持久保存，重连不重放业务请求。`queued_count` 表示进入服务器队列的数量，不保证实际送达或处理。事件没有离线补发；重复发布会创建新的事件，即使使用同一个请求 ID。

## 命令

提供方应先安装处理函数，再连接和注册。处理函数在线程池运行，可以返回异步结果：

```java
try (CielClient provider = new CielClient(directory)) {
    provider.onCommand("demo.echo", execution -> java.util.concurrent.CompletableFuture.completedFuture(
            new CielClient.CommandResult(true, execution.input())));
    provider.connect().get();
    provider.registerCommand("demo.echo", "Return input JSON").get();
    // 保持提供方运行，直到应用退出。
    new java.util.concurrent.CountDownLatch(1).await();
}
```

使用 `unregisterCommand` 可以注销命令。管理员需先为调用实例授予目标命令的调用权限。调用方在发送前持久保存请求 ID 和参数：

```java
String requestId = CielClient.newRequestId(); // 发送前持久保存。
var accepted = client.invokeCommand(targetInstanceId, "demo.echo",
        com.google.gson.JsonParser.parseString("{\"value\":1}"), 30, requestId).get();
var result = client.awaitCommand(accepted.id(), java.time.Duration.ofSeconds(35)).get();
System.out.println(result.status());
```

`command_accepted` 表示受理，不代表执行成功。`awaitCommand` 查询已有调用，不再发起执行；也可使用 `getCommand` 或 `onCommandCompleted`。终态为 `SUCCEEDED`、`FAILED`、`UNKNOWN`。完成推送为尽力投递，查询是恢复途径。

断线或超时后，有调用 ID 就查询；没有调用 ID 时，可用**原请求 ID、完全相同的目标、命令、输入和超时**调用，恢复原记录。参数不同返回 `REQUEST_ID_CONFLICT`；记录已清理时返回 `CALL_RECORD_EXPIRED`，不会重新执行。

`UNKNOWN` 不能解释为业务没有产生副作用，重新执行必须由应用显式决定。执行结果绑定原连接，旧连接的迟到结果不会在新连接发送。deadline 和 Future 取消都不保证停止业务副作用。`awaitCommand` 会在断线或单次查询失败时结束，不会跨重连继续等待。

## 通知

```java
client.registerNotification("backup.done", "备份完成", "Backup status").get();
String requestId = CielClient.newRequestId(); // 发送前持久保存。
var result = client.sendNotification("backup.done", "备份完成", "数据已保存", requestId).get();
var latest = client.getNotification(result.get("id").getAsString()).get();
client.unregisterNotification("backup.done").get();
```

收件人和渠道由 Ciel 用户偏好决定。返回值保留 `recipient_count`、各渠道状态（`PENDING`、`SENDING`、`DELIVERED`、`SKIPPED`、`FAILED`）和原因码。邮件被 SMTP 接受不等于用户已读。没有通知完成推送；超时恢复使用原请求 ID、相同类型、标题和正文，或查询已知通知 ID。

## 错误、并发与关闭

业务操作返回 `CompletableFuture`。本地输入校验可能同步抛出 `CielException`；异步失败可从 `ExecutionException` 或 `CompletionException` 的 cause 读取。错误提供 `kind()`、`code()`、`requestId()`、`recordId()`、`sendStage()` 和 `retryable()`。

`ATTEMPTED` 表示已尝试发送，不能推断服务端没有执行；`NOT_SENT` 表示未交给 WebSocket 发送。`retryable()` 只描述连接恢复是否合理，不代表可以自动重放业务操作。

通过 `onState` 和 `onError` 观察连接。临时故障按 1、2、4、8、16、30 秒退避并加入抖动，尊重 `Retry-After`；鉴权、TLS、协议错误停止重连。`Options` 可调整连接、请求、心跳确认超时和最大重连延迟，默认分别为 10、15、10、30 秒。`connect()` 等待首次 `READY`；临时连接失败时持续重试，可取消该 Future 或关闭客户端。

默认容量限制：

| 资源 | 限制 |
| --- | --- |
| 发送速率 | 每秒 5 条，心跳优先 |
| 发送队列／在途请求 | 各 64 |
| 回调线程／队列 | 2 个线程／64 个排队任务 |
| 命令线程／并发执行 | 4 个线程／最多 16 个执行 |

超出容量返回明确错误；回调队列溢出时停止连接，避免默默丢弃推送。回调可能并发，不承诺完成顺序。用户回调应尽快返回；SDK 心跳独立运行。

取消尚未发送的 Future 会取消排队发送；已尝试发送的操作只取消本地等待。`close()` 终止连接、结束等待、停止 SDK 自建线程并释放身份锁，不会撤销业务副作用。默认日志只输出错误码，不输出令牌、凭据和业务内容。

## 构建与 Javadoc

将 `JAVA_HOME` 指向 JDK 25，并安装 JDK 17 用于测试。Gradle 无法自动找到两套 JDK 时，通过 `-Dorg.gradle.java.installations.paths` 指定路径。

```powershell
.\gradlew.bat build testJava25 compileExamplesJava
.\gradlew.bat javadoc
```

Linux 和 macOS：

```bash
./gradlew build testJava25 compileExamplesJava
./gradlew javadoc
```

生产代码按 `--release 17` 编译。`build/libs` 包含库、sources 和 Javadoc JAR；HTML Javadoc 位于 `build/docs/javadoc/index.html`。公共 API 文档目前使用中文，各方法的详细约定见 [CielClient.java](../src/main/java/top/spco/ciel/CielClient.java)。

Windows 的 `%TEMP%` 使用短路径别名时，JDK 25 可能报 `Unable to establish loopback connection`。遇到此问题，可创建较短的本地目录，将 Gradle 进程的 Unix-domain socket 临时文件放入该目录。以下 JDK 路径需替换为本机实际路径：

```powershell
New-Item -ItemType Directory -Force -Path 'C:\ciel-tmp' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\ciel-tmp'
.\gradlew.bat '-Dorg.gradle.java.installations.paths=C:\path\to\jdk-25,C:\path\to\jdk-17' build
```

SDK 不设置 JVM 全局参数。

## 可运行示例与集成测试

[Example.java](../examples/top/spco/ciel/examples/Example.java) 支持注册、提供服务和调用命令。请替换路径及目标实例 ID：

```powershell
.\gradlew.bat runExample --args='enroll C:\grants\worker.json C:\private-worker'
.\gradlew.bat runExample --args='serve C:\private-worker'
# 先注册另一个调用实例，并由管理员授予 demo.echo 调用权限。
.\gradlew.bat runExample --args='call C:\private-caller <target-instance-id> demo.echo null'
```

`serve` 演示事件收发、命令提供、通知发送与查询；`call` 演示受理与最终执行结果的区分。Linux 和 macOS 使用 `./gradlew` 及对应路径。

```powershell
.\gradlew.bat test
.\gradlew.bat '-PcielServerBinary=C:\path\to\ciel.exe' test testJava25
# 可选：加上 Rust 参考客户端，验证身份文件和文件锁互通。
.\gradlew.bat '-PcielServerBinary=C:\path\to\ciel.exe' '-PcielRustClientBinary=C:\path\to\test_client.exe' test testJava25
```

`test` 使用 Java 17，`testJava25` 使用 Java 25。TLS 测试通过 JDK 自带的 `keytool` 生成临时证书，验证错误 Pin、过期证书和 TLS 1.2 被拒绝，且拒绝前没有发送应用数据。

没有指定 `cielServerBinary` 时，真实服务集成测试会明确标记为跳过。指定后，测试自动创建临时数据目录并启动独立 Ciel 进程，验证注册恢复、身份锁与权限、JSON/UTF-8 边界、分片、队列与取消、事件、命令和通知去重、并发响应关联、重连，以及阻塞回调期间的心跳。测试会关闭自己的进程，不使用已有的 `ciel-data` 目录。

## 协议与范围

协议以 Ciel 服务端的 `PROTOCOL_SPEC.md` 和 `docs/*-payloads.md` 为准。本 SDK 实现服务 WSS 协议，目前没有管理 HTTP API、框架集成、离线队列或自动业务重放。

## 许可证

[Apache License 2.0](../LICENSE)。
