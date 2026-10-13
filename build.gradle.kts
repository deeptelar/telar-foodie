plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)

    // The planner is common code. The JVM target is there for its tests, which are fastest there,
    // and for a run from the command line; the page is the JS target.
    jvm()

    js {
        outputModuleName = "foodie"
        browser {
            commonWebpackConfig {
                outputFileName = "foodie.js"
            }
            testTask {
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.telar.core)
            implementation(libs.telar.agent)
            implementation(libs.telar.serialization)
            implementation(libs.telar.anthropic)
            implementation(libs.telar.openai)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.client.core)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.telar.test)
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.ktor.client.mock)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        jsMain.dependencies {
            implementation(libs.telar.checkpoint.browser)
            implementation(libs.ktor.client.js)
        }
    }
}
