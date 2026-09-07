package org.bittrace.plugin

import java.net.URLClassLoader
import java.util.ServiceLoader
import org.bittrace.data.SettingsStore
import org.bittrace.ui.copyToClipboard as copyTextToClipboard

/**
 * Host services handed to each plugin's `init`.
 *
 * Logging goes wherever [onLog] points — the app's log store in a running app,
 * stderr when nobody is listening — so a plugin failure is visible in the UI
 * rather than only in a console the user does not have open.
 */
private class AppPluginHost(private val onLog: (String, String) -> Unit) : PluginHost {
    override fun log(level: String, message: String) = onLog(level, message)

    // The app's own clipboard helper, aliased on import so this override does
    // not resolve to itself. One real implementation behind both the internal
    // calls and the plugin-facing one.
    override fun copyToClipboard(text: String): Boolean = copyTextToClipboard(text)
}

/**
 * Discovers plugins via [ServiceLoader] from two sources, merged and de-duped by
 * id (bundled wins on clash):
 *
 *  - **Bundled**: providers on the app classpath (declared in
 *    `META-INF/services/org.bittrace.plugin.Plugin`).
 *  - **External**: every `*.jar` in `%APPDATA%\BitTrace\plugins`, loaded through a
 *    [URLClassLoader] whose parent is the app classloader — so both sides share
 *    the one `:plugin-api` copy of the interfaces.
 *
 * Every discovery/instantiation/init is guarded so a single broken plugin logs
 * and is skipped rather than taking down the app.
 */
object PluginLoader {

    /** Directory scanned for external plugin JARs. */
    val pluginsDir get() = SettingsStore.defaultPath().parent?.resolve("plugins")

    /**
     * Loads every plugin, reporting progress and failures through [onLog].
     *
     * [onLog] takes a level and a message, like the proxy and session importers
     * do; the caller decides where they land. The default writes to stderr, for
     * a load that happens before any UI exists.
     */
    fun load(onLog: (String, String) -> Unit = ::printLog): PluginRegistry {
        val host = AppPluginHost(onLog)
        val byId = LinkedHashMap<String, Plugin>()

        val appLoader = Plugin::class.java.classLoader
        collect(ServiceLoader.load(Plugin::class.java, appLoader), host, byId)

        externalLoader(appLoader, host)?.let { collect(ServiceLoader.load(Plugin::class.java, it), host, byId) }

        host.log("loaded ${byId.size} plugin(s): ${byId.keys.joinToString()}")
        return PluginRegistry(byId.values.toList())
    }

    private fun printLog(level: String, message: String) = System.err.println("[plugin/$level] $message")

    /** A classloader over the external plugin JARs, or null if none/absent. */
    private fun externalLoader(parent: ClassLoader, host: PluginHost): URLClassLoader? {
        val dir = pluginsDir?.toFile() ?: return null
        val jars = dir.takeIf { it.isDirectory }?.listFiles { f -> f.isFile && f.extension == "jar" }
        if (jars.isNullOrEmpty()) return null
        return try {
            URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), parent)
        } catch (e: Throwable) {
            host.log("error", "failed to open plugins dir: $e"); null
        }
    }

    /** Iterates a ServiceLoader defensively, initing and keeping first-seen ids. */
    private fun collect(loader: ServiceLoader<Plugin>, host: PluginHost, into: MutableMap<String, Plugin>) {
        val it = loader.iterator()
        while (true) {
            val hasNext = try {
                it.hasNext()
            } catch (e: Throwable) {
                host.log("error", "plugin discovery error: $e"); break
            }
            if (!hasNext) break

            val plugin = try {
                it.next()
            } catch (e: Throwable) {
                host.log("error", "plugin instantiation error: $e"); continue
            }

            val existing = into[plugin.id]
            if (existing != null) {
                // The external pass runs a ServiceLoader over a classloader whose
                // parent is the app's, so it re-discovers every bundled provider —
                // the *same class* a second time, which is structural and not
                // worth a word. A different class claiming a taken id is a real
                // clash, though: usually an old JAR left in the plugins folder,
                // and staying quiet about it makes the new one look simply broken.
                if (existing.javaClass != plugin.javaClass) {
                    host.log(
                        "warn",
                        "ignored '${plugin.javaClass.name}': id '${plugin.id}' " +
                            "is already taken by '${existing.javaClass.name}'",
                    )
                }
                continue
            }
            try {
                plugin.init(host)
            } catch (e: Throwable) {
                host.log("error", "init '${plugin.id}' failed: $e"); continue
            }
            into[plugin.id] = plugin
        }
    }
}
