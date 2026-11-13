package com.trentcowden.audiobookshelf

import com.trentcowden.audiobookshelf.storage.AuthState
import com.trentcowden.audiobookshelf.storage.AuthStore
import com.trentcowden.audiobookshelf.storage.TokenCipher
import java.io.File
import java.nio.file.Files

/** Stand-in for the Android Keystore cipher: reversible, and visibly not plain text. */
object FakeCipher : TokenCipher {
    override fun seal(plain: String) = "sealed:" + plain.reversed()
    override fun open(sealed: String) = sealed.removePrefix("sealed:").reversed().takeIf { sealed.startsWith("sealed:") }
}

fun tempDir(): File = Files.createTempDirectory("abs-test").toFile().also { it.deleteOnExit() }

/** Fresh AuthStore in a temp dir, optionally signed in. */
fun freshAuthStore(signedIn: AuthState? = null): File {
    val dir = tempDir()
    AuthStore.resetForTest()
    AuthStore.init(dir, FakeCipher)
    signedIn?.let { AuthStore.save(it) }
    return dir
}
