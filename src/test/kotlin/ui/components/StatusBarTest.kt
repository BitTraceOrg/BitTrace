package org.bittrace.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Density
import org.bittrace.ui.BitTraceTheme
import org.jetbrains.skia.EncodedImageFormat
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test

/**
 * The bar follows the rail: the inspector's readings are about the captured
 * flows, the Forge's is about the request in front of you, and neither says
 * anything true on the other's panel. Logs and the nerd stats are app-wide and
 * stay put.
 *
 * Rendered rather than asserted over a tree, because what is being checked is
 * that cells appear and disappear — and the layout is the thing that would
 * break if `StatusContext` grew a case nobody wired up. Set
 * `-Dbittrace.ui.dump=<dir>` to keep the frames and look at them.
 */
class StatusBarTest {

    private val stats = { NerdStats(flows = 12, trafficBytes = 2_048, bodyBytes = 4_096) }

    // Literal rather than `P.ok` / `P.dim`: a cell is built here, outside any
    // composition, and the palette is only meaningful inside one. What is being
    // rendered is that the glyph takes the shade it is handed, not which shade
    // the theme would have chosen.
    private val PUBLISHED = Color(0xFF4CAF50)
    private val LOCAL_ONLY = Color(0xFF9E9E9E)

    private fun render(name: String, content: @Composable () -> Unit) {
        ImageComposeScene(width = 900, height = 26, density = Density(1f)) {
            BitTraceTheme(dark = true) {
                Box(Modifier.fillMaxSize()) { content() }
            }
        }.let { scene ->
            try {
                scene.render()
                val image = scene.render()
                val dump = System.getProperty("bittrace.ui.dump")
                if (dump != null) {
                    val dir = Path.of(dump).also { Files.createDirectories(it) }
                    image.encodeToData(EncodedImageFormat.PNG)?.bytes
                        ?.let { Files.write(dir.resolve("$name.png"), it) }
                }
            } finally {
                scene.close()
            }
        }
    }

    private fun bar(name: String, context: StatusContext, busy: String? = null) =
        render(name) {
            StatusBar(
                context = context,
                busy = busy,
                nerdStats = stats,
                logsOpen = false,
                warn = 2,
                error = 1,
                onToggleLogs = {},
            )
        }

    private val inspector = StatusContext.Inspector(
        flows = 4_102,
        query = null,
        queryMatches = 4_102,
        ok = 3_980,
        failed = 122,
    )

    @Test
    fun `the inspector shows flows and outcomes`() {
        bar("status-inspector", inspector)
    }

    @Test
    fun `a running query is spelled out beside a narrowed count`() {
        bar("status-inspector-query", StatusContext.Inspector(
            flows = 4_102,
            query = "host contains example.com",
            queryMatches = 312,
            ok = 3_980,
            failed = 122,
        ))
    }

    @Test
    fun `an active outcome filter fills its cell`() {
        bar("status-inspector-filtered", StatusContext.Inspector(
            flows = 4_102,
            query = null,
            queryMatches = 4_102,
            ok = 3_980,
            failed = 122,
            failedFilterOn = true,
        ))
    }

    @Test
    fun `the forge shows the trail to the open request, then its branch`() {
        bar("status-forge", StatusContext.Forge(
            project = "Payments API",
            collection = "Auth",
            request = "Exchange device code",
            branch = BranchCell(
                label = "feature/oauth-device-code",
                tint = PUBLISHED,
                current = "feature/oauth-device-code",
                options = listOf("main", "feature/oauth-device-code"),
            ),
        ))
    }

    @Test
    fun `a project outside a repository shows the trail and no branch`() {
        bar("status-forge-norepo", StatusContext.Forge(
            project = "Scratch",
            collection = "Smoke tests",
            request = "Ping",
            branch = null,
        ))
    }

    @Test
    fun `an unsaved draft shows neither`() {
        bar("status-forge-draft", StatusContext.Forge(
            project = null,
            collection = null,
            request = null,
            branch = null,
        ))
    }

    @Test
    fun `a branch on a trunk name reads cleanly beside the slash`() {
        bar("status-forge-main", StatusContext.Forge(
            project = "New project",
            collection = "New collection",
            request = "New request",
            branch = BranchCell(
                label = "main",
                tint = PUBLISHED,
                current = "main",
                options = listOf("main", "develop"),
            ),
        ))
    }

    @Test
    fun `a local-only repo greys the branch and offers no picker`() {
        // No remote, so the glyph is the dull one; a detached HEAD lands here
        // too, by way of an empty option list.
        bar("status-forge-local", StatusContext.Forge(
            project = "Scratch",
            collection = "Smoke tests",
            request = "Ping",
            branch = BranchCell(label = "main", tint = LOCAL_ONLY, current = "main"),
        ))
    }

    @Test
    fun `home and settings keep only the app-wide controls`() {
        bar("status-none", StatusContext.None)
    }

    @Test
    fun `the busy slot renders on a panel with no readings of its own`() {
        bar("status-none-busy", StatusContext.None, busy = "Pushing…")
    }
}
