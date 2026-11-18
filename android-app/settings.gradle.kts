pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // 仅用于 A/B 实验：zxing-cpp 的 master 快照（3.2.0-SNAPSHOT）发布在 Central 的 snapshot 仓库
        maven {
            url = uri("https://central.sonatype.com/repository/maven-snapshots/")
            mavenContent { snapshotsOnly() }
        }
    }
}

rootProject.name = "LabelReprint"
include(":app")
