package com.arin.builder

/**
 * ARIN-PROTECTED: import-guard — Arin itself (no AI, no credit) adds the imports models forget (Compose, Material 3,
 * icons, lifecycle, coroutines, Room, navigation, Android) and the Material 3 opt-in, before every build and when the
 * compiler reports "Unresolved reference". These are the most common build errors after bracket mistakes.
 */
object KotlinImports {
    private const val RT = "androidx.compose.runtime"
    private const val LAYOUT = "androidx.compose.foundation.layout"
    private const val M3 = "androidx.compose.material3"

    /** Symbol → its import, and how the symbol must appear in code to count as used. */
    private data class Sym(val name: String, val import: String, val compose: Boolean, val use: Regex)

    private fun call(n: String) = Regex("""(?<![\w.])$n\s*[({<]""")
    private fun ext(n: String) = Regex("""\.$n\s*\(""")
    private fun type(n: String) = Regex("""(?<![\w.])$n\b(?!\s*=)""")
    private fun ann(n: String) = Regex("""@$n\b""")

    private val table: List<Sym> = buildList {
        fun c(pkg: String, how: (String) -> Regex, vararg names: String) = names.forEach { add(Sym(it, "$pkg.$it", true, how(it))) }
        fun a(pkg: String, how: (String) -> Regex, vararg names: String) = names.forEach { add(Sym(it, "$pkg.$it", false, how(it))) }
        c(RT, ::call, "remember", "mutableStateOf", "mutableIntStateOf", "mutableFloatStateOf", "mutableLongStateOf", "mutableDoubleStateOf",
            "mutableStateListOf", "mutableStateMapOf", "LaunchedEffect", "DisposableEffect", "SideEffect", "rememberCoroutineScope",
            "derivedStateOf", "rememberUpdatedState", "produceState", "snapshotFlow", "CompositionLocalProvider",
            "staticCompositionLocalOf", "compositionLocalOf")
        c(RT, ::ext, "collectAsState")
        c(RT, ::ann, "Composable", "Stable", "Immutable")
        c("$RT.saveable", ::call, "rememberSaveable")
        c("androidx.compose.ui", ::type, "Modifier", "Alignment")
        c(LAYOUT, ::type, "Arrangement", "PaddingValues")
        c(LAYOUT, ::call, "Column", "Row", "Box", "Spacer", "BoxWithConstraints", "FlowRow", "FlowColumn")
        c(LAYOUT, ::ext, "padding", "fillMaxSize", "fillMaxWidth", "fillMaxHeight", "height", "width", "size", "wrapContentSize",
            "wrapContentWidth", "wrapContentHeight", "offset", "aspectRatio", "systemBarsPadding", "statusBarsPadding",
            "navigationBarsPadding", "imePadding", "safeDrawingPadding", "heightIn", "widthIn", "sizeIn", "requiredSize")
        c("androidx.compose.ui.graphics", ::type, "Color", "Brush")
        c("androidx.compose.ui.graphics.vector", ::type, "ImageVector")
        c("androidx.compose.ui.unit", { n -> Regex("""\d\.$n\b|\)\.$n\b""") }, "dp", "sp")
        c("androidx.compose.ui.text", ::type, "TextStyle")
        c("androidx.compose.ui.text.font", ::type, "FontWeight", "FontFamily", "FontStyle")
        c("androidx.compose.ui.text.style", ::type, "TextAlign", "TextOverflow", "TextDecoration")
        c("androidx.compose.ui.text.input", ::type, "KeyboardType", "ImeAction", "PasswordVisualTransformation", "VisualTransformation")
        c("androidx.compose.foundation.text", ::type, "KeyboardOptions", "KeyboardActions")
        c("androidx.compose.ui.platform", ::type, "LocalContext", "LocalConfiguration", "LocalFocusManager", "LocalClipboardManager", "LocalUriHandler")
        c("androidx.compose.ui.draw", ::ext, "clip", "shadow", "alpha", "rotate", "scale", "blur")
        c("androidx.compose.ui.layout", ::type, "ContentScale")
        c("androidx.compose.ui.res", ::call, "painterResource", "stringResource", "colorResource")
        c("androidx.compose.ui.tooling.preview", ::ann, "Preview")
        c("androidx.compose.foundation", ::ext, "background", "clickable", "border", "verticalScroll", "horizontalScroll", "combinedClickable")
        c("androidx.compose.foundation", ::call, "Image", "rememberScrollState", "Canvas", "isSystemInDarkTheme")
        c("androidx.compose.foundation.shape", ::call, "RoundedCornerShape")
        c("androidx.compose.foundation.shape", ::type, "CircleShape")
        c("androidx.compose.foundation.lazy", ::call, "LazyColumn", "LazyRow", "rememberLazyListState")
        c("androidx.compose.foundation.lazy.grid", ::call, "LazyVerticalGrid", "LazyHorizontalGrid")
        c("androidx.compose.foundation.lazy.grid", ::type, "GridCells")
        c(M3, ::type, "MaterialTheme", "CardDefaults", "ButtonDefaults", "TopAppBarDefaults", "SnackbarHostState", "ExperimentalMaterial3Api",
            "TextFieldDefaults", "OutlinedTextFieldDefaults", "SwitchDefaults")
        c(M3, ::call, "Text", "Button", "TextButton", "OutlinedButton", "ElevatedButton", "FilledTonalButton", "IconButton", "Icon", "Card",
            "ElevatedCard", "OutlinedCard", "Scaffold", "TopAppBar", "CenterAlignedTopAppBar", "LargeTopAppBar", "MediumTopAppBar",
            "FloatingActionButton", "ExtendedFloatingActionButton", "OutlinedTextField", "TextField", "Surface", "AlertDialog", "Switch",
            "Checkbox", "Slider", "HorizontalDivider", "VerticalDivider", "CircularProgressIndicator", "LinearProgressIndicator",
            "NavigationBar", "NavigationBarItem", "SnackbarHost", "darkColorScheme", "lightColorScheme", "DropdownMenu",
            "DropdownMenuItem", "RadioButton", "FilterChip", "AssistChip", "SuggestionChip", "InputChip", "Tab", "TabRow",
            "ScrollableTabRow", "ModalBottomSheet", "Badge", "BadgedBox", "ListItem", "Typography", "Snackbar", "rememberModalBottomSheetState",
            "ModalNavigationDrawer", "ModalDrawerSheet", "NavigationDrawerItem", "rememberDrawerState", "SearchBar", "DatePicker",
            "rememberDatePickerState", "TimePicker", "rememberTimePickerState", "ExposedDropdownMenuBox", "SegmentedButton",
            "SingleChoiceSegmentedButtonRow", "PullToRefreshBox")
        c(M3, ::type, "DrawerValue", "SnackbarDuration")
        c("androidx.compose.material.icons", ::type, "Icons")
        a("androidx.activity", ::type, "ComponentActivity")
        a("androidx.activity", ::call, "enableEdgeToEdge")
        a("androidx.activity.compose", ::call, "setContent", "rememberLauncherForActivityResult", "BackHandler")
        a("androidx.activity.result.contract", ::type, "ActivityResultContracts")
        a("androidx.lifecycle", ::type, "ViewModel", "AndroidViewModel")
        a("androidx.lifecycle", { n -> Regex("""(?<![\w.])$n\b""") }, "viewModelScope")
        c("androidx.lifecycle.viewmodel.compose", ::call, "viewModel")
        c("androidx.lifecycle.compose", ::ext, "collectAsStateWithLifecycle")
        a("kotlinx.coroutines", { n -> Regex("""\.$n\s*\{|(?<![\w.])$n\s*\(\s*Dispatchers""") }, "launch")
        a("kotlinx.coroutines", ::call, "delay", "withContext", "coroutineScope", "async")
        a("kotlinx.coroutines", ::type, "Dispatchers", "Job", "CoroutineScope")
        a("kotlinx.coroutines.flow", ::type, "MutableStateFlow", "StateFlow", "MutableSharedFlow", "SharedFlow", "Flow")
        a("kotlinx.coroutines.flow", ::ext, "asStateFlow", "asSharedFlow", "stateIn")
        a("android.os", ::type, "Bundle")
        a("android.content", ::type, "Context", "Intent")
        a("android.widget", ::type, "Toast")
        a("android.util", ::type, "Log")
        a("android.net", ::type, "Uri")
        a("androidx.room", ::ann, "Entity", "PrimaryKey", "Dao", "Query", "Insert", "Update", "Delete", "Database", "ColumnInfo", "TypeConverters", "TypeConverter", "Transaction", "Upsert")
        a("androidx.room", ::type, "RoomDatabase", "Room", "OnConflictStrategy")
        a("androidx.navigation.compose", ::call, "NavHost", "composable", "rememberNavController")
        a("androidx.navigation", ::type, "NavHostController", "NavController")
    }
    private val byName = table.groupBy { it.name }

