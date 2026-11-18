// Repositories for buildSrc's own plugin classpath resolution.
// Without this file, the plugins {} block (java-gradle-plugin, kotlin-dsl) and
// its transitive dependencies (kotlin-gradle-plugin 1.9.24, kotlin-stdlib, ...)
// resolve against the Gradle Plugin Portal only. The portal now 303-redirects
// uncached artifacts to Maven Central, which breaks resolution on some
// networks (seen on GitHub Actions runners since the ubuntu-26.04 image).
// Declaring mavenCentral()/google() here makes the plugin classpath resolve
// directly and independently of portal proxying.
pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}
