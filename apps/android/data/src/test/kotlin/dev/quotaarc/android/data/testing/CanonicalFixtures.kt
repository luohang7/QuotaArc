package dev.quotaarc.android.data.testing

internal fun canonicalFixture(name: String): ByteArray =
    checkNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(name)) {
        "Missing canonical fixture $name"
    }.use { it.readBytes() }
