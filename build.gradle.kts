plugins {
    `java-library`
    `maven-publish`
    signing
}

group = "top.spco.ciel"
version = "0.1.0"

repositories { mavenCentral() }

java {
    toolchain { languageVersion.set(JavaLanguageVersion.of(25)) }
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<Jar>().configureEach {
    from("LICENSE") { into("META-INF") }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
    options.encoding = "UTF-8"
}

tasks.javadoc {
    options.encoding = "UTF-8"
    (options as StandardJavadocDocletOptions).apply {
        docEncoding = "UTF-8"
        charSet = "UTF-8"
        addBooleanOption("Xdoclint:all", true)
        addBooleanOption("Werror", true)
    }
}

dependencies {
    api("com.google.code.gson:gson:2.14.0")
    testImplementation(platform("org.junit:junit-bom:6.0.0"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    providers.gradleProperty("cielServerBinary").orNull?.let { systemProperty("ciel.server.binary", it) }
    providers.gradleProperty("cielRustClientBinary").orNull?.let { systemProperty("ciel.rust.client.binary", it) }
    testLogging { events("passed", "skipped", "failed") }
}

tasks.test {
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}

tasks.register<Test>("testJava25") {
    description = "Run the same checks on Java 25"
    group = "verification"
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(25)) })
    shouldRunAfter(tasks.test)
}

val examples = sourceSets.create("examples") {
    java.setSrcDirs(listOf("examples"))
    compileClasspath += sourceSets.main.get().output + configurations.runtimeClasspath.get()
    runtimeClasspath += output + compileClasspath
}

tasks.register<JavaExec>("runExample") {
    group = "application"
    description = "Enroll, serve, or call using the SDK (--args)"
    dependsOn(examples.classesTaskName)
    classpath = examples.runtimeClasspath
    mainClass.set("top.spco.ciel.examples.Example")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(17)) })
}

publishing {
    repositories {
        maven {
            name = "centralBundle"
            url = layout.buildDirectory.dir("central-repository").get().asFile.toURI()
        }
    }
    publications {
        create<MavenPublication>("sdk") {
            from(components["java"])
            artifactId = "ciel-sdk-java"
            pom {
                name.set("Ciel Java SDK")
                description.set("Pinned WSS service client for the Ciel v1 protocol")
                url.set("https://github.com/SpCoGov/ciel-sdk-java")
                licenses {
                    license {
                        name.set("The Apache License, Version 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                        distribution.set("repo")
                    }
                }
                developers {
                    developer {
                        id.set("SpCoGov")
                        name.set("SpongeCouna")
                        url.set("https://github.com/SpCoGov")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/SpCoGov/ciel-sdk-java.git")
                    developerConnection.set("scm:git:ssh://git@github.com/SpCoGov/ciel-sdk-java.git")
                    url.set("https://github.com/SpCoGov/ciel-sdk-java")
                }
            }
        }
    }
}

signing {
    setRequired({
        gradle.taskGraph.hasTask(":centralBundle") ||
            gradle.taskGraph.hasTask(":publishSdkPublicationToCentralBundleRepository")
    })
    useInMemoryPgpKeys(
        providers.environmentVariable("SIGNING_KEY").orNull,
        providers.environmentVariable("SIGNING_PASSWORD").orNull
    )
    sign(publishing.publications["sdk"])
}

val sdkPublication = publishing.publications["sdk"] as MavenPublication
val centralArtifactPath = "${sdkPublication.groupId.replace('.', '/')}/${sdkPublication.artifactId}/${sdkPublication.version}"

tasks.register<Zip>("centralBundle") {
    group = "publishing"
    description = "Create a signed Maven Central bundle for manual upload"
    dependsOn("publishSdkPublicationToCentralBundleRepository")
    destinationDirectory.set(layout.buildDirectory.dir("central-bundle"))
    archiveFileName.set("${sdkPublication.artifactId}-${sdkPublication.version}-central.zip")
    from(layout.buildDirectory.dir("central-repository/$centralArtifactPath")) {
        into(centralArtifactPath)
        exclude("*.asc.md5", "*.asc.sha1", "*.asc.sha256", "*.asc.sha512")
    }
    doFirst {
        val directory = layout.buildDirectory.dir("central-repository/$centralArtifactPath").get().asFile
        val prefix = "${sdkPublication.artifactId}-${sdkPublication.version}"
        for (artifact in listOf(".jar", "-sources.jar", "-javadoc.jar", ".pom", ".module")) {
            for (suffix in listOf("", ".asc", ".md5", ".sha1")) {
                val file = directory.resolve("$prefix$artifact$suffix")
                check(file.isFile && file.length() > 0) { "Missing Central bundle file: ${file.name}" }
            }
        }
    }
}
