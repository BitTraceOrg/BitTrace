package org.bittrace.plugin

import org.bittrace.plugin.Plugin
import org.bittrace.plugin.PluginHost
import org.bittrace.data.SettingsStore
import java.net.URLClassLoader
import java.util.ServiceLoader

/** Host services handed to each plugin's `init`. */
private class AppPluginHost : PluginHost {
    override fun log(message: String) = System.err.println("[plugin] $message")
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

    fun load(): PluginRegistry {
        val host = AppPluginHost()
        val byId = LinkedHashMap<String, Plugin>()

        val appLoader = Plugin::class.java.classLoader
        collect(ServiceLoader.load(Plugin::class.java, appLoader), host, byId)

        externalLoader(appLoader, host)?.let { collect(ServiceLoader.load(Plugin::class.java, it), host, byId) }

        host.log("loaded ${byId.size} plugin(s): ${byId.keys.joinToString()}")
        return PluginRegistry(byId.values.toList())
    }

    /** A classloader over the external plugin JARs, or null if none/absent. */
    private fun externalLoader(parent: ClassLoader, host: PluginHost): URLClassLoader? {
        val dir = pluginsDir?.toFile() ?: return null
        val jars = dir.takeIf { it.isDirectory }?.listFiles { f -> f.isFile && f.extension == "jar" }
        if (jars.isNullOrEmpty()) return null
        return try {
            URLClassLoader(jars.map { it.toURI().toURL() }.toTypedArray(), parent)
        } catch (e: Throwable) {
            host.log("failed to open plugins dir: $e"); null
        }
    }

    /** Iterates a ServiceLoader defensively, initing and keeping first-seen ids. */
    private fun collect(loader: ServiceLoader<Plugin>, host: PluginHost, into: MutableMap<String, Plugin>) {
        val it = loader.iterator()
        while (true) {
            val hasNext = try {
                it.hasNext()
            } catch (e: Throwable) {
                host.log("plugin discovery error: $e"); break
            }
            if (!hasNext) break

            val plugin = try {
                it.next()
            } catch (e: Throwable) {
                host.log("plugin instantiation error: $e"); continue
            }

            if (into.containsKey(plugin.id)) continue
            try {
                plugin.init(host)
            } catch (e: Throwable) {
                host.log("init '${plugin.id}' failed: $e"); continue
            }
            into[plugin.id] = plugin
        }
    }
}
