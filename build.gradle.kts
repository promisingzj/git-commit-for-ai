plugins {
    java
    id("org.jetbrains.intellij.platform") version "2.19.0"
}

group = "dev.gitcommitforai"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform { defaultRepositories() }
}

dependencies {
    intellijPlatform {
        val installedIde = providers.gradleProperty("localIdePath")
        if (installedIde.isPresent) local(installedIde.get()) else intellijIdeaCommunity("2024.3.6")
        bundledPlugin("Git4Idea")
    }
}

java { toolchain { languageVersion = JavaLanguageVersion.of(21) } }
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
tasks.processResources { from("LICENSE") { into("META-INF") } }
intellijPlatform {
    pluginConfiguration {
        name = "git commit for ai"
        version = project.version.toString()
        ideaVersion { sinceBuild = "243" }
    }
}

