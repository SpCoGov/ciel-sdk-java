[简体中文](README.zh-CN.md)

<h1 align="center">
  <br>
  <a href="https://github.com/SpCoGov/ciel-sdk-java"><img src="assets/logo.svg" alt="CIEL logo" width="150"></a>
  <br>
  CIEL Java SDK
  <br>
</h1>

<h4 align="center">Connect Java services to CIEL.</h4>

<p align="center">
  <a href="https://github.com/SpCoGov/ciel-sdk-java">Repository</a> •
  <a href="docs/usage.md">Documentation</a> •
  <a href="examples/top/spco/ciel/examples/Example.java">Example</a> •
  <a href="https://github.com/SpCoGov/ciel-sdk-java/issues">Issues</a> •
  <a href="https://github.com/SpCoGov/ciel-sdk-rust">Rust SDK</a>
</p>

## 🛠️ Getting started

Requires **Java 17+**. Build from source with **JDK 25** and the included Gradle Wrapper. The Maven coordinate is `top.spco.ciel:ciel-sdk-java:0.1.0`; the SDK has not yet been published to Maven Central.

Clone the repository and install it into your local Maven repository:

```sh
git clone https://github.com/SpCoGov/ciel-sdk-java
cd ciel-sdk-java
./gradlew publishToMavenLocal
```

On Windows, use `.\gradlew.bat publishToMavenLocal`. Set `JAVA_HOME` to JDK 25 before building.

Add the dependency to your application's `build.gradle.kts`:

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("top.spco.ciel:ciel-sdk-java:0.1.0")
}
```

In CIEL WebUI, have an administrator issue an enrollment grant for your service ID and save it as `grant.json`. Enroll once to create the instance identity:

```java
import top.spco.ciel.CielClient;
import java.nio.file.Path;

public class QuickStart {
    public static void main(String[] args) throws Exception {
        Path directory = Path.of("worker-identity");
        CielClient.enroll(Path.of("grant.json"), directory).get();
        try (CielClient client = CielClient.connect(directory).get()) {
            System.out.println(client.identity().instanceId());
        }
    }
}
```

On subsequent starts, connect using the existing directory. Keep grants and identity files private. For events, commands, notifications, and recovery rules, see the [usage guide](docs/usage.md).

## ⚙️ Build

```sh
./gradlew build testJava25 compileExamplesJava
./gradlew javadoc
```

Building requires JDK 25; running both test suites also requires JDK 17. On Windows, replace `./gradlew` with `.\gradlew.bat`. JARs are generated in `build/libs`, and API documentation in `build/docs/javadoc/index.html`.

The [build and test guide](docs/usage.md#build-and-javadoc) covers toolchain configuration and isolated CIEL integration tests. For manual releases, use the [Maven Central bundle workflow](.github/workflows/central-bundle.yml) and follow the [publishing instructions](docs/usage.md#publishing-to-maven-central).

## 🚀 Contributing

Report problems through [Issues](https://github.com/SpCoGov/ciel-sdk-java/issues) or submit a [pull request](https://github.com/SpCoGov/ciel-sdk-java/pulls). Include a minimal reproduction for bugs and run the relevant checks before submitting changes. Keep the English and Chinese documentation in sync.

## ⚗️ Stack

Java standard library + Gradle + [Gson](https://github.com/google/gson). Gson is the only external runtime dependency; logging uses `System.Logger`, with the backend chosen by your application.

## 📜 License

[Apache License 2.0](LICENSE).
