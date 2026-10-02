[English](README.md)

<h1 align="center">
  <br>
  <a href="https://github.com/SpCoGov/ciel-sdk-java"><img src="assets/logo.svg" alt="CIEL logo" width="150"></a>
  <br>
  CIEL Java SDK
  <br>
</h1>

<h4 align="center">让 Java 服务接入 CIEL。</h4>

<p align="center">
  <a href="https://github.com/SpCoGov/ciel-sdk-java">仓库</a> •
  <a href="docs/usage.zh-CN.md">文档</a> •
  <a href="examples/top/spco/ciel/examples/Example.java">示例</a> •
  <a href="https://github.com/SpCoGov/ciel-sdk-java/issues">问题反馈</a> •
  <a href="https://github.com/SpCoGov/ciel-sdk-rust">Rust SDK</a>
</p>

## 🛠️ 快速开始

运行需要 **Java 17+**，源码构建需要 **JDK 25** 和仓库自带的 Gradle Wrapper。依赖坐标为 `top.spco.ciel:ciel-sdk-java:0.1.0`，目前尚未发布到 Maven Central。

克隆仓库，将 SDK 安装到本机 Maven 仓库：

```sh
git clone https://github.com/SpCoGov/ciel-sdk-java
cd ciel-sdk-java
./gradlew publishToMavenLocal
```

Windows 使用 `.\gradlew.bat publishToMavenLocal`。构建前将 `JAVA_HOME` 指向 JDK 25。

在应用的 `build.gradle.kts` 中添加：

```kotlin
repositories {
    mavenLocal()
    mavenCentral()
}

dependencies {
    implementation("top.spco.ciel:ciel-sdk-java:0.1.0")
}
```

在 CIEL WebUI 中由管理员为服务 ID 签发注册授权，将 JSON 保存为 `grant.json`。首次运行注册并保存实例身份：

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

以后启动时直接使用已有身份目录连接，不再重复注册。请私密保存授权和身份文件。事件、命令、通知及恢复规则见[使用指南](docs/usage.zh-CN.md)。

## ⚙️ 构建

```sh
./gradlew build testJava25 compileExamplesJava
./gradlew javadoc
```

构建需要 JDK 25，运行两套测试还需要 JDK 17。Windows 将 `./gradlew` 换成 `.\gradlew.bat`。JAR 输出到 `build/libs`，API 文档输出到 `build/docs/javadoc/index.html`。

工具链配置与隔离 CIEL 集成测试见[构建和测试说明](docs/usage.zh-CN.md#构建与-javadoc)。手动发布可使用 [Maven Central 打包 workflow](.github/workflows/central-bundle.yml)，步骤见[发布说明](docs/usage.zh-CN.md#发布到-maven-central)。

## 🚀 贡献

通过 [Issues](https://github.com/SpCoGov/ciel-sdk-java/issues) 反馈问题，或提交 [Pull Request](https://github.com/SpCoGov/ciel-sdk-java/pulls)。报告缺陷时请附最小复现，提交修改前运行相关检查，并同步更新中英文文档。

## ⚗️ 技术栈

Java 标准库 + Gradle + [Gson](https://github.com/google/gson)。Gson 是唯一的外部运行依赖；日志使用 `System.Logger`，后端由应用选择。

## 📜 许可证

[Apache License 2.0](LICENSE)。
