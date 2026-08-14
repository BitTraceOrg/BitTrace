package org.bittrace.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.bittrace.data.SettingsStore
import org.bittrace.plugin.ThemeManager
import org.bittrace.proxy.ProxyService
import kotlin.concurrent.thread

/** Settings screen — proxy port + appearance, persisted via [SettingsStore]. */
@Composable
fun SettingsView(settings: SettingsStore, service: ProxyService, themeManager: ThemeManager) {
    Column(
        Modifier.widthIn(max = 560.dp).fillMaxWidth().background(P.bg)
            .verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        PzText("Settings", color = P.text, size = 20, family = P.Ui, weight = FontWeight.Bold)

        Column(Modifier.fillMaxWidth().background(P.panel).border1(P.line)) {
            Row(Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 4.dp)) {
                PzText("PROXY", color = P.dim, size = 11, family = P.Ui)
            }
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                var portText by remember(settings.settings.proxyPort) {
                    mutableStateOf(settings.settings.proxyPort.toString())
                }
                val valid = portText.toIntOrNull()?.let { it in 1..65535 } == true

                Row(verticalAlignment = Alignment.CenterVertically) {
                    PzText("Listen port", color = P.dim, size = 13, family = P.Ui, modifier = Modifier.width(120.dp))
                    Box(
                        Modifier.width(100.dp).height(24.dp).background(P.bg).border1(P.line)
                            .padding(horizontal = 6.dp),
                        contentAlignment = Alignment.CenterStart,
                    ) {
                        BasicTextField(
                            value = portText,
                            onValueChange = { s -> if (s.all { it.isDigit() } && s.length <= 5) portText = s },
                            singleLine = true,
                            textStyle = TextStyle(color = P.text, fontSize = 13.sp, fontFamily = P.Mono),
                            cursorBrush = SolidColor(P.accent),
                        )
                    }
                    Spacer(Modifier.width(10.dp))
                    Box(
                        Modifier.background(if (valid) P.accent else P.line)
                            .pointerHoverIcon(if (valid) PointerIcon.Hand else PointerIcon.Default)
                            .clickable(enabled = valid) { applyPort(portText, settings, service) }
                            .padding(horizontal = 14.dp, vertical = 6.dp),
                    ) {
                        PzText("Apply & restart", color = if (valid) P.bg else P.faint, size = 12, family = P.Ui, weight = FontWeight.Medium)
                    }
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(if (service.isRunning) P.ok else P.err, 6)
                    Spacer(Modifier.width(7.dp))
                    val status = if (service.isRunning) {
                        "Proxy running on port ${settings.settings.proxyPort} · pid ${service.pid ?: "—"}"
                    } else {
                        "Proxy stopped"
                    }
                    PzText(status, color = P.faint, size = 12)
                }
            }
        }

        // ── appearance ───────────────────────────────────────────────────
        Column(Modifier.fillMaxWidth().background(P.panel).border1(P.line)) {
            Row(Modifier.fillMaxWidth().background(P.head).bottomBorder(P.line).padding(horizontal = 10.dp, vertical = 4.dp)) {
                PzText("APPEARANCE", color = P.dim, size = 11, family = P.Ui)
            }
            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                PzText("Theme", color = P.dim, size = 13, family = P.Ui, modifier = Modifier.width(120.dp))
                ThemeToggle(settings, themeManager)
            }
        }

        PzText(
            "Applying restarts the proxy on the new port. Point your client or system proxy at 127.0.0.1:<port>.",
            color = P.faint, size = 11, family = P.Ui,
        )
    }
}

@Composable
private fun ThemeToggle(settings: SettingsStore, themeManager: ThemeManager) {
    // Themes come from the plugin registry — bundled Precision plus any external
    // theme plugins appear here automatically.
    val active = themeManager.activeId(settings.settings.theme)
    Row(Modifier.border1(P.line)) {
        themeManager.availableThemes.forEachIndexed { i, spec ->
            val on = spec.id == active
            Box(
                Modifier.background(if (on) P.accent else Color.Transparent)
                    .then(if (i > 0) Modifier.leftBorder(P.line) else Modifier)
                    .pointerHoverIcon(PointerIcon.Hand)
                    .clickable { themeManager.setActiveThemeId(spec.id, settings) }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                PzText(spec.name, color = if (on) P.bg else P.dim, size = 12, family = P.Ui)
            }
        }
    }
}

/** Persists the new port, then restarts the sidecar off the UI thread. */
private fun applyPort(text: String, settings: SettingsStore, service: ProxyService) {
    val port = text.toIntOrNull() ?: return
    if (port !in 1..65535) return
    settings.update { it.copy(proxyPort = port) }
    thread(isDaemon = true, name = "proxy-restart") {
        runCatching {
            service.stop()
            Thread.sleep(300) // let the OS release the old port before rebinding
            service.start(port)
        }.onFailure { System.err.println("[proxy] restart failed: $it") }
    }
}
