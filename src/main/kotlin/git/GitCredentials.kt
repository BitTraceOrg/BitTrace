package org.bittrace.git

import java.io.File
import java.net.URI
import org.eclipse.jgit.api.TransportConfigCallback
import org.eclipse.jgit.transport.CredentialsProvider
import org.eclipse.jgit.transport.SshTransport
import org.eclipse.jgit.transport.URIish
import org.eclipse.jgit.transport.UsernamePasswordCredentialsProvider
import org.eclipse.jgit.transport.sshd.JGitKeyCache
import org.eclipse.jgit.transport.sshd.KeyPasswordProvider
import org.eclipse.jgit.transport.sshd.SshdSessionFactory
import org.eclipse.jgit.transport.sshd.SshdSessionFactoryBuilder

/**
 * How BitTrace authenticates to a remote.
 *
 * Two paths, deliberately different in kind:
 *
 * **SSH** uses the keys and agent already on the machine. The app never sees a
 * key, never asks for a passphrase, and [decliningPasswords] makes that a fast
 * refusal rather than a hang — JGit's default provider tries to prompt on a
 * console, and a windowed app has none, so a passphrase-protected key would sit
 * there forever with no indication why.
 *
 * **HTTPS** uses a token the user has put in Settings. Keyed by host rather than
 * by remote or project, because one token covers every repository on a host and
 * keying it any finer would mean typing it once per project.
 *
 * The token is read through a lambda rather than held here, so changing it in
 * Settings reaches the next fetch without anything having to be rebuilt.
 */
class GitCredentials(private val token: () -> String) : AutoCloseable {

    /**
     * Built once and shared. It owns a key cache and a thread pool, which is
     * why this class is [AutoCloseable] and why the factory is not made per
     * call — a factory per operation would leak a pool per operation.
     */
    private val sshFactory: SshdSessionFactory by lazy {
        SshdSessionFactoryBuilder()
            .setPreferredAuthentications("publickey")
            .setHomeDirectory(File(System.getProperty("user.home")))
            .setSshDirectory(File(System.getProperty("user.home"), ".ssh"))
            .setKeyPasswordProvider { decliningPasswords() }
            .build(JGitKeyCache())
    }

    /**
     * Attached per transport rather than through `SshSessionFactory.setInstance`.
     *
     * That setter is a JVM-wide global. This app loads third-party plugins into
     * the same JVM, and a global one of them could also reach is not somewhere
     * to put the thing that decides which key signs a push.
     */
    fun callbackFor(remoteUrl: String): TransportConfigCallback = TransportConfigCallback { transport ->
        if (transport is SshTransport) {
            transport.sshSessionFactory = sshFactory
        } else {
            transport.credentialsProvider = httpsProviderFor(remoteUrl)
        }
    }

    /** The user/password pair for [remoteUrl]. See [loginFor] for the username. */
    private fun httpsProviderFor(remoteUrl: String): CredentialsProvider {
        val secret = token()
        if (secret.isBlank()) return CredentialsProvider.getDefault() ?: NoCredentials
        return UsernamePasswordCredentialsProvider(loginFor(hostOf(remoteUrl)), secret)
    }

    /** Whether a token is configured at all, for messages that hinge on it. */
    fun hasToken(): Boolean = token().isNotBlank()

    /** Whether [remoteUrl] can be reached with what is configured right now. */
    fun canReach(remoteUrl: String): Boolean = canAuthenticate(remoteUrl, token())

    override fun close() {
        runCatching { sshFactory.close() }
    }

    /**
     * A provider that refuses every passphrase prompt.
     *
     * Returning null from [getPassphrase] tells sshd there is nothing to try,
     * so the attempt fails immediately with an auth error the UI can explain,
     * instead of blocking on a console read that will never return.
     */
    private fun decliningPasswords(): KeyPasswordProvider = object : KeyPasswordProvider {
        override fun getPassphrase(uri: URIish?, attempt: Int): CharArray? = null
        override fun setAttempts(maxNumberOfAttempts: Int) = Unit
        override fun getAttempts(): Int = 1
        override fun keyLoaded(uri: URIish?, attempt: Int, error: Exception?): Boolean = false
    }

    /** Never prompts, never succeeds — the honest answer when no token is set. */
    private object NoCredentials : CredentialsProvider() {
        override fun isInteractive(): Boolean = false
        override fun supports(vararg items: org.eclipse.jgit.transport.CredentialItem?): Boolean = false
        override fun get(uri: URIish?, vararg items: org.eclipse.jgit.transport.CredentialItem?): Boolean = false
    }

    companion object {
        /**
         * Whether a write to [url] could authenticate with what is configured.
         *
         * Checked before the push rather than after, because the answer from
         * the far end is not a useful one: GitHub replies to an unauthenticated
         * write with `git-receive-pack not permitted`, which describes a wire
         * protocol rather than the thing to go and fix.
         *
         * Only HTTP(S) needs a token. SSH has its own keys, and `file:` and
         * plain paths — which is what a repository on a share or a local mirror
         * looks like — need nothing at all.
         */
        fun canAuthenticate(url: String, token: String): Boolean =
            !url.startsWith("http", ignoreCase = true) || token.isNotBlank()

        /**
         * The username to send beside a token.
         *
         * Always the *password* field carries the token, which is what every
         * forge documents. The token-as-username form also works on GitHub for
         * classic tokens and is widely repeated, but it is not what GitHub says
         * to do and fine-grained tokens do not reliably accept it — which
         * surfaces as a 403 on push and looks exactly like a missing permission.
         */
        fun loginFor(host: String): String = when (host) {
            "gitlab.com" -> "oauth2"
            else -> "x-access-token"
        }

        /** Best-effort host for [url], covering both `https://` and `git@host:path`. */
        fun hostOf(url: String): String = runCatching {
            if (url.startsWith("git@")) {
                url.substringAfter('@').substringBefore(':')
            } else {
                URI(url).host.orEmpty()
            }
        }.getOrDefault("").lowercase()
    }
}
