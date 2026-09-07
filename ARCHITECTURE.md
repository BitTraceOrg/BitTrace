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

A `CONNECT` is a flow of its own, on its own pair of tags: mitmproxy answers it
without raising the request and response hooks, so a tunnel that is refused
would otherwise leave no trace at all. `ConnectRequestData` converts into the
same two request messages as anything else, so a tunnel is an ordinary row with
`CONNECT` in the method column. Its id is unrelated to the ids of the requests
that travel inside it; `clientConnectionId`, carried on both, is the link.

**Bodies never travel with metadata.** They live in `BodyCache`, keyed by flow
id and side, and reach the UI through a `(id, side) -> ByteArray?` lambda. That
keeps `TrafficRow` small, lets the cache evict independently of the row list,
and means anything that can produce bytes can feed the Inspector.

A body past the sidecar's streaming threshold does not ride on its `Complete*`
frame at all: it is forwarded chunk by chunk, and `StreamedBodies` reassembles
it before it reaches the cache. Two things separate that path from the inline
one. The bytes arrive **still `Content-Encoding`-encoded**, because mitmproxy's
stream callback sees the wire rather than the decoded message, so they are
inflated here. And capture is **best-effort**: the sidecar drops chunks rather
than stalling the proxy's event loop behind a slow reader, and assembly stops at
a fixed ceiling rather than letting one download size the heap. Either way the
prefix is kept and the loss is logged, since what reaches the Inspector then
looks like a whole body.

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
| `ui/` | Theme, type scale, platform helpers, presentation formatting |
| `ui/components/` | The shared widget vocabulary, plus the window chrome every screen sits inside |
| `ui/layouts/<screen>/` | One screen's entry composable, with `components/` beside it for the parts only that screen uses |
| `plugin/` | Plugin discovery and the bundled plugins |

**The line is reuse, and it is now drawn by the folder.** A composable used from
more than one screen lives in `ui/components/`; one that only its own screen
will ever want lives in that screen's own `components/`. So `DataGrid` is shared
because the flow table and the log panel both build on it, while `FlowTable` and
`Waterfall` sit under `layouts/inspector/components/` because nothing else will
ever want them.

The four screens match the four rail entries:

| Layout | Entry | Its own components |
| --- | --- | --- |
| `layouts/home/` | `Home` | — |
| `layouts/inspector/` | `TrafficView`, and the `Inspector` the Forge also reuses | `FlowTable`, `OverviewBand`, `Waterfall`, `FlowPhases`, `FlowQuery`, `FlowExport`, `BodyScan` |
| `layouts/forge/` | `ApiView` | `Tree`, `AuthTab`, `RequestSettingsTab`, `RequestHistoryTab`, `VariablesPane`, `ProjectToolbar`, `KvEditor`, `MethodPicker`, `GitDialogs`, `GitMenu`, `UnsavedChangesDialog` |
| `layouts/settings/` | `SettingsView` | — |

`Inspector` is the one component a layout owns that another layout imports: the
Forge shows a response through the very same pane the traffic screen does, which
is the point of it. Home and Settings have no `components/` folder because
everything they draw is private to their one file; the folder appears when there
is something to put in it.

Each shared widget in `ui/components/` is its own file named after it — `Buttons.kt`,
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

**One exception, opted into once:** `Palette.tooltipStyle` positions every
tooltip in the app relative to its own button rather than to the cursor, which
is the only way a hover tooltip does not end up touching whatever the button
sits on depending on where the pointer entered it. There is no stable
`TooltipPlacement` constructor — every one Jewel or Compose Foundation offers
is `@ExperimentalFoundationApi` or `@ExperimentalJewelApi`, unlike `Dropdown` or
`LazyTree`, which had a stable replacement to fall back to. The `@OptIn` is
scoped to that one private function, in the one file whose job is exactly this
kind of concession, so nothing outside `JewelBridge.kt` needs to know the type
exists.

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
a tick list, ORed within a column and ANDed across them, and *only* a column
whose values are a closed set gets one — Code, Method and Type do (Method's comes from `data/HttpMethods.kt`, the
app's one list — the API client's picker and the search band's facet column read
the same one, so the three panels cannot disagree about what a method is): a list built from what has arrived cannot offer
`5xx` until a 5xx has happened, which is exactly when you stop needing to ask
for it. **`numeric`** is a comparison, for Size, because "larger than 100 KB" is
neither a substring nor a set; the operand accepts units (`64kb`) since the
column displays them.

