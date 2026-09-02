# BitTrace architecture

An HTTP(S) capture and inspection tool: a Compose Desktop UI in Kotlin driving a
packaged mitmproxy sidecar, plus an API client that sends through that same
proxy so its requests are captured like any other traffic.

This document is about *shape and reasoning* — where things live, and why the
seams fall where they do. The code carries the detail.

## Modules

| Module | Contains | Depends on |
| --- | --- | --- |
| root (`src/`) | The application | `:plugin-api`, Compose Desktop, Jewel, kotlinx-serialization, jackson-core, kaml |
| `:plugin-api` | The public plugin contract: interfaces, colour ramps, plain value types | `compose.ui` only, for `Color` |

`:plugin-api` deliberately carries almost nothing. It has no serialization
dependency and no knowledge of the app's models, so a plugin never compiles
against internals that are free to change.

## The data path

```
MITMConnect (packaged mitmproxy)
   │  stdout: MAGIC | tag | json_len | body_len | json | body
   ▼
FrameReader ──▶ ProxyProcess ──▶ ProxyListener ──▶ ProxyService
                                                     │
                              metadata ──▶ SessionStore (SnapshotStateList<TrafficRow>)
                              bodies   ──▶ BodyCache   (id + side → ByteArray)
                                                     │
                                                     ▼
                                        FlowTable · Waterfall · Inspector
```

Four messages describe one flow — initial request, initial response, complete
request, complete response — and `SessionStore` merges them by flow id into one
`TrafficRow`. The three late parts are Compose snapshot state, so a row already
on screen fills in as frames arrive without any explicit refresh.

**Bodies never travel with metadata.** They live in `BodyCache`, keyed by flow
id and side, and reach the UI through a `(id, side) -> ByteArray?` lambda. That
keeps `TrafficRow` small, lets the cache evict independently of the row list,
and means anything that can produce bytes can feed the Inspector.

### Threading

The sidecar's reader threads never touch UI state directly. `SessionStore` hops
every mutation onto the AWT event thread — which is also Compose Desktop's UI
thread — through a private `onUi` helper. `EdtDispatcher` (in `api/`) is the
same hop expressed as a `CoroutineDispatcher`, so coroutines that read snapshot
state run where the writes happen.

Work that blocks goes the other way: body formatting runs on
`Dispatchers.Default`, HTTP sends and disk walks on `Dispatchers.IO`, and the
results land back on the event thread.

## Packages

| Package | Role |
| --- | --- |
| `data/` | Models and stores: the HAR-shaped wire types, `SessionStore`, `SettingsStore`, `LogStore`, string interning |
| `proxy/` | The sidecar: process, frame protocol, body cache, CA handling |
| `session/` | HAR import and export |
| `api/` | The API client's logic: request model, collections, history, sending, TLS, importers |
| `components/` | Every composable with a single caller: the screens, and the widgets only one of them uses |
| `ui/` | The shared widget vocabulary, the Jewel theme bridge, and the platform helpers |
| `plugin/` | Plugin discovery and the bundled plugins |

**The line between `ui/` and `components/` is reuse, not subject matter.** A
composable used from more than one place lives in `ui/`; one with a single
caller lives in `components/`, next to the screen that owns it. So `DataGrid` is
in `ui/` because the flow table and the log panel both build on it, while
`FlowTable` and `Waterfall` are in `components/` because nothing else will ever
want them. Screens are components by that rule too — `SettingsView` has exactly
one caller.

Each shared widget in `ui/` is its own file named after it — `Buttons.kt`,
`CheckBox.kt`, `Dropdown.kt`, `SegmentedToggle.kt`, `TextInput.kt`, `PillTabs.kt`,
`ChipRow.kt`, `Text.kt`, `Dot.kt` — so where a control lives is never a question.
Most are a dozen lines over a Jewel component; the exceptions are the three
things Jewel has no answer for, `DataGrid`, `CodeEditor` and the scrollbar and
splitter wrappers.

The practical test when adding a composable: if a second screen would want it,
put it in `ui/` and give it a file; otherwise leave it beside its caller.

