import org.gradle.api.tasks.Sync
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// 第三方声明随每个产物走（D106）：在**签名之前**就把声明放进 assets/notices/。
// 目录是 Gradle 生成的，不提交副本——副本迟早和 THIRD-PARTY-NOTICES.md 漂移，
// 到时候"声明里写着"和"包里真正带的"就是两份东西了。
val repoRoot = rootProject.projectDir.parentFile
// 注意层级：`assets.srcDir(X)` 里的 X **就是** assets 根，所以文件必须放在 X/notices/ 下，
// 进包才是 assets/notices/…。第一版把它放到 X 根上，结果进包成了 assets/THIRD-PARTY-NOTICES.md
// ——闸当场报了"缺少 assets/notices/…"，这就是那道闸存在的理由。
val noticesAssetsRoot = layout.buildDirectory.dir("generated/assets")
val syncNotices by tasks.registering(Sync::class) {
    from(repoRoot.resolve("THIRD-PARTY-NOTICES.md")) { into("notices") }
    from(repoRoot.resolve("LICENSES")) { into("notices/LICENSES") }
    into(noticesAssetsRoot)
}

android {
    // D116：namespace 就是 Kotlin 包名，全小写；applicationId 也用它，三者完全一致。
    // （以前 namespace 与 applicationId 故意大小写不同，结果 alias 类名按错的拼了就闪退。）
    namespace = "com.nmoi.agent"
    compileSdk = 34

    defaultConfig {
        // D116：applicationId 改成全小写 com.nmoi.agent，与 namespace / Kotlin 包名完全一致。
        // D102 原本写着"同一阶段内不许换"——这次是有意换的一次：两个名字大小写不同，
        // 只要有一处按错的那个拼（JNI 别名、深链、渠道配置……）就是运行期才炸。
        // 代价是换了一个装机身份：旧包必须卸载重装，数据全丢（现在是骨架阶段，库里本来没数据）。
        // **从此以后这个名字不能再动**（要动就得再卸载一次，还得处理老用户的迁移）。
        applicationId = "com.nmoi.agent"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.0.1-dev"
    }

    // 声明进 assets/notices/（由上面的 syncNotices 生成）。assets 在合并阶段就进包，
    // 签名只是对最终 zip 盖章，所以"签名前打包"这件事在这里天然成立——
    // 真正要防的是"忘了放"和"放了个旧副本"，前者由这条 srcDir 保证，后者由不提交副本保证。
    sourceSets.getByName("main").assets.srcDir(noticesAssetsRoot)

    buildFeatures {
        compose = true
        // 骨架阶段没有 BuildConfig 需求；开着会随包带一份常量，关掉等真要用再开。
        buildConfig = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        debug {
            // 不混淆：骨架阶段要能看懂堆栈，混淆留到 release 那一版再说。
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            // 签名不在这里写死：材料只从 CI 的 environment secret 进 $RUNNER_TEMP（D65 / docs/signing.md）。
            // 现在没有任何签名材料，所以 release 只能出 unsigned，出包走 debug。
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    // Compose 用 BOM 管版本，下面这些不许写版本号。
    implementation(platform("androidx.compose:compose-bom:2024.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.2")
    // 显式声明 androidx.core（跟协程那条同一个理由）：`WindowCompat` 在这里，
    // 状态栏图标深浅要靠它。现在编得过只是因为它被 activity-compose 顺带拖进来了。
    implementation("androidx.core:core-ktx:1.13.1")
    // 显式声明协程：`Dispatchers.IO` 现在只靠 Compose 的传递依赖能编过，
    // 哪天上游把这条依赖挪走，编译会在一个很奇怪的地方断掉。
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // 注意：这一版还没有 JNI。会话列表来自写死的占位数据（见 ui/PlaceholderData.kt），
    // 接 Rust 核心走 crates/jni + cargo-ndk，是下一步的事。
}

// 打包前必须先把声明同步出来：preBuild 是所有 assemble 变体的公共前置。
tasks.named("preBuild") {
    dependsOn(syncNotices)
}