**The header funnels and the band edit one state.** Code, Method and Type offer
the same three sets of ticks in both places, and held separately they were two
filters that agreed only until somebody used either: ticking 4xx in a header
left the band showing nothing selected, and the grid then applied the two
independently, so the row count answered to a query neither panel had drawn.
`GridFacetBinding` is the seam — the band's `FlowQuery` keeps the state, the
header popup reads and writes it through the binding, and `applyGridFilters`
never sees those ticks because the owner has already applied them.
`FACET_COLUMNS` names the correspondence next to the groups themselves. It is
keyed by column key, so a renamed column would not fail to compile — it would
quietly stop editing the query — and a test walks the catalog to catch that.

Tick lists used to be derived from the captured rows when a column did not
declare one, which is wrong in both directions: on a closed set it could only
offer what had already arrived, and on an open one like host it grew without
bound, turning the popup into a scrolling directory you had to search before you
could filter with it. Open sets now get the text field, which is the right tool
for a value you describe rather than pick — and the host set people actually
wanted lives in the overview band's Host column, with cross-filtered counts.

## The overview band

`layouts/inspector/components/OverviewBand.kt` is the strip above the grid, and it has two modes
that morph into each other in place. Idle it is the waterfall: 66dp of lanes with
the brush strip beneath.

**The lanes are the brush seen close up.** `Waterfall` holds no scroll state of
its own — its window *is* the brushed range, a minute wide by default because
that is what the strip selects by default, and as wide as you drag it after
that. A fixed minute there would have quietly contradicted a wider selection,
showing its first minute while the strip drew the whole thing highlighted; and
because the lanes now fill the window edge to edge, they no longer draw the
window on themselves — a highlight covering the entire canvas says nothing.
Gridlines are quarters of whatever the window is, so they stay four readable
units instead of becoming an hour of hairlines at 15s apiece. `computeTimeline` takes
the capture's origin rather than re-basing on the rows it is handed, because
filtering hands it a subset and a re-based axis would mean something different
from one frame to the next.

**The strip is in both modes, in the same place.** It replaced the waterfall's
old scroll map, which drew the same picture and answered a strictly smaller
question: both were "the whole capture, with the part you are looking at marked
on it", but only one of them can also say which part that is. Scrolling the
lanes back an hour and filtering to that hour were separate acts that always
happened together, so they are now one — brushing seeds the lanes' pin rather
than driving their window every frame, which leaves the lanes still draggable
from wherever the brush put them. Pressing `/` grows it to 250dp and the
lanes fade out for a query bar, a six-column facet grid and a time-window brush
strip.

The reason it is one component rather than a timeline plus a search panel is the
brush strip. **Searching does not replace the timeline; it turns the timeline
into an input.** The same waterfall comes back compressed at the bottom as
something you drag a window on — no lanes this time, because that read is
density (*when was it busy*) rather than per-flow. A search box in place of the
band would have thrown away the one picture that says *when* to look.

The strip runs on one gesture handler rather than a drag detector beside a tap
detector, because where a press *lands* is what decides its meaning and two
competing detectors would have had to agree on which of them owned it. Inside an
existing window the press moves that window, measured from the window as it
stood when the drag began so a move does not accumulate per-frame rounding;
anywhere else it draws a new one, and a press too narrow to be a brush clears
it. A click inside the window is therefore a zero-length move that leaves it
exactly as it was — the window is not something you lose by touching it.

The band edits exactly one value, `layouts/inspector/components/FlowQuery.kt`, which is
also the only thing the grid consults. That matters because the query is
rendered in four places at once — the grid's rows, the lanes, the strip's own
bars and the status bar — and four independent filters eventually disagree,
whereas four views of one value cannot. Within a facet the values are ORed
(ticking `4xx` and `5xx` means either, since no flow is both); across facets they
are ANDed unless the joiner says otherwise. Free text and the time window are
always ANDed on top: an OR between "in this second" and "mentions this host" is
not a question anybody asks.

**Counts are cross-filtered.** Each column's numbers apply every *other* facet,
the text and the window, but ignore that column's own selection. Computed the
obvious way, a count reads zero for every value you have not picked — which is
precisely when it needed to tell you what picking it would do. A zero-count row
stays clickable for the same reason: it is how you clear your way back to it,
and a row that cannot be pressed reads as broken rather than empty.

