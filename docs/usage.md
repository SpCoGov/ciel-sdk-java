# CIEL Java SDK Guide

[简体中文](usage.zh-CN.md) · [Back to README](../README.md)

## Requirements

| Purpose | Requirement |
| --- | --- |
| Run the SDK | Java 17 or later |
| Build the SDK | JDK 25; use the included Gradle Wrapper |
| Run both test suites | JDK 17 and JDK 25 |
| Connect to Ciel | A running Ciel server and an administrator-issued enrollment grant |

The only external runtime dependency is **Gson 2.14.0**. HTTP, WebSocket, TLS, asynchronous tasks, file locks, and logging use the JDK. The logging API is `System.Logger`; the host application chooses its logging backend.

## Installation

Coordinates: `top.spco.ciel:ciel-sdk-java:0.1.0`.

For local use, install the SDK into **your local Maven repository**. The project also provides a signed bundle workflow for manual Maven Central upload; producing a bundle does not publish the artifact. See [Publishing to Maven Central](#publishing-to-maven-central).

Set `JAVA_HOME` to JDK 25, then run from the SDK repository root:

```powershell
.\gradlew.bat publishToMavenLocal
```

On Linux or macOS, use `./gradlew publishToMavenLocal`.

Add the dependency to the consuming project's `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("top.spco.ciel:ciel-sdk-java:0.1.0")
}
```

Gradle resolves Gson transitively. JUnit is used only for the SDK's tests; applications do not need Log4j, Spring, or JNI to use the SDK.

## Publishing to Maven Central

[Build Maven Central bundle](../.github/workflows/central-bundle.yml) is a manually triggered GitHub Actions workflow. It builds and tests on Java 17 and 25, signs the Maven publication in memory, and produces a downloadable ZIP. It does not upload to Central or release a version automatically. No GPG installation is needed on your computer or the runner.

1. Generate a PGP signing key in a trusted environment and publish its public key to a [Central-supported key server](https://central.sonatype.org/publish/requirements/gpg/#distributing-your-public-key). The verified `top.spco` namespace covers this SDK's `top.spco.ciel` group.
2. In the repository's **Settings → Secrets and variables → Actions**, add `SIGNING_KEY` containing the complete ASCII-armored private key, including its header and footer. Add `SIGNING_PASSWORD` for the private key's passphrase; it can be omitted for an unencrypted key. Keep private keys in Secrets, not in source files. This manual upload workflow does not need a Central Portal token.
3. Push the SDK source and workflow to the repository's default branch. Set the intended release version in `build.gradle.kts`, then select **Actions → Build Maven Central bundle → Run workflow**.
4. Download `ciel-sdk-java-<version>-central.zip` from the run's **Artifacts** section. The upload action delivers the ZIP directly, without an additional outer ZIP. On Central Portal, choose **Publish Component**, upload this file, inspect validation results, and publish the validated deployment.

The ZIP follows Maven repository layout, for example `top/spco/ciel/ciel-sdk-java/0.1.0/`. It contains the library, sources, Javadoc, POM, Gradle module metadata, PGP `.asc` signatures, and checksums. Gradle creates and checks the required files; the workflow also checks ZIP integrity. See the [Central upload requirements](https://central.sonatype.org/publish/publish-portal-upload/).

The same task is available as `./gradlew centralBundle` (Windows: `.\gradlew.bat centralBundle`) when `SIGNING_KEY` and, if needed, `SIGNING_PASSWORD` are provided in the process environment. It generates `build/central-bundle/ciel-sdk-java-<version>-central.zip`. Missing signing credentials fail the bundle task; ordinary builds and `publishToMavenLocal` work without them. Never sign one set of files and upload rebuilt files: the signatures must match the exact bytes in the ZIP.

The release workflow runs unit and TLS tests on both Java versions. Real-server integration tests require `cielServerBinary` and are skipped in this workflow. After a version is published to Central, use a new version for later releases.

## Enrollment and connection

Create an enrollment grant in Ciel as an administrator and save the JSON as `grant.json`. It contains the server WSS address, SPKI pin, service ID, and a one-time token. Choose a private identity directory for this instance. Keep the grant and identity files out of version control.

```java
import top.spco.ciel.CielClient;
import java.nio.file.Path;