## Key seams

### `DataGrid` — one grid, two callers

`DataGrid<T, M>` backs both the flow table and the proxy log. Everything the two
disagree on is a parameter: filters, selection, reordering, tail-following,
selectable text, row detail lines, and marker rows. `GridColumn<T>` carries a
`weight` that means either a share of the leftover width or a fixed dp size,
selected by `fixed` — which is exactly the difference between the flow table's
reflowing columns and the log's pinned TIME/LEVEL/SOURCE.

### `Inspector` — one component, three sources

`Inspector(row, bodyProvider, settings, formatters, stacked, showRequest)` is
the whole dependency surface. It renders captured flows, imported HAR entries
and API client responses identically, because all three arrive as a
`TrafficRow` plus a body lookup. Every null path degrades to "—" rather than
throwing, so a half-arrived flow renders fine.

### The design language

`DESIGN.MD` is the authority — it pins every colour, size and role by value, and
this section only says where those values live in the code. Colour is in the
ramps a theme plugin supplies (`:plugin-api`'s `Palette`, §2); type is `ui/Typo`
(§3); component sizes are Jewel's, which already match §4 and most of §12.

Int UI, as Jewel ships it. **Nothing in the app sets a corner size, a control
height or a padding on a Jewel component** — those are part of a component's
design, and overriding them is how you get defects like a tab whose top edge
stops responding. Two deliberate exceptions, each documented where it lives:
tab-strip scrollbars are given zero thickness (an upstream overlay swallows
clicks aimed at a tab), and the section tabs inside a pane run at 28dp rather
than 40dp, which is the same call Int UI makes when it runs editor tabs and
tool-window tabs at different densities.

Containers follow from that: a strip that holds a Jewel control **must not fix
its own height**. Sizing a header strip to 26dp while the field inside it wants
28dp is what pushes text off centre and makes two tab strips on one screen
disagree. Strips hug their contents and pad.

Colour is a set of ramps, not a list of colours — see `:plugin-api`'s `Palette`.
Type is a seven-step scale derived from the theme's base size — see `ui/Typo`.
Neither exists for elegance: without them, every new control invents its own
hover shade and its own point size, and the two drift.

Labels are sentence case. Acronyms, media types, HTTP verbs and header names
keep the wire's spelling, because those are quotations, not our words.

### Jewel, and the one place the theme crosses into it

Standard controls — buttons, fields, checkboxes, combo boxes, segmented
controls, scrollbars, tabs, menus — come from
[Jewel](https://github.com/JetBrains/intellij-community/tree/master/platform/jewel),
JetBrains' Compose Desktop implementation of the IntelliJ Int UI. Only its
stable API is used: `Dropdown`, `LazyTree` and `SpeedSearchArea` are
`@ExperimentalJewelApi` and are deliberately not, which is why the picker is a
`ListComboBox` and `CollectionTree` is still hand-built.

`ui/JewelBridge.kt` is the whole of the coupling. It renders the active
`Palette` as a Jewel `ThemeDefinition` plus a `ComponentStyling`, and
`BitTraceTheme` wraps the app in it.

**Why the colours are restated there by hand.** Int UI's component defaults are
baked against the static `IntUiDarkTheme`/`IntUiLightTheme` palettes, *not*
against `ThemeDefinition.colorPalette`. Supplying a custom palette therefore
does not reach the components at all; each style has to be handed our colours
explicitly. They are handed the same *roles* Int UI reads, so the file is a
mapping rather than a second set of design decisions — the index choices behind
those roles live on `Palette`, in one place.

`P` is still the single source of truth. It serves everything Jewel has no
component for, and the bridge is the one place those same colours are also given
to Jewel — so there remains exactly one theme to edit. Nothing outside
`JewelBridge.kt` reads a Jewel colour token.

A theme plugin knows nothing about Jewel: it contributes eight ramps and a
`isDark` flag, and the semantic roles are derived from those. `ThemeSpec.dark` is
no longer declared at all — it reads off the palette, so a spec cannot disagree
with the colours it ships, and it decides which Int UI base the styling derives
from.

### The window

`DecoratedWindow` and `TitleBar` from `jewel-int-ui-decorated-window` own the
frame: dragging, minimise/maximise/close, edge resize and Windows snap layouts.
The app used to draw all of that itself — an undecorated `Window`, eight
hand-placed resize handles working in AWT pixel space, and a maximiser that
computed screen insets so it would not cover the taskbar.

What stays ours is what sits *inside* the bar: the menus on the left and the
proxy endpoint in the middle. Interactive strips there are marked with
`Modifier.clientRegion(...)`, or the title bar treats their clicks as drags.

**This is why the app needs the JetBrains Runtime.** `DecoratedWindow` throws on
any other JVM — there is no fallback. See the build notes at the end.

The import dialog keeps its hand-built bar: `TitleBar` needs a
`DecoratedWindowScope`, and `DialogWindow` has none.

### Body formatters

`BodyFormatter` (in `:plugin-api`) turns bytes into text plus `Span`s tagged
with a semantic `TokenKind`. Formatters never choose colours — the host maps
kinds onto the active palette, so highlighting recolours with the theme. The
same lexers drive the request body editor through a `VisualTransformation`,
which is why a JSON body looks the same while you type it and after it is sent.

Formatters are called off the UI thread and must be stateless.

### Tools

`tools/` holds features that open in a window of their own rather than a view in
the rail: a tool is something you run *beside* what you were doing, and a view
would make you leave the thing you opened it to look at. `Tool` is the enum, and
the Tools menu is built from `Tool.entries`, so a tool that exists is a tool you
can open — there is no second list to keep in step. `ToolWindows` tracks which
are on screen, one window per tool, so asking twice brings you the one you have.

`ToolWindow` is the shared frame: a `DecoratedWindow` with a `TitleBar`, the same
pair the main window uses, so minimise, maximise, restore, edge resize and
Windows snap layouts come from the platform rather than from hand-drawn buttons
approximating them. It is a `Window` and not the `DialogWindow` behind
`AppDialog`, which is a separate decision: a dialog always sits in front of the
main window and takes its attention, whereas a tool should minimise on its own
and appear in the taskbar. Tool windows are composed as siblings of the main
window inside the same composition, so they inherit the theme without being
passed it — and, like it, they need the JetBrains Runtime.

The grid feeds the diff: `DataGrid` takes `markedKeys` and `onToggleMark`
alongside its single `selectedKey`, kept apart rather than merged into a set
because the selection drives what the other panes are showing and a view
following two selections would have to pick one anyway. Ctrl+click marks a row
(claimed on the Initial pointer pass so `clickable` does not also select it), and
the row menu offers the same thing for anyone who does not know the shortcut.
`Main` keeps the marks as a two-entry list — marking a third drops the oldest —
so "Diff the 2 marked flows" is always available without a clear step.

**Diff** (`tools/DiffTool.kt`) compares two flows, request or response. It does
not host an `Inspector`: that component renders one flow and owns its tab,
formatter and scroll state, so two side by side would show two bodies at two
scroll positions with nothing lining up. What it reuses is everything under the
Inspector — the same body formatters, the same request/response vocabulary, and
the same message layout the Raw tab assembles. `tools/Diff.kt` is the alignment,
kept pure and covered by `DiffTest`; it trims the common head and tail before
building an LCS table, and past `CELL_LIMIT` reports the middle as one block
rather than hanging the window on an exact answer.

## The code surface

Editing and viewing both go through `ui/CodeEditor.kt`, over
[KodeMirror](https://github.com/Monkopedia/kodemirror) — a native Kotlin port of
CodeMirror 6, no WebView and no JS bridge, built against the same Compose 1.11.1
Jewel pins. It replaced a hand-rolled gutter, find bar, completion popup and
line-splitting viewer with machinery that was already tested against
CodeMirror's own suite, and brought folding, bracket matching and undo the app
never had.

`CodeEditor` edits and `CodeView` reads; they are the same component with
`readOnly`/`editable` flipped, so a viewer cannot drift from the editor beside
it. A session owns its document once built, so the language, the theme and the
read-only flag are fixed at construction and a `key(...)` rebuilds the session
when one really changes — the alternative is reconfiguring a live editor, and
the cost is undo history on a rare event.

`ui/editor/EditorTheming.kt` derives the editor theme from the active `Palette`,
the same job `JewelBridge` does for Jewel and for the same reason: adopting one
of the seventeen bundled themes would have ended theme plugins' hold on the
editor. `languageFor` maps a content type to a language with the same loose
matching the body formatters use.

`ui/editor/GraphQlLanguage.kt` is a language written here, because upstream has
22 and GraphQL is not among them. It is a `StreamParser` — a hand-written
tokenizer, which suits a grammar whose lexical part is small and whose only
context-sensitivity is whether a name is a type. Two booleans carry that; a
parse tree per keystroke would not be worth what it bought. It ships its own
completion source: the language's words plus the identifiers the document has
already used, with no schema and no introspection.

**What the formatters lost.** `BodyFormatter.format` still runs — pretty-printing
a minified body is the part that mattered — but `BodyFormatter.highlight` is no
longer consulted on the Body, Raw and Hex tabs, because the language colours
them now. The method stays on the plugin interface: removing it would break
every external formatter for nothing. The Hex tab is deliberately **plain text**,
since a hex dump is columns of digits that no language describes.

## Plugins

`PluginLoader` discovers `Plugin` implementations via `ServiceLoader` from two
sources, merged by id with bundled winning: the app classpath, and every JAR in
`%APPDATA%\BitTrace\plugins` loaded through a `URLClassLoader` whose parent is
the app loader — so both sides share one copy of the API types. Every
discovery, instantiation and `init` is guarded, so one broken plugin logs and is
skipped rather than taking the app down.

Five categories exist today: `ThemePlugin`, `BodyFormatter`, `RequestImporter`,
`CollectionActionPlugin`, `FlowActionPlugin`. Adding a category means adding an
interface to `:plugin-api` and a `byType()` accessor to `PluginRegistry`.

`CollectionActionPlugin` is the collections tree's context menu. Its
`CollectionTarget` names which rung it was opened on — `PROJECT`, `COLLECTION`
or `REQUEST` — along with the project and collection the node sits in, so a
plugin decides what to offer from the kind rather than by counting path
segments. `actionsFor(target)` is called each time a menu opens and returns however many
items that node deserves, sorted by `order` with ties keeping load order, so
installing one plugin cannot reshuffle another's. Rename and Delete stay the
host's and sit above the separator — an item that could displace them could make
a collection uneditable. `Tree.kt` renders a plain `TreeMenuItem` (a label, an
enabled flag and a lambda) and never sees the plugin API; `ApiView` does the
translating, because that is where the store, the notice strip and a scope to
reload on already are. The bundled `BundledCollectionActions` (Copy path,
Duplicate) goes through the same seam an external JAR would, so the extension
point is exercised by the app itself rather than existing only on paper.

`FlowActionPlugin` is the same shape for a captured flow's row menu, and its
`FlowTarget` is a flat record rather than the app's `TrafficRow` — that row is a
live snapshot object wired into the capture pipeline, so handing it out would
make every internal change to capture a breaking change to plugins. Bodies reach
a plugin through a `(FlowBodySide) -> ByteArray?` function rather than a field,
because they are evicted under memory pressure and are read when an action runs,
not when a menu is built. Copy URL / cURL / HAR stay the host's: they are built
from the full captured message — cookies, timings, HTTP version, byte sizes —
none of which the plugin-facing target carries, and shrinking the HAR export to
fit one would be a real loss for a made-up symmetry. `BundledFlowActions` (Copy
as fetch(), Copy response body) exercises the seam.

## Filtering the grid

Column filters answer three different shapes of question and `GridColumn` has
one mechanism for each. **Typed text** is a substring of the cell. **Facets** are
a tick list, ORed within a column and ANDed across them — derived from the data
by default, but a column may declare a fixed `facets` list instead, which is
what Code, Method and Type do: a list built from what has arrived cannot offer
`5xx` until a 5xx has happened, which is exactly when you stop needing to ask
for it. **`numeric`** is a comparison, for Size, because "larger than 100 KB" is
neither a substring nor a set; the operand accepts units (`64kb`) since the
column displays them.

**Body search** is the question none of those can answer, because every column
filters metadata the grid already shows. Ctrl+F opens a strip between the
waterfall and the grid — `components/BodySearch.kt` — searching request or
response bodies as plain text or as a regex. The scan is debounced and runs on
`Dispatchers.Default`: it decodes every cached body in the table, which is cheap
for a hundred rows and not for ten thousand. A body that has been evicted simply
does not match, since a hit that cannot be inspected is worse than a miss. The
waterfall deliberately keeps showing everything — an overview narrowed by a
search is no longer an overview.

## The API client

Requests are sent **through BitTrace's own proxy** (`java.net.http.HttpClient`
with a `ProxySelector` at `127.0.0.1:<proxyPort>`), so they are captured as
ordinary flows and appear in the grid.

- **Correlation.** Each send stamps an `X-BitTrace-Rid` header, then polls the
  store newest-first from a watermark for the flow carrying it. The marker —
  not a URL and timestamp guess — is what makes two rapid identical sends
  unambiguous. If no flow matches within the deadline (proxy stopped, or the
  request never reached it), the exchange is synthesized into a row of its own
  so the Inspector still has something real to show.
- **TLS.** Traffic through the sidecar is re-signed by mitmproxy's CA, which the
  JVM does not trust. `ProxyTrust` composes the platform trust manager with one
  holding that CA and accepts if either does — additive, so ordinary
  certificates are still validated. The managers are `X509ExtendedTrustManager`
  on purpose: with the plain interface, `HttpClient` silently skips hostname
  verification.
- **Tabs.** `ApiClientState` holds a list of `RequestTab`s, each with its own
  draft, response and in-flight job, so switching tabs cancels nothing.
- **GraphQL is two documents.** `ApiBody` keeps the operation in `text` and the
  variables in `graphqlVariables`, because they are different languages wanting
  different highlighting and different halves of the editor; a single blob would
  have to be parsed apart and reassembled per keystroke. `payload()` builds the
  `{"query": …, "variables": …}` envelope at send time and `wireContentType()`
  reports `application/json`, the way `ApiAuth` assembles its header — so
  `contentType` stays the marker for which editor to show while the server is
  told what it actually reads. Variables go in as raw JSON, since re-encoding
  what was typed would turn an object into a string.
- **Request settings.** `RequestSettings` is a field of `ApiRequest` whose every
  field is nullable, and that *is* the inheritance: null means "use the app
  default", so changing a default in Settings moves every request that never had
  an opinion. `resolve(defaults)` is the single place the two are merged.
  `ApiClientState` reads the defaults through a `() -> Settings` lambda, the same
  way it reads the proxy port, so a change reaches the next send rather than the
  next restart. The pre-settings `ApiRequest.timeoutMs` is read but never
  written: `RequestYaml.decode` folds it in only when it differs from the old
  30 000 default, because the encoder wrote defaults and every old file carries
  one — reading it verbatim would opt every existing request out of a default it
  never chose.
- **Redirects are followed by hand**, in `ApiSender.execute`, not by the client.
  `HttpClient.Redirect` is a client property and the client is cached across
  sends, and the JDK offers no hop cap at all — so a per-request policy and a
  maximum both require doing it here. Each hop goes through the proxy and is
  captured as its own flow, so the grid shows the chain. The correlation marker
  rides only on the first hop, or several captured flows would claim to be the
  same send; `Authorization` and `Cookie` are dropped when a hop crosses origin.
  Method rules follow browsers rather than the RFC: 303 always becomes GET, and
  301/302 do too for anything that was not already GET or HEAD.
- **OAuth.** The two schemes are opposites and `api/oauth/` keeps them apart.
  **OAuth 1.0** signs each request: `OAuth1.kt` is pure and synchronous, and
  its percent-encoder is deliberately *not* `URLEncoder` — the two disagree on
  space, `*` and `~`, which is how most OAuth 1.0 attempts fail. **OAuth 2.0**
  fetches a token: `OAuth2.kt` builds the requests and reads the answers (both
  pure), `OAuthService.kt` owns the sockets, the browser and the polling, and
  `LoopbackServer.kt` is a one-shot `ServerSocket` bound to `127.0.0.1` — a raw
  socket rather than `com.sun.net.httpserver`, which keeps `jdk.httpserver` out
  of the module set packaging computes.

  **Tokens are memory-only.** `OAuthTokens` is keyed by a fingerprint of the
  config that earned the token — client, endpoint, grant, scope — not by request
  identity, so two requests against one API share an authorisation and editing a
  URL does not discard a valid token. Nothing obtained is written to a
  collection, because a collection is a folder you can commit or export.

  Signing and token-attaching both need what `ApiAuth` lacks — the request being
  sent, and the session's tokens — so `ApiSender.authHeaderFor` handles those two
  and everything else stays on `ApiAuth.header()`. **Sending never opens a
  browser**: an interactive grant is asked for, and a Send that launched one
  behind you would be indistinguishable from a hijack. Token exchanges go
  through the app's own proxy, so a failed one is readable in the grid.
- **Auth.** `ApiAuth` is a field of `ApiRequest`, never a row in its header
  table, and is applied by `ApiSender` at send time — a header for Basic,
  Bearer and a header-borne API key, an appended query parameter for a
  query-borne one. Materialising it into the table instead would make the
  credential something you edit as base64 and would leave a stale value behind
  the moment a field changed. A hand-typed header of the same name wins, on the
  same reasoning as the `Cookie` header. One flat record holds every scheme's
  fields at once, so switching scheme loses nothing, and `type` is a `String`
  rather than an enum so a request naming a scheme a later build added still
  loads.

### Import and export

A project or a collection exports as a zip and imports from one, from the
tree's own menu. `api/CollectionArchive.kt` holds the zip mechanics and knows
nothing about projects: `zipDirectory` writes the level's **contents** rather
than the level's folder, which is what lets `unzipInto` mean "unpack into the
level you clicked"; `unzipInto` asks a caller-supplied lambda what each
top-level item should be called, or null to skip it.

Two properties are load-bearing there. Entries whose names walk upwards are
partitioned out before anything looks at them and reported as skipped — refused
as themselves rather than sanitised into plausible names, since tidying
`../escape.yaml` into `escape.yaml` would be safe and would also hide that the
archive tried. And empty directories get entries of their own, so a collection
with no requests survives the round trip instead of looking like one that was
lost.

`CollectionStore.importInto` supplies the policy: a project accepts folders, a
collection accepts `.yaml` files, and anything else is counted in the notice
rather than written. A clashing name is numbered through the same `freeName`
that `createNamedCollection` uses — nothing on disk is ever replaced, on the
same reasoning that makes `delete()` move to `.trash`.

### Storage

Everything sits beside `settings.json` under `%APPDATA%\BitTrace`
(`~/.config/BitTrace` elsewhere):

```
BitTrace/
  settings.json      SettingsStore — debounced, whole-file
  history.yaml       HistoryStore  — distinct sent requests
  collections/       one folder per project, one per collection inside it,
                     one YAML per request
  plugins/           external plugin JARs
```

Saved requests are three fixed levels deep — **project > collection > request**
— and the tree mirrors that layout one-to-one, so either rung can be copied,
shared or version-controlled as a folder. The depth is what carries the
meaning, so it is fixed rather than arbitrary: anything at the wrong level (a
stray YAML beside a project, a folder inside a collection) is not shown, and is
left alone on disk rather than moved or deleted. A pre-project layout — the
collections that used to sit at the root — is adopted once on load by moving
those folders wholesale under a single project, so nothing silently disappears
the first time the new walk runs. Saving a request is **explicit**,
unlike settings: settings are driven by continuous input where intermediate
values are meaningless, whereas a request is authored content, and autosaving
would commit a stray keystroke before the user could think better of it.

## Session import and export

HAR 1.2, streamed in both directions — a real capture runs to hundreds of MB
once bodies are inlined as base64, so neither side ever holds the whole
document.

- **Import**: jackson walks to `log.entries` and lifts one entry's raw JSON at a
  time, which kotlinx decodes into all-defaulted mirror types (the wire model's
  non-null fields would reject any HAR written by another tool).
- **Export**: fields stream straight to a `JsonGenerator`, with bodies written
  through `writeBinaryField` so base64 is never materialised. The file is
  written beside its destination and moved into place, so a failure cannot
  truncate a good `.har`.

Imported flows are tagged with a session id, and the grid derives its
`SESSION … START/END` banners from runs of that tag — not from stored positions,
which is what keeps them correct under filtering and eviction.

## Conventions

- **No Material dependency, and no hand-rolled control that Jewel already has.**
  Buttons are `DefaultButton`/`OutlinedButton`/`ActionButton`/`IconActionButton`,
  toggles are `ToggleableChip` and `SegmentedControl`, list items are
  `SimpleListItem` — never a `Box` with a `.clickable` and a background. The
  build enforces the first half by excluding `org.jetbrains.compose.material`
  outright; the second half is a review question: if a click target draws its own
  hover and pressed states, it is a button that should have been a Jewel one.
  Anything Jewel genuinely does not cover — the grid, the editor, the
  visualisations — stays hand-built on `foundation` primitives and reads `P`.
- **Themes are data.** `P` is snapshot-backed, so `P.apply(palette)` recolours
  the app with no call-site changes. Nothing hardcodes a colour — and nothing
  reads one outside composition either, or it would freeze at class-init and
  stop following the theme.
- **Settings gain fields, never lose them.** Every field is defaulted so an
  older `settings.json` still loads.
- **Failures are visible, not fatal.** Plugin and formatter calls are wrapped;
  problems surface in the log panel or inline, and the app keeps running.
- **Read settings once where it is a structural choice** (table row mode, enabled
  columns), and reactively where it is a live toggle (inspector layout). The
  first avoids a settings lookup per row as traffic streams in.

## Tests

`src/test/` covers the two places where correctness is genuinely hard to eyeball:
YAML round-tripping of request bodies (`RequestYamlTest`, which also pins that
kaml works against the resolved kotlinx-serialization version) and cURL shell
tokenizing (`CurlImporterTest`). Run with `./gradlew test`.

## Build

Kotlin 2.3, Compose Multiplatform 1.11, Jewel 0.39.1, JVM toolchain 25.
`./gradlew compileKotlin` to check, `./gradlew run` to launch, `./gradlew
createDistributable` for a runnable app image. The sidecar binary ships as a
classpath resource and is unpacked to a temp folder on first run; point
`-Dbittrace.sidecar.dir=<folder>` at a local build to override it.

**The JetBrains Runtime is not optional.** Both `run` and the packaged app need
one, because `DecoratedWindow` refuses to start on anything else. The build
finds it from `-PjbrHome=`, then `$JBR_HOME`, then the JBR bundled with a local
JetBrains IDE. Running is happy with any of those; **packaging is not** — IDE
JBRs are built `-nomod` and carry no `jmods`, so jlink cannot build a runtime
image from them. `createDistributable` and `package*` therefore depend on a
`checkJbr` task that fails with an explanation rather than producing an app that
dies on its first frame. For packaging, point `jbrHome` at a `jbrsdk` build from
<https://github.com/JetBrains/JetBrainsRuntime/releases>.

Two version alignments are pinned on purpose. The IntelliJ platform icons
(`AllIconsKeys`) are published only to the JetBrains repository and on different
build numbers from the ones Jewel resolves, so a `resolutionStrategy` forces
`icons`, `icons-api` and `icons-impl` together. Those icons also drag in
IntelliJ's coroutines fork, which would otherwise sit on the classpath beside
upstream's; a module replacement collapses the two.