Downstream, the query filters the grid, marks its own free-text needle inside
the URL cells (`FlowHighlight` — filtering says *which* rows matched, not
*where*), names itself in the status bar next to `n flows of total`, and picks
the grid's empty message: nothing captured and everything filtered out look
identical from inside the grid and need opposite advice.

**Free text searches the bodies**, on the side the toggle beside the field
picks, and the metadata as well — a text search that could no longer find
`/orders` in a URL would have stopped answering the question it used to. Every
other criterion is metadata the grid already holds, so filtering is a pure
predicate over rows in memory; the body scan is the one part that cannot be, and
it stays outside the model in `layouts/inspector/components/BodyScan.kt`, running off the UI
thread behind a 250ms debounce and handing `matches` a set of ids to test in
constant time. `null` from that scan means "not yet", not "no matches" — read as
a miss it would blank the grid on every keystroke and fill it back in a beat
later. An evicted body simply does not match, since a hit you cannot then open
in the inspector sends you looking for something the app no longer has.

## Git-backed projects

Every API-client project is a git repository, initialised on first sight of it.
`git/GitService.kt` wraps JGit — chosen over shelling out so the feature works on
a machine with no git installed — and every call suspends, hops to IO itself and
returns `Result`, with JGit's exceptions translated into a sealed `GitFailure`
so the UI branches on a type instead of matching a message.

**Project variables.** Each project holds a `.bittrace-variables.yaml` — a
key/value table shown as a **Variables** row under it — and `{{name}}` in a URL,
a param, a header, a cookie, a body or an auth field is replaced on the way out
(`api/Variables.kt`). Substitution happens at send time and is **never written
back**: the saved request keeps the `{{name}}` form, which is what makes it
worth committing, and the send history records it that way too so an entry stays
replayable.

Four rules, each with a test:

- **An unknown name resolves to nothing**, and there is no special case for "the
  project has no variables at all" — that is simply the case where every name is
  unknown. A short-circuit there would make a draft behave differently from a
  saved request for no reason a user could see. Because that hole is invisible
  on the wire, `TrackedVariables` records every name it could not supply and the
  send logs them as a warning; the substitution itself stays a pure function.
- **A draft resolves against the project it would be saved into.** It has no
  file of its own, so it used to belong to no project and every `{{name}}` in it
  came out empty — while the same request, saved one click later, worked. The
  Forge keeps `ApiClientState.draftHome` in step with its tree selection, which
  is the very path `save` reads to decide where the draft lands, so "where will
  this go" and "which variables apply" cannot give different answers.
- **Values are not rescanned.** A variable whose value contains `{{...}}` yields
  those characters, so a substitution's result never depends on another
  variable and a cycle is impossible rather than merely handled.
- **Substitution happens on two paths, not one.** `ApiSender.execute` covers the
  send; `ApiClientState.authorize`/`refreshToken` cover the authorise, because
  `OAuthTokens` fingerprints a token on the grant, client id, token URL, scope
  and audience. Resolve on one side only and the lookup misses every time —
  which surfaces not as an error but as a client that silently re-authorises on
  every send.

The variables file is **committed**, unlike the credentials sidecar it replaced.
That is a deliberate trade, made explicitly: one place to change a value, at the
cost of the guarantee that nothing sensitive reaches the remote. Anything typed
into a variable goes to the remote with the project, which the publish dialog
says in as many words. The leading dot in the filename is load-bearing —
`holdsRequestDirectly` reads a plain `.yaml` directly inside a project folder as
the pre-project layout, and `adoptLegacyLayout` would sweep every project into a
folder called "My project".

**A tab is a sum type.** `EditorTab` is either a `RequestTab` or a
`VariablesTab`, rather than one class with a mode flag. The difference is not
stylistic: with a flag, a variables tab carries a placeholder `ApiRequest` and a
path, so `CollectionStore.save(path, request)` is *writable* — and `saveAll`
reaches it with no compile error, putting a six-line YAML file where a project
folder was. Split, that call cannot be expressed. `CollectionStore.save` also
now refuses any path that is not at a request's depth, so the same mistake from
any other caller is a failed `Result` rather than a lost file.

Several things in `GitService` are there to stop a failure that does not throw:Several things in `GitService` are there to stop a failure that does not throw:

- **`.gitattributes` plus a pinned `core.autocrlf=false`.** `CollectionStore`
  writes LF. Inheriting `autocrlf=true` means git checks out CRLF, every request
  file reads as modified forever, and the dirty count never reaches zero.
