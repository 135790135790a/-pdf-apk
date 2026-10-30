pluginManagement {
    // CI（GitHub Actions）走官方仓库：阿里云镜像在 runner 上经常超时，
    // 会卡住 KSP 这类插件的解析（v1.5.7 的 CI 就是这么红的）。
    // 本地仍用阿里云加速，拉不到时下面的官方仓库兜底。
    val ci = !System.getenv("CI").isNullOrEmpty()
    repositories {
        if (!ci) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    val ci = !System.getenv("CI").isNullOrEmpty()
    repositories {
        if (!ci) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "MangaShelf"
include(":app")