    /** The code with comments removed and string contents blanked (string templates ${…} are kept as code). */
    private fun codeOnly(t: String): String {
        val out = StringBuilder(t.length); var i = 0; val n = t.length
        val modes = ArrayDeque<Int>(); val tpl = ArrayDeque<Int>() // 1 = "…", 3 = """…""", 0 = ${…}
        while (i < n) {
            val c = t[i]; val m = modes.lastOrNull()
            if (m == 1 || m == 3) {
                when {
                    m == 1 && c == '\\' -> { out.append("  "); i += 2; continue }
                    m == 1 && c == '\n' -> { modes.removeLast(); out.append(c) }
                    c == '$' && i + 1 < n && t[i + 1] == '{' -> { modes.addLast(0); tpl.addLast(0); out.append("  "); i += 2; continue }
                    m == 3 && t.startsWith("\"\"\"", i) -> { modes.removeLast(); out.append("\"\"\""); i += 3; continue }
                    m == 1 && c == '"' -> { modes.removeLast(); out.append('"') }
                    else -> out.append(if (c == '\n') '\n' else ' ')
                }
                i++; continue
            }
            when {
                t.startsWith("//", i) -> { while (i < n && t[i] != '\n') i++; continue }
                t.startsWith("/*", i) -> { val e = t.indexOf("*/", i + 2); i = if (e < 0) n else e + 2; out.append(' '); continue }
                t.startsWith("\"\"\"", i) -> { modes.addLast(3); out.append("\"\"\""); i += 3; continue }
                c == '\'' -> {
                    val end = if (i + 1 < n && t[i + 1] == '\\') t.indexOf('\'', i + 2) else i + 2
                    if (end in (i + 1)..(i + 8) && end < n && t[end] == '\'') { out.append(" ".repeat(end + 1 - i)); i = end + 1; continue }
                    out.append(c)
                }
                c == '"' -> { modes.addLast(1); out.append('"') }
                c == '{' && m == 0 -> { tpl.addLast(tpl.removeLast() + 1); out.append(c) }
                c == '}' && m == 0 -> {
                    val d = tpl.removeLast()
                    if (d == 0) { modes.removeLast(); out.append(' ') } else { tpl.addLast(d - 1); out.append(c) }
                }
                else -> out.append(c)
            }
            i++
        }
        return out.toString()
    }

