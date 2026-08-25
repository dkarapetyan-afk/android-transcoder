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
        // Prebuilt Android AAR from GitHub Releases (not JitPack — that compiles native from source).
        exclusiveContent {
            forRepository {
                ivy {
                    url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download")
                    patternLayout {
                        artifact("v[revision]/[artifact]-[revision].[ext]")
                    }
                    metadataSources {
                        artifact()
                    }
                }
            }
            filter {
                includeGroup("com.k2fsa.sherpa.onnx")
            }
        }
    }
}

rootProject.name = "RecordingCompressor"
include(":app")
