// 仓库只有一个 Gradle 工程，就在这里；不叫 `app/`，因为 Rust 那边才是主体。
// 版本全在根 build.gradle.kts 里，模块里不写版本号（D110）。
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    // 依赖版本只认根里的声明：模块里再写一份 maven 源会让"同一份代码在两台机器上解析出不同版本"。
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "NMoi"
include(":app")
