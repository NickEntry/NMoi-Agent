// 版本钉子见 docs/android.md：这三个数有交叉约束，动一个要重算另外两个。
//   AGP 8.5.2  → 要求 Gradle >= 8.7、JDK 17
//   KGP 2.0.21 → 支持 Gradle 6.8.3-8.8、AGP 7.1.3-8.5
//   所以 Gradle 只能取 8.7 或 8.8；取 8.7 = AGP 的下限。
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.21" apply false
    // Kotlin 2.0 起 Compose 编译器是独立插件，版本必须等于 Kotlin 版本，不是 Compose 的版本。
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.21" apply false
}