    private fun imported(t: String, imp: String): Boolean {
        val pkg = imp.substringBeforeLast('.')
        return Regex("""(?m)^\s*import\s+${Regex.escape(imp)}\s*$""").containsMatchIn(t) ||
            Regex("""(?m)^\s*import\s+${Regex.escape(pkg)}\.\*\s*$""").containsMatchIn(t) ||
            Regex("""(?m)^\s*import\s+[\w.]+\.${Regex.escape(imp.substringAfterLast('.'))}(\s+as\s+\w+)?\s*$""").containsMatchIn(t)
    }

    /** Names declared anywhere in the project (a project's own `Typography` must not get Material's import). */
    fun declared(files: Map<String, String>): Set<String> = files.filterKeys { it.endsWith(".kt") }.values.flatMap { t ->
        Regex("""(?m)(?:fun|class|object|interface|val|var|typealias)\s+(?:<[^>]*>\s*)?(?:[\w.]+\.)?(\w+)""").findAll(t).map { it.groupValues[1] }.toList()
    }.toSet()

    /**
     * Returns the file with the missing imports (and the Material 3 opt-in) added, or the same text. [mustHave]: names
     * the compiler reported unresolved in this file. [own]: names the project declares itself.
     */
    fun fix(path: String, text: String, own: Set<String>, mustHave: Set<String> = emptySet(), rPackage: String? = null): Pair<String, List<String>> {
        if (!path.endsWith(".kt")) return text to emptyList()
        val code = codeOnly(text)
        val compose = code.contains("@Composable") || code.contains("setContent")
        val add = linkedSetOf<String>()
        val wildcard = Regex("""(?m)^\s*import\s+[\w.]+\.\*\s*$""").containsMatchIn(text)
        val room = text.contains("androidx.room") || Regex("@(Dao|Entity|Database)\\b").containsMatchIn(code)
        for (s in table) {
            if (s.name in own) continue
            if (s.compose && !compose && s.name !in mustHave) continue
            if (imported(text, s.import)) continue
            if (byName[s.name]!!.size > 1 && s.name !in mustHave) continue
            // With a wildcard import (e.g. Material 2's androidx.compose.material.*) a name may already come from it:
            // only names the compiler itself reported missing are added then.
            if (wildcard && s.name !in mustHave) continue
            if (s.import.startsWith("androidx.room.") && !room && s.name !in mustHave) continue
            if (s.name in mustHave || s.use.containsMatchIn(code)) add += s.import
        }
        // Icons.Default.Add / Icons.Filled.X / Icons.Outlined.X / Icons.AutoMirrored.Filled.X → their own imports.
        Regex("""Icons\.(AutoMirrored\.)?(Default|Filled|Outlined|Rounded|Sharp|TwoTone)\.(\w+)""").findAll(code).forEach { m ->
            val style = if (m.groupValues[2] == "Default") "filled" else m.groupValues[2].lowercase()
            val imp = "androidx.compose.material.icons." + (if (m.groupValues[1].isNotEmpty()) "automirrored." else "") + style + "." + m.groupValues[3]
            if (!imported(text, imp) && !Regex("""(?m)^\s*import\s+${Regex.escape(imp)}\s*$""").containsMatchIn(text)) add += imp
        }
        // R.string.x in a file outside the app's main package needs `import <package>.R` ("Unresolved reference 'R'").
        val filePkg = Regex("""(?m)^\s*package\s+([\w.]+)""").find(text)?.groupValues?.get(1)
        if (rPackage != null && filePkg != null && filePkg != rPackage &&
            (Regex("""(?<![\w.])R\.(string|drawable|mipmap|color|raw|id|layout|font|xml|dimen|style|array|menu|anim|plurals|integer|bool)\b""").containsMatchIn(code) || "R" in mustHave) &&
            !Regex("""(?m)^\s*import\s+[\w.]+\.R\s*$""").containsMatchIn(text)) add += "$rPackage.R"
        // `by remember { … }` / `by viewModel.state.collectAsState()` needs getValue (and setValue for var).
        if (Regex("""\bby\s+(remember|rememberSaveable|mutableStateOf|mutable\w*StateOf|derivedStateOf|[\w.]+\.collectAsState|[\w.]+\.collectAsStateWithLifecycle|produceState)""").containsMatchIn(code) || "getValue" in mustHave) {
            if (!imported(text, "$RT.getValue")) add += "$RT.getValue"
            if ((Regex("""\bvar\s+\w+(\s*:\s*[\w<>?]+)?\s+by\s""").containsMatchIn(code) || "setValue" in mustHave) && !imported(text, "$RT.setValue")) add += "$RT.setValue"
        }
        // LazyColumn/LazyRow `items(list) { … }` needs its own import (without it Kotlin picks items(count: Int) and
        // reports a type mismatch); grids have their own.
        if (compose && Regex("""(?<![\w.])(items|itemsIndexed)\s*\(""").containsMatchIn(code)) {
            fun has(fq: String) = Regex("""(?m)^\s*import\s+(${Regex.escape(fq)}|${Regex.escape(fq.substringBeforeLast('.'))}\.\*)\s*$""").containsMatchIn(text)
            val usesItems = Regex("""(?<![\w.])items\s*\(""").containsMatchIn(code)
            val usesIndexed = Regex("""(?<![\w.])itemsIndexed\s*\(""").containsMatchIn(code)
            if (Regex("""(?<![\w.])Lazy(Column|Row)\s*[({]""").containsMatchIn(code)) {
                if (usesItems && !has("androidx.compose.foundation.lazy.items")) add += "androidx.compose.foundation.lazy.items"
                if (usesIndexed && !has("androidx.compose.foundation.lazy.itemsIndexed")) add += "androidx.compose.foundation.lazy.itemsIndexed"
            }
            if (Regex("""(?<![\w.])Lazy(Vertical|Horizontal)Grid\s*[({]""").containsMatchIn(code)) {
                if (usesItems && !has("androidx.compose.foundation.lazy.grid.items")) add += "androidx.compose.foundation.lazy.grid.items"
                if (usesIndexed && !has("androidx.compose.foundation.lazy.grid.itemsIndexed")) add += "androidx.compose.foundation.lazy.grid.itemsIndexed"
            }
        }
        var out = text
        val notes = mutableListOf<String>()
        if (add.isNotEmpty()) {
            out = addImports(out, add.toList())
            notes += "added ${add.size} import(s): " + add.joinToString { it.substringAfterLast('.') }
        }
        // Material 3 experimental components need an opt-in, or the build fails.
        val experimental = Regex("""(?<![\w.])(TopAppBar|CenterAlignedTopAppBar|LargeTopAppBar|MediumTopAppBar|ModalBottomSheet|rememberModalBottomSheetState|ExposedDropdownMenuBox|DatePicker|rememberDatePickerState|TimePicker|rememberTimePickerState|SearchBar|TopAppBarDefaults|SegmentedButton|PullToRefreshBox)\s*[({.]""")
        if (compose && experimental.containsMatchIn(code) && !text.contains("ExperimentalMaterial3Api::class") && !out.contains("ExperimentalMaterial3Api::class")) {
            out = KotlinFixes.addFileOptIn(out, "androidx.compose.material3.ExperimentalMaterial3Api")
            notes += "added the Material 3 opt-in"
        }
        return out to notes
    }

