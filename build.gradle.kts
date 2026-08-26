// AGP 9+ ships built-in Kotlin support, so there is deliberately no
// 'org.jetbrains.kotlin.android' line here anymore.
plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.3.21" apply false

    // KSP runs Room's annotation processor. Modern KSP versions are decoupled
    // from the Kotlin compiler version (that's why there's no "-2.x.x" suffix).
    id("com.google.devtools.ksp") version "2.3.11" apply false
}