- **Filepatterns joined with `/`.** They are POSIX paths whatever the platform;
  `relativize().toString()` gives backslashes on Windows, and `addFilepattern`
  then matches nothing and commits zero files without erroring.
- **No cached `Repository` handles.** An open repository holds `.git/index` and
  the pack files, and on Windows that makes the folder unmovable — which is what
  renaming or deleting a project does.
- **Pinned discovery.** `findGitDir()` walks upward and could find a repository
  above the collections root; `readEnvironment()` would let a stray `GIT_DIR`
  hijack every call.
- **One mutex per project**, or concurrent operations leave a stale
  `.git/index.lock` for the user to find and delete by hand.
- **A first commit at init**, so `branches`, `log`, `status` and ahead/behind
  never meet an unborn branch.
- **`pull` is fast-forward only.** A conflicted merge writes `<<<<<<<` into YAML
  that `RequestYaml.decode` then refuses, breaking every affected request at
  once. BitTrace is not a merge tool: it refuses and says where to go.

`git/GitStore.kt` caches a `GitState` per project as Compose state. There is no
file watcher anywhere in this app, so it is refreshed from four places: the
`onChanged` hook `CollectionStore` now calls after a mutation (the store still
knows nothing about git — it only announces that it touched a file), after every
git operation whether or not it succeeded, on API-view entry, and on window
focus. That last one is the only trigger that catches a change BitTrace did not
make, which in practice means `git pull` in a terminal.

**The branch is a combo box on the project row**, not a chip that opens a
picker: choosing from a list is what the control does, so it looks and behaves
like the app's other list pickers rather than a label that turns out to be a
button that turns out to open a dialog. Its options therefore have to exist
before it is clicked, which is why `GitState` carries the branch names — one
extra ref walk inside a repository the status read already has open, against a
second round trip. Remote-tracking branches appear only where no local branch
of that name exists: the two are the same branch, and offering both would make
picking one a coin toss with different consequences.

**Checkout and pull are blocked while a tab is dirty.** They rewrite files under
open editors, and reloading afterwards would discard the edits silently. The
guard offers Save all, and there is no Discard button because the operation can
simply wait. Afterwards `ApiClientState.reconcile` puts tabs back in step:
unchanged files are left alone so a no-op checkout does not recompose the
editor, and a request that does not exist on the new branch keeps its content,
loses its path and becomes an unsaved draft rather than being closed.

**Publishing** a project that has no remote asks for a URL, adds it as `origin`,
pushes and records the tracking config — and rolls the remote back if the push
fails, because the menu offers Publish only while there is no remote, so a
mistyped URL would otherwise leave a project that can never be published again
from the app. Two things JGit does not do on its own are handled here: a
rejected push is an ordinary result carrying a status rather than an exception,
so the statuses are checked or "Pushed to origin" gets printed for something the
remote threw out; and `PushCommand` has no `setUpstream`, so the two config keys
are written by hand, without which the branch reads as untracked forever and
Pull refuses immediately after a successful publish.

Auth is split by kind. SSH uses `~/.ssh` and the agent, with a passphrase
provider that always declines — the default tries to prompt on a console a
windowed app does not have, so a locked key would hang forever instead of
failing explainably. HTTPS uses a token from Settings, keyed by host, with
per-forge username conventions. The SSH factory is attached per transport rather
than through `SshSessionFactory.setInstance()`, because that is a JVM global the
plugins loaded into this same JVM could also reach.

One packaging note: JGit, sshd and JNA reach for JDK modules nothing else here
references, and jlink builds the image from what it is told. The list in
`nativeDistributions` came from `jdeps --list-deps` over those jars, plus
`jdk.crypto.ec` and `jdk.unsupported`, which jdeps structurally cannot see
because both are reached by ServiceLoader and reflection. Missing modules fail
only in the packaged app and only on the transport path.

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

  **The listener is written against what browsers actually do, not against one
  well-formed request.** A browser opens several connections to a host it is
  about to fetch from and may hold one of them silent, so: the accepted socket
  carries its own read timeout (`accept`'s does not cover it, and a thread
  parked in a socket read ignores cancellation, so a silent preconnect once
  blocked the redirect behind it for the whole authorisation and Stop could not
  end it either); the backlog has room for the extras; a connection that resets
  mid-read is skipped rather than failing the flow; and the request is read to
  the end of its headers before the page is written, because closing with unread
  bytes buffered is an RST that discards the response.

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