    /** Adds import lines after the last import (or after the package line), skipping ones already there. */
    fun addImports(text: String, imports: List<String>): String {
        val lines = text.lines().toMutableList()
        val have = lines.map { it.trim() }.toSet()
        val block = imports.distinct().map { "import $it" }.filter { it !in have }
        if (block.isEmpty()) return text
        val lastImport = lines.indexOfLast { it.trimStart().startsWith("import ") }
        val pkg = lines.indexOfFirst { it.trimStart().startsWith("package ") }
        val at = if (lastImport >= 0) lastImport + 1 else if (pkg >= 0) pkg + 1 else lines.indexOfLast { it.trimStart().startsWith("@file:") } + 1
        lines.addAll(at, if (lastImport < 0) listOf("") + block else block)
        return lines.joinToString("\n")
    }

    /** "Unresolved reference 'x'" / "Unresolved reference: x" → per file name, the names. */
    fun unresolved(errors: String): Map<String, Set<String>> =
        Regex("""([\w-]+\.kt):\d+:\d+\s+Unresolved reference:?\s*'?`?(\w+)""").findAll(errors)
            .groupBy({ it.groupValues[1] }, { it.groupValues[2] }).mapValues { it.value.toSet() }

    /** Names this fixer knows. */
    fun knows(name: String): Boolean = name in byName || name == "getValue" || name == "setValue" || name == "R"
}
