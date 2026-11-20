pluginManagement {
    repositories {
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        mavenCentral()
        google()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/public")
        maven("https://maven.aliyun.com/repository/google")
        // 阿里云对个别构件只有元数据没有二进制,这些组强制走官方源
        exclusiveContent {
            forRepository { google() }
            filter {
                includeGroup("androidx.input")
            }
        }
        mavenCentral()
    }
}
rootProject.name = "TransportClip"
include(":app")
