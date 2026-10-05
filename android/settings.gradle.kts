pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "CyanCodex"
include(":app")
// 开源版本只包含用户端(:app); 管理端(:admin)、审核端(:reviewer)、压测工具(:stresstool) 不在本包内
//
// ⚠️ 官方 2.0.0 源码包里的 settings.gradle.kts 还留着
//    include(":offline") / include(":toolbox") / include(":launcher") 三行,
//    但这三个模块的目录**根本没打进开源包**(README 也写着"已同步改为只 include(\":app\")")。
//    照原样 sync 会直接报 "Project directory does not exist"。
//    这里注释掉 —— 二改工程只需要 :app。
//include(":offline")
//include(":toolbox")
//include(":launcher")
