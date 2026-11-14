// Copyright 2026 The Orbit authors
// SPDX-License-Identifier: Apache-2.0
package io.github.kmmarin.tvbro.desktop

import java.io.File
import java.io.IOException
import java.security.SecureRandom
import java.util.Base64

/** Output of a helper program: exit code, stdout, stderr. */
data class ProcOut(val code: Int, val out: String, val err: String)

/**
 * The secrets key in the macOS login Keychain, through Apple's `/usr/bin/security` tool (a generic password, service
 * [SERVICE], account [ACCOUNT]). Like the Linux keyring it holds only the 32-byte AES key of [Secrets]; the secrets
 * themselves stay in the config folder, encrypted with it.
 *
 * Why the `security` tool and not Security.framework through JNA: a Keychain item trusts the program that created it.
 * Made by `security`, it is read back by the same, Apple-signed, never-changing binary, so the user is not asked again
 * after every app update; an item made from the app's own unsigned Java launcher would ask (or fail) whenever that
 * binary changes. The trade-off is the same as with `secret-tool` on Linux: other programs of the same user can run
 * `security find-generic-password` too. No secret ever goes on a command line: the key is written with `security -i`
 * (commands read from stdin, so `ps` shows only "security -i") and read from the stdout of `find-generic-password -w`.
 *
 * [run] is the process runner (replaced in tests: the logic is checked on any host, the real tool only on a Mac).
 */
class MacKeychain(
    private val run: (cmd: List<String>, stdin: ByteArray?) -> ProcOut = ::runProc,
    private val rnd: SecureRandom = SecureRandom(),
) {
    companion object {
        const val TOOL = "/usr/bin/security"
        const val SERVICE = "tvBro"
        const val ACCOUNT = "secrets-key"
        /** `security` exits with errSecItemNotFound (-25300) as 44 when no such item exists. */
        const val NOT_FOUND_EXIT = 44

        val available: Boolean get() = Platform.mac && File(TOOL).canExecute()

        /** Only "not found" may lead to a new key; a locked Keychain, a denied prompt or any other failure must not. */
        fun classify(r: ProcOut): KeyringLookup = when {
            r.code == 0 && r.out.isNotBlank() -> if (Keyring.decode(r.out) != null) KeyringLookup.FOUND else KeyringLookup.ERROR
            r.code == NOT_FOUND_EXIT -> KeyringLookup.NOT_FOUND
            else -> KeyringLookup.ERROR
        }

        /**
         * The stdin script for `security -i`: one command line. No word needs quoting (Base64 and the names have no
         * spaces or quotes), so the line does not depend on how `security` splits quoted words. `-U` updates an
         * existing item instead of failing.
         */
        fun addScript(keyB64: String): ByteArray {
            require(keyB64.isNotEmpty() && keyB64.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it == '+' || it == '/' || it == '=' }) { "not Base64" }
            return "add-generic-password -U -s $SERVICE -a $ACCOUNT -l $SERVICE-secrets-key -w $keyB64\n".toByteArray()
        }

        fun runProc(cmd: List<String>, stdin: ByteArray?): ProcOut = try {
            val p = ProcessBuilder(cmd).start()
            val err = StringBuilder()
            val t = Thread { err.append(p.errorStream.bufferedReader().readText()) }.apply { isDaemon = true; start() }
            p.outputStream.use { o -> stdin?.let { o.write(it) } }
            val out = p.inputStream.bufferedReader().readText()
            val code = p.waitFor(); t.join(2000)
            ProcOut(code, out, err.toString())
        } catch (e: Exception) { ProcOut(-1, "", e.javaClass.simpleName) }
    }

    fun lookup(): ProcOut = run(listOf(TOOL, "find-generic-password", "-s", SERVICE, "-a", ACCOUNT, "-w"), null)

    /** Stores [key]; true when a read-back returns the same key. */
    fun store(key: ByteArray): Boolean {
        val b64 = Base64.getEncoder().encodeToString(key)
        val r = run(listOf(TOOL, "-i"), addScript(b64))
        if (r.code != 0) return false
        val back = lookup()
        return classify(back) == KeyringLookup.FOUND && Keyring.decode(back.out)!!.contentEquals(key)
    }

    /**
     * The key: the stored one; else, when none is stored, [keyFile]'s if one exists (secrets written without the
     * Keychain), else a new key stored in the Keychain. Null = could not store one (the caller uses the key file).
     * Throws when the Keychain cannot be asked (locked, prompt denied): never a new key then, or old secrets are lost.
     */
    fun loadOrCreate(keyFile: File): ByteArray? {
        val r = lookup()
        return when (classify(r)) {
            KeyringLookup.FOUND -> Keyring.decode(r.out)!!
            KeyringLookup.ERROR -> throw IOException("Keychain not available (security exit ${r.code})")
            KeyringLookup.NOT_FOUND -> {
                if (keyFile.exists()) return KeyFile.loadOrCreate(keyFile)
                val k = ByteArray(32).also { rnd.nextBytes(it) }
                if (store(k)) k else null
            }
        }
    }
}