Path directory = Path.of("private-worker");
CielClient.enroll(Path.of("grant.json"), directory).get();
try (CielClient client = CielClient.connect(directory).get()) {
    System.out.println(client.identity().instanceId());
}
```

Run `enroll` once for a new instance. On later starts, connect using the existing directory. Calling `enroll` with an active identity returns `ALREADY_ENROLLED`.

Enrollment atomically saves and flushes `candidate-<instance_id>.json` before committing on the same connection. After confirmation, it becomes `identity.json`. If confirmation is lost, rerunning `enroll` first authenticates the candidate to recover the identity instead of consuming the token again.

The identity format and `agent.lock` are shared with the Rust reference client. Only one process may use an identity directory at a time. Unix files use mode 600 and directories use mode 700; Windows ACLs allow only the current user. Identity files are not encrypted, so use a trusted local filesystem.

The SDK checks the service address, TLS 1.3, certificate validity, handshake signatures, and the SPKI pin. The administrator-issued pin is the trust source; a public CA is not required. Connections do not carry browser cookies or an `Origin` header.

## Events

The following examples assume an open `CielClient client`. Install the callback before subscribing:

```java
client.onEvent("demo.changed", event -> System.out.println(event.get("publication_id")));
client.registerEvent("demo.changed").get();
client.subscribeEvent("demo.changed").get();
client.publishEvent("demo.changed", com.google.gson.JsonParser.parseString("{\"value\":1}"),
        CielClient.newRequestId()).get();
client.unsubscribeEvent("demo.changed").get();
client.unregisterEvent("demo.changed").get();
```

Registration and subscription relationships are persisted by Ciel. Reconnection does not replay business requests. `queued_count` counts server queue insertions, not confirmed delivery or processing. Events have no offline catch-up; republishing creates another event even when the same request ID is reused.

## Commands

A provider should install its handler before connecting and registering. Handlers run in a worker pool and can return asynchronous results:

```java
try (CielClient provider = new CielClient(directory)) {
    provider.onCommand("demo.echo", execution -> java.util.concurrent.CompletableFuture.completedFuture(
            new CielClient.CommandResult(true, execution.input())));
    provider.connect().get();
    provider.registerCommand("demo.echo", "Return input JSON").get();
    // Keep the provider running until the application shuts down.
    new java.util.concurrent.CountDownLatch(1).await();
}
```

Use `unregisterCommand` to remove a registration. An administrator must grant the calling instance permission to invoke the target command. Persist the request ID and parameters before sending:

```java
String requestId = CielClient.newRequestId(); // Persist before sending.
var accepted = client.invokeCommand(targetInstanceId, "demo.echo",
        com.google.gson.JsonParser.parseString("{\"value\":1}"), 30, requestId).get();
