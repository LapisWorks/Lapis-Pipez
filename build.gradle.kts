import net.minecrell.pluginyml.bukkit.BukkitPluginDescription

plugins {
    kotlin("jvm") version "2.3.10"
    idea
    id("com.gradleup.shadow") version "9.2.2"
    id("net.minecrell.plugin-yml.bukkit") version "0.6.0"
    id("xyz.jpenilla.run-paper") version "2.3.0"
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = project.properties["group"]!!

repositories {
    mavenCentral()
    maven("https://central.sonatype.com/repository/maven-snapshots/")
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc"
    }
    maven("https://repo.xenondevs.xyz/releases") {
        name = "InvUI"
    }
    maven("https://repo.codemc.io/repository/maven-releases/") {
        name = "CodeMC"
    }
}

val rebarVersion = project.properties["rebar.version"] as String
val minecraftVersion = project.properties["minecraft.version"] as String

dependencies {
    // Paper 开发包（API + NMS）
    paperweight.paperDevBundle("$minecraftVersion.build.+")

    // Rebar：compileOnly，运行时由服务器上的 Rebar 插件提供
    compileOnly("io.github.pylonmc:rebar:$rebarVersion")

    // 界面库（可选，不用 GUI 可以删掉）
    compileOnly("xyz.xenondevs.invui:invui:2.1.1")

    compileOnly(kotlin("stdlib"))

    testImplementation(kotlin("test-junit5"))
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

idea {
    module {
        isDownloadJavadoc = true
        isDownloadSources = true
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    jvmToolchain(25)
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
    options.release.set(25)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.fromTarget("25"))
    }
}

tasks.processResources {
    filteringCharset = "UTF-8"
}

tasks.shadowJar {
    archiveClassifier = ""
}

bukkit {
    name = project.name
    main = project.properties["main-class"] as String
    version = project.version.toString()
    apiVersion = "26.2"
    depend = listOf("Rebar")
    load = BukkitPluginDescription.PluginLoadOrder.STARTUP

    authors = listOf("mc506lw")
}

paperweight {
    reobfArtifactConfiguration = io.papermc.paperweight.userdev.ReobfArtifactConfiguration.MOJANG_PRODUCTION
}

tasks.test {
    useJUnitPlatform()
}
