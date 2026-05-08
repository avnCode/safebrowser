package com.safebrowser.app

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BrowserScreen(vm: BrowserViewModel) {
    val tabs       by vm.tabs.collectAsState()
    val activeId   by vm.activeTabId.collectAsState()
    val settings   by vm.settings.collectAsState()
    val bookmarks  by vm.bookmarks.collectAsState()
    val prompt     by vm.redirectPrompt.collectAsState()
    val toastMsg   by vm.toast.collectAsState()
    val snackbar   = remember { SnackbarHostState() }
    val scope      = rememberCoroutineScope()

    val activeTab = tabs.firstOrNull { it.id == activeId }
    var addrText by remember(activeTab?.id) {
        mutableStateOf(activeTab?.url?.takeIf { !it.startsWith("file://") } ?: "")
    }
    LaunchedEffect(activeTab?.url) {
        val u = activeTab?.url?.takeIf { !it.startsWith("file://") } ?: ""
        if (u != addrText) addrText = u
    }
    LaunchedEffect(toastMsg) {
        toastMsg?.let {
            scope.launch { snackbar.showSnackbar(it) }
            vm.consumeToast()
        }
    }

    var showBookmarks  by remember { mutableStateOf(false) }
    var showSettings   by remember { mutableStateOf(false) }
    var showTabs       by remember { mutableStateOf(false) }

    BackHandler {
        when {
            showBookmarks -> showBookmarks = false
            showSettings  -> showSettings  = false
            showTabs      -> showTabs      = false
            vm.goBackInActiveTab() -> Unit  // WebView handled it
            else          -> activeId.takeIf { it != 0L }?.let { vm.closeTab(it) }
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            Surface(tonalElevation = 2.dp) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                    ) {
                        IconButton(onClick = { showTabs = true }) {
                            BadgedBox(badge = { Badge { Text(tabs.size.toString()) } }) {
                                Icon(Icons.Default.Layers, contentDescription = "Tabs")
                            }
                        }
                        OutlinedTextField(
                            value = addrText,
                            onValueChange = { addrText = it },
                            modifier = Modifier.weight(1f),
                            singleLine = true,
                            shape = RoundedCornerShape(50),
                            placeholder = { Text("Search Google or enter URL") },
                            leadingIcon = {
                                if (activeTab?.isLoading == true) {
                                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                                } else {
                                    Icon(Icons.Default.Search, contentDescription = null)
                                }
                            },
                            trailingIcon = {
                                Row {
                                    val starred = bookmarks.any { it.url == activeTab?.url }
                                    IconButton(onClick = { vm.toggleBookmarkActive() }) {
                                        Icon(
                                            if (starred) Icons.Default.Star else Icons.Default.StarBorder,
                                            tint = if (starred) Color(0xFFFBBC05) else LocalContentColor.current,
                                            contentDescription = "Bookmark",
                                        )
                                    }
                                    IconButton(onClick = { showBookmarks = true }) {
                                        Icon(Icons.Default.BookmarkBorder, contentDescription = "Bookmarks")
                                    }
                                    IconButton(onClick = { showSettings = true }) {
                                        Icon(Icons.Default.MoreVert, contentDescription = "Menu")
                                    }
                                }
                            },
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go,
                            ),
                            keyboardActions = KeyboardActions(onGo = { vm.navigateActive(addrText) }),
                        )
                    }
                    Row(
                        Modifier.padding(top = 4.dp, start = 4.dp, end = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        AssistChip(
                            onClick = { vm.setMode(if (settings.mode == Mode.Strict) Mode.Lenient else Mode.Strict) },
                            label = { Text("Mode: ${settings.mode.name}") },
                            leadingIcon = {
                                Icon(
                                    if (settings.mode == Mode.Strict) Icons.Default.Lock else Icons.Default.LockOpen,
                                    null, modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        AssistChip(
                            onClick = { vm.setAdblock(!settings.adblock) },
                            label = { Text("Ads: ${if (settings.adblock) "ON" else "OFF"}") },
                            leadingIcon = {
                                Icon(
                                    if (settings.adblock) Icons.Default.Block else Icons.Default.AdsClick,
                                    null, modifier = Modifier.size(16.dp),
                                )
                            },
                        )
                        Spacer(Modifier.weight(1f))
                        IconButton(onClick = { vm.newTab() }) {
                            Icon(Icons.Default.Add, contentDescription = "New tab")
                        }
                    }
                }
            }
        },
    ) { padding ->
        Box(Modifier.padding(padding).fillMaxSize()) {
            WebViewHost(vm = vm, modifier = Modifier.fillMaxSize())
        }
    }

    if (showBookmarks) BookmarksSheet(
        bookmarks,
        onPick = { vm.loadInActive(it); showBookmarks = false },
        onDelete = vm::deleteBookmark,
        onDismiss = { showBookmarks = false }
    )

    if (showSettings) SettingsSheet(vm, settings, activeTab) { showSettings = false }

    if (showTabs) TabsSheet(
        tabs, activeId,
        onPick = { vm.activate(it); showTabs = false },
        onClose = vm::closeTab,
        onNew = { vm.newTab(); showTabs = false },
        onDismiss = { showTabs = false }
    )

    prompt?.let { p ->
        AlertDialog(
            onDismissRequest = { vm.confirmRedirect(false) },
            icon = { Icon(Icons.Default.Warning, null) },
            title = { Text("Cross-site redirect blocked") },
            text = {
                Column {
                    Text("From: ${p.expected}")
                    Spacer(Modifier.height(4.dp))
                    Text("To: ${p.nextUrl}", maxLines = 3)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "This page tried to send you to a different site. Allow once?",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            },
            confirmButton = { TextButton(onClick = { vm.confirmRedirect(true) }) { Text("Allow once") } },
            dismissButton = { TextButton(onClick = { vm.confirmRedirect(false) }) { Text("Block") } },
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun BookmarksSheet(
    bookmarks: List<Bookmark>,
    onPick: (String) -> Unit,
    onDelete: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Bookmarks", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))
            if (bookmarks.isEmpty()) {
                Text("No bookmarks yet. Tap the ☆ to save the current page.")
            } else {
                LazyColumn(Modifier.heightIn(max = 480.dp)) {
                    items(bookmarks, key = { it.id }) { b ->
                        ListItem(
                            headlineContent = { Text(b.title.ifBlank { b.url }, maxLines = 1) },
                            supportingContent = { Text(UrlNormalizer.host(b.url) ?: b.url, maxLines = 1) },
                            trailingContent = {
                                IconButton(onClick = { onDelete(b.id) }) {
                                    Icon(Icons.Default.Close, contentDescription = "Remove")
                                }
                            },
                            modifier = Modifier.clickable { onPick(b.url) },
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SettingsSheet(
    vm: BrowserViewModel,
    settings: Settings,
    activeTab: TabState?,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Text("Settings", style = MaterialTheme.typography.titleLarge)
            Spacer(Modifier.height(12.dp))

            Text("Theme", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ThemeChoice.entries.forEach { t ->
                    FilterChip(
                        selected = settings.theme == t,
                        onClick = { vm.setTheme(t) },
                        label = { Text(t.name) },
                    )
                }
            }

            Spacer(Modifier.height(12.dp))
            Text("Redirect protection", style = MaterialTheme.typography.titleMedium)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Mode.entries.forEach { m ->
                    FilterChip(
                        selected = settings.mode == m,
                        onClick = { vm.setMode(m) },
                        label = { Text(m.name) },
                    )
                }
            }
            Text(
                when (settings.mode) {
                    Mode.Strict ->  "Strict — confirm cross-origin redirects."
                    Mode.Lenient -> "Lenient — silently allow same-tab cross-origin nav."
                },
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(16.dp))
            Text("Ad / tracker blocker", style = MaterialTheme.typography.titleMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = settings.adblock, onCheckedChange = { vm.setAdblock(it) })
                Spacer(Modifier.width(8.dp))
                Text(if (settings.adblock) "Enabled" else "Disabled")
            }

            Spacer(Modifier.height(12.dp))
            Text("Per-host allow-list", style = MaterialTheme.typography.titleMedium)
            Text(
                "Hosts where the ad blocker is OFF (some sites need this).",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(6.dp))
            HostChips(items = vm.adBlocker.allowList(), onRemove = { vm.toggleHostAllow(it) })
            val currentHost = activeTab?.url?.let { UrlNormalizer.host(it) }
            if (!currentHost.isNullOrBlank()) {
                val isAllowed = vm.adBlocker.isAllowed(currentHost)
                Spacer(Modifier.height(8.dp))
                AssistChip(
                    onClick = { vm.toggleHostAllow(currentHost) },
                    label = { Text((if (isAllowed) "Re-enable on " else "Allow ") + currentHost) },
                )
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TabsSheet(
    tabs: List<TabState>,
    activeId: Long,
    onPick: (Long) -> Unit,
    onClose: (Long) -> Unit,
    onNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.fillMaxWidth().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Tabs (${tabs.size})", style = MaterialTheme.typography.titleLarge)
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onNew) {
                    Icon(Icons.Default.Add, null); Spacer(Modifier.width(4.dp)); Text("New")
                }
            }
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 480.dp)) {
                items(tabs, key = { it.id }) { t ->
                    ListItem(
                        headlineContent = { Text(t.title, maxLines = 1) },
                        supportingContent = { Text(UrlNormalizer.host(t.url) ?: t.url, maxLines = 1) },
                        trailingContent = {
                            IconButton(onClick = { onClose(t.id) }) {
                                Icon(Icons.Default.Close, contentDescription = "Close tab")
                            }
                        },
                        colors = if (t.id == activeId)
                            ListItemDefaults.colors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
                        else ListItemDefaults.colors(),
                        modifier = Modifier.clickable { onPick(t.id) },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun HostChips(items: List<String>, onRemove: (String) -> Unit) {
    if (items.isEmpty()) {
        Text("(empty)", style = MaterialTheme.typography.bodySmall)
        return
    }
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(items) { host ->
            AssistChip(
                onClick = { onRemove(host) },
                label = { Text(host) },
                trailingIcon = { Icon(Icons.Default.Close, null, Modifier.size(16.dp)) },
            )
        }
    }
}