var result = client.awaitCommand(accepted.id(), java.time.Duration.ofSeconds(35)).get();
System.out.println(result.status());
```

`command_accepted` means accepted, not successfully executed. `awaitCommand` queries an existing call and does not invoke it again. You can also use `getCommand` or `onCommandCompleted`. Terminal statuses are `SUCCEEDED`, `FAILED`, and `UNKNOWN`. Completion pushes are best effort; queries provide recovery.

After a timeout or disconnect, query a known call ID. If the call ID is unknown, retry with the **original request ID and exactly the same target, command, input, and timeout** to recover the original record. Changed parameters return `REQUEST_ID_CONFLICT`; a cleaned-up record returns `CALL_RECORD_EXPIRED` without executing again.

`UNKNOWN` does not mean that the operation had no effects. An application must explicitly decide whether to execute again. Execution results are bound to the original connection; late results from an old connection are not sent through a new one. Deadlines and Future cancellation do not guarantee that business side effects stop. `awaitCommand` ends on disconnection or a query failure; it does not resume across reconnections.

## Notifications

```java
client.registerNotification("backup.done", "Backup completed", "Backup status").get();
String requestId = CielClient.newRequestId(); // Persist before sending.
var result = client.sendNotification("backup.done", "Backup completed", "Data saved", requestId).get();
var latest = client.getNotification(result.get("id").getAsString()).get();
client.unregisterNotification("backup.done").get();
```

Recipients and channels come from Ciel user preferences. Responses preserve `recipient_count`, per-channel statuses (`PENDING`, `SENDING`, `DELIVERED`, `SKIPPED`, `FAILED`), and reason codes. SMTP acceptance does not mean the user has read the email. There is no notification completion push. Recover after a timeout with the original request ID and identical type, title, and body, or query a known notification ID.

## Errors, concurrency, and shutdown

Business operations return `CompletableFuture`. Local input validation can throw `CielException` synchronously; asynchronous failures are available through the cause of `ExecutionException` or `CompletionException`. Errors expose `kind()`, `code()`, `requestId()`, `recordId()`, `sendStage()`, and `retryable()`.

`ATTEMPTED` means a send was attempted and cannot prove that the server did not execute the operation. `NOT_SENT` means the request was not handed to WebSocket sending. `retryable()` describes connection recovery, not permission to replay business operations automatically.

Observe the connection with `onState` and `onError`. Temporary failures use backoff of 1, 2, 4, 8, 16, and 30 seconds with jitter and respect `Retry-After`. Authentication, TLS, and protocol errors stop reconnection. `Options` configures connection, request, heartbeat acknowledgment, and maximum reconnect delays; defaults are 10, 15, 10, and 30 seconds. `connect()` waits for the first `READY` state and keeps retrying temporary failures until its Future is cancelled or the client is closed.

Default capacity limits:

| Resource | Limit |
| --- | --- |
| Send rate | 5 messages per second, with heartbeat priority |
| Send queue / outstanding requests | 64 each |
| Callback workers / queue | 2 workers / 64 queued tasks |
| Command workers / concurrent executions | 4 workers / up to 16 executions |

Capacity exhaustion produces explicit errors. Callback queue overflow stops the connection instead of silently dropping pushes. Callbacks may run concurrently and have no guaranteed completion order. Keep callbacks short; heartbeat runs independently.

Cancelling an unsent Future removes its queued send; cancelling an attempted operation stops only local waiting. `close()` ends the connection, completes pending waits, stops SDK-owned workers, and releases the identity lock. It does not undo business side effects. Default logs contain error codes and exclude tokens, credentials, and business content.

## Build and Javadoc

Configure `JAVA_HOME` for JDK 25 and install JDK 17 for tests. If Gradle cannot discover both installations, pass `-Dorg.gradle.java.installations.paths` with their paths.

```powershell
.\gradlew.bat build testJava25 compileExamplesJava
.\gradlew.bat javadoc
```

Linux and macOS:

```bash
./gradlew build testJava25 compileExamplesJava
./gradlew javadoc
```

Production classes target Java 17 through `--release 17`. `build/libs` contains the library, sources, and Javadoc JARs; HTML Javadoc is generated at `build/docs/javadoc/index.html`. Public API documentation currently uses Chinese. See [CielClient.java](../src/main/java/top/spco/ciel/CielClient.java) for individual method contracts.

On Windows, a short-path `%TEMP%` alias can cause JDK 25 to report `Unable to establish loopback connection`. If this occurs, create a short local directory and point Unix-domain socket temporary files to it for the Gradle process. Replace the example paths with your JDK locations:

```powershell
New-Item -ItemType Directory -Force -Path 'C:\ciel-tmp' | Out-Null
$env:JAVA_TOOL_OPTIONS = '-Djdk.net.unixdomain.tmpdir=C:\ciel-tmp'
.\gradlew.bat '-Dorg.gradle.java.installations.paths=C:\path\to\jdk-25,C:\path\to\jdk-17' build
```

The SDK does not set global JVM options.

## Runnable example and integration tests

[Example.java](../examples/top/spco/ciel/examples/Example.java) supports enrollment, providing services, and invoking a command. Replace the paths and target instance ID:

```powershell
.\gradlew.bat runExample --args='enroll C:\grants\worker.json C:\private-worker'
.\gradlew.bat runExample --args='serve C:\private-worker'
# Enroll a separate caller and grant it permission to invoke demo.echo first.
.\gradlew.bat runExample --args='call C:\private-caller <target-instance-id> demo.echo null'
```

`serve` demonstrates events, command handling, notification sending, and status queries. `call` distinguishes acceptance from the final execution result. Linux and macOS use `./gradlew` with their own paths.

```powershell
.\gradlew.bat test
.\gradlew.bat '-PcielServerBinary=C:\path\to\ciel.exe' test testJava25
# Optional: also check identity file and lock interoperability with Rust.
.\gradlew.bat '-PcielServerBinary=C:\path\to\ciel.exe' '-PcielRustClientBinary=C:\path\to\test_client.exe' test testJava25
```

`test` uses Java 17; `testJava25` uses Java 25. TLS tests use the JDK's `keytool` to generate temporary certificates and check rejection of a wrong pin, an expired certificate, and TLS 1.2 before application data is sent.

Real-server integration tests are explicitly skipped unless `cielServerBinary` is supplied. When supplied, tests create temporary data directories and start an isolated Ciel process. They cover enrollment recovery, identity locks and permissions, JSON/UTF-8 boundaries, fragmentation, queues and cancellation, events, command and notification deduplication, concurrent response correlation, reconnection, and heartbeat while callbacks are blocked. Tests stop their own processes and do not use an existing `ciel-data` directory.

## Protocol and scope

The Ciel server's `PROTOCOL_SPEC.md` and `docs/*-payloads.md` are the protocol source of truth. This SDK covers the service WSS protocol. It does not currently provide management HTTP APIs, framework integrations, offline queues, or automatic replay of business requests.

## License

[Apache License 2.0](../LICENSE).
