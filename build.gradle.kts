plugins {
    id("java")
}

group = "org.glavo"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.8.1")
}

val moduleName = "org.glavo.jmod.fallback"

val needExports = listOf(
    "java.base/jdk.internal.module",
    "java.base/jdk.internal.jmod",
    "java.base/jdk.internal.jimage",
    "java.base/jdk.internal.loader",
    "java.base/jdk.internal.org.objectweb.asm",
    "jdk.jlink/jdk.tools.jlink.builder",
    "jdk.jlink/jdk.tools.jlink.plugin",
    "jdk.jlink/jdk.tools.jlink.internal",
    "jdk.jlink/jdk.tools.jlink.internal.plugins",
)

tasks.compileJava {
    sourceCompatibility = "11"
    targetCompatibility = "11"
    options.compilerArgs.addAll(needExports.flatMap { listOf("--add-exports", "$it=$moduleName") })
}

tasks.jar {
    manifest {
        attributes(
            "Main-Class" to "org.glavo.jmod.fallback.Main",
            "Add-Exports" to needExports.joinToString(" "),
            "Add-Opens" to needExports.joinToString(" "),
        )
    }
}

tasks.test {
    useJUnitPlatform()
}