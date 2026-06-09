# phone_companion — Gap Resolution Spec

**Repo:** `dwilson2547/robo-services`, path `phone_companion/`  
**Target:** Android Kotlin / Jetpack Compose app  
**Purpose:** Fill the ten gaps identified in the top-level review. Implement in any order; each item is self-contained. Commit each item separately with a matching CHANGELOG entry per repo convention.

---

## Item 1 — Signal and message deletion

**Priority: High**

### Problem
`SignalEditorScreen` and `SignalsScreen` provide no way to remove a signal or a message from the active DBC. There is also no way to remove an entire message. Wrong definitions must be deleted.

### Required changes

**`SignalsScreen.kt` — `SignalRow`**

Add a delete affordance. Long-press currently opens the verify dialog; keep that behavior and add a secondary action. Recommended: add a trailing `IconButton` with `Icons.Default.Delete` visible only when the parent `MessageRow` is expanded.

```kotlin
// In SignalRow, add trailing delete icon
IconButton(onClick = onDelete) {
    Icon(Icons.Default.Delete, contentDescription = "Delete signal",
        tint = MaterialTheme.colorScheme.error)
}
```

Wire `onDelete` up through `MessageRow` → `SignalsScreen` → a new `CanBusViewModel.deleteSignal(rawId: Int, signalName: String)` call.

**`SignalsScreen.kt` — `MessageRow`**

Add a "Delete message" option accessible via a trailing `DropdownMenu` on the message header row (three-dot icon). Confirm with an `AlertDialog` before deleting.

**`CanBusViewModel.kt`**

```kotlin
fun deleteSignal(rawId: Int, signalName: String) {
    val dbc = _activeDbc.value ?: return
    val dbcId = _activeDbcId.value ?: return
    val msg = dbc.messages[rawId] ?: return
    val updated = msg.copy(signals = msg.signals.filterNot { it.name == signalName })
    val newMessages = dbc.messages.toMutableMap()
    newMessages[rawId] = updated
    val updatedDbc = dbc.copy(messages = newMessages)
    settingsVm.dbcRepository.save(dbcId, updatedDbc)  // or inject repo directly
    _activeDbc.value = updatedDbc
}

fun deleteMessage(rawId: Int) {
    val dbc = _activeDbc.value ?: return
    val dbcId = _activeDbcId.value ?: return
    val newMessages = dbc.messages.toMutableMap()
    newMessages.remove(rawId)
    val updatedDbc = dbc.copy(messages = newMessages)
    settingsVm.dbcRepository.save(dbcId, updatedDbc)
    _activeDbc.value = updatedDbc
}
```

> Note: `CanBusViewModel` does not currently hold a reference to `DbcRepository`. Either inject it, or route the save through `SettingsViewModel` the same way `saveSignal()` in `SignalEditorScreen` already does (calling `settingsVm.dbcRepository.save()`). The latter is simpler and consistent with the existing pattern.

---

## Item 2 — Editable DLC on new message creation

**Priority: High**

### Problem
`saveSignal()` in `SignalEditorScreen.kt` hardcodes `dlc = 8` when creating a new message:

```kotlin
val msg = DbcMessage(rawId = newRawId, name = msgName.trim(), dlc = 8, signals = listOf(newSignal))
```

Many W-body HS-CAN frames are 3–5 bytes. An incorrect DLC causes the `BitGrid` to show phantom bytes and allows the user to define signals past the real frame boundary.

### Required changes

**`SignalEditorScreen.kt`**

Add a `dlcInput` form field shown only when `isNewMessage == true`. Default it to the observed frame's actual DLC when navigating from `UnknownsScreen` (pass the observed DLC as a nav argument or look it up from `canBusVm.lastFrameData(canId)?.size`).

```kotlin
// Near the msgName field, inside the isNewMessage block:
var dlcInput by rememberSaveable { mutableStateOf(
    (canBusVm.lastFrameData(rawId?.and(0x1FFFFFFF) ?: 0)?.size ?: 8).toString()
) }

OutlinedTextField(
    value = dlcInput,
    onValueChange = { dlcInput = it.filter { c -> c.isDigit() }.take(1) },
    label = { Text("DLC (bytes)") },
    modifier = Modifier.fillMaxWidth(0.4f),
    singleLine = true,
    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
    isError = dlcInput.toIntOrNull()?.let { it in 1..8 } == false,
)
```

Update `dlc` used for `maxStartBit` and `BitGrid` to prefer the observed DLC or the user-entered DLC over the hardcoded 8:

```kotlin
val dlc = when {
    existingMessage != null -> existingMessage.dlc
    else -> dlcInput.toIntOrNull()?.coerceIn(1, 8) ?: 8
}
```

Pass the resolved `dlc` into `saveSignal()` and use it in the `DbcMessage` constructor.

---

## Item 3 — Search / filter on Unknowns and Signals screens

**Priority: Medium**

### Problem
Both `UnknownsScreen` and `SignalsScreen` have no search or filter. At 50+ IDs this becomes difficult to navigate.

### Required changes

**`UnknownsScreen.kt`**

Add a `TextField` at the top of the screen (below the trigger bar). Filter `unknowns` by ID hex string prefix match:

```kotlin
var query by remember { mutableStateOf("") }

OutlinedTextField(
    value = query,
    onValueChange = { query = it.uppercase() },
    label = { Text("Filter by ID") },
    modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
    singleLine = true,
    leadingIcon = { Icon(Icons.Default.Search, null) },
    trailingIcon = {
        if (query.isNotEmpty()) IconButton(onClick = { query = "" }) {
            Icon(Icons.Default.Clear, null)
        }
    }
)

val filtered = if (query.isEmpty()) unknowns
    else unknowns.filter { "0x%03X".format(it.id).contains(query) || 
                            "0x%08X".format(it.id).contains(query) }
```

**`SignalsScreen.kt`**

Add the same search field above the `LazyColumn`. Filter on message name and CAN ID:

```kotlin
val filtered = if (query.isEmpty()) messages
    else messages.filter { 
        it.name.contains(query, ignoreCase = true) || 
        "0x%03X".format(it.canId).contains(query.uppercase())
    }
```

---

## Item 4 — Lightweight coverage badges (pre-Phase 4)

**Priority: Medium**

### Problem
There is no feedback on how many defined messages were actually observed in a session. This is the core "is my DBC correct" loop.

### Required changes

**`SignalsScreen.kt` — tab badge / header**

Add a summary row at the top of the screen showing seen vs. total:

```kotlin
val seenCount = messages.count { known.containsKey(it.canId) }
val totalCount = messages.size

Text(
    "$seenCount / $totalCount messages seen this session",
    modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
    style = MaterialTheme.typography.labelSmall,
    color = if (seenCount == totalCount) ColorVerified 
            else MaterialTheme.colorScheme.onSurfaceVariant,
)
```

**`MessageRow`**

Color-code the message header: green tint if seen in this session (`live != null`), dim if not seen. A small dot indicator (like the one on `UnknownIdRow`) is sufficient — no need for a full coverage bar at this stage.

**`LogBrowserScreen.kt`** (stretch goal for this item)

Add a coverage summary card at the top of the log browser showing how many DBC messages appear in the log, using the same seen/total pattern.

---

## Item 5 — Frame inspector for known messages

**Priority: High**

### Problem
`FrameInspectorScreen` filters from `vm.unknownIds`, so once a CAN ID is in the DBC it becomes inaccessible for byte-level inspection. You cannot verify a signal's decoded value against its raw bytes after it has been defined.

### Required changes

**`FrameInspectorScreen.kt`**

Generalize the data source. Instead of only reading from `vm.unknownIds`, also accept frames from `vm.knownMessages`:

```kotlin
// Replace the single unknowns lookup with a fallback to knownMessages:
val unknowns by vm.unknownIds.collectAsState()
val known by vm.knownMessages.collectAsState()

val unknownState = unknowns.find { it.id == canId }
val knownState = known[canId]

// Unified frame list:
val frames: List<CanFrame> = unknownState?.recentFrames
    ?: knownState?.lastFrame?.let { listOf(it) }
    ?: emptyList()
val dlc = (frames.maxOfOrNull { it.data.size } ?: 8).coerceAtMost(8)
```

For known messages, also render signal overlays on the byte grid — highlight which cells belong to each defined signal using the same `computeSelectedBits()` logic already in `BitGrid.kt`. Each signal gets a distinct tint color.

**`CanBusViewModel.kt`**

`knownMessages` only stores the last frame per ID. Add a `recentFrames` ring buffer to `MessageState` (analogous to `unknownIdFrames`) so the inspector has a history to diff:

```kotlin
data class MessageState(
    val message: DbcMessage,
    val lastFrame: CanFrame,
    val decodedSignals: Map<String, Double>,
    val updateRateHz: Float,
    val recentFrames: List<CanFrame> = emptyList(),  // add this
)
```

Cap at `UNKNOWN_HISTORY_SIZE` (20) frames. Populate it in `processBatch()` the same way `unknownIdFrames` is populated.

**Navigation**

Add an "Inspect" action to `SignalsScreen` message rows (or a per-signal tap that goes to inspector rather than editor). Route: `"inspector/{canId}"` already exists — just wire it in.

---

## Item 6 — Signal comment / description field in editor

**Priority: High**

### Problem
`DbcSignal.comment` is fully parsed and written (`CM_` blocks in DBC), but the `SignalEditorScreen` has no field for it. This is the primary place to document intent, source, and confidence on tentative signals.

### Required changes

**`SignalEditorScreen.kt`**

Add a multiline `OutlinedTextField` for the comment, below the scaling section:

```kotlin
var comment by rememberSaveable { mutableStateOf(existingSignal?.comment ?: "") }

OutlinedTextField(
    value = comment,
    onValueChange = { comment = it },
    label = { Text("Notes / comment") },
    modifier = Modifier.fillMaxWidth(),
    minLines = 2,
    maxLines = 4,
)
```

Pass `comment` into `saveSignal()` and assign it to `newSignal.copy(comment = comment.trim().ifEmpty { null })`.

Also add a comment field for `DbcMessage` on new message creation (single-line, optional).

---

## Item 7 — Value description (VAL_) editor

**Priority: Medium**

### Problem
`DbcSignal.valueDescriptions` is parsed and written correctly, but there is no UI to add or edit enum entries. Gear position, PRNDL, and mode signals are the primary use case.

### Required changes

**`SignalEditorScreen.kt`**

Add a collapsible "Value descriptions" section below the comment field. Show existing entries in a `LazyColumn` with inline edit/delete, and an "Add entry" button that opens a small dialog for raw value → label:

```kotlin
var valueDescriptions by remember {
    mutableStateOf(existingSignal?.valueDescriptions ?: emptyMap<Long, String>())
}

// Collapsible section header
var showValDesc by remember { mutableStateOf(valueDescriptions.isNotEmpty()) }
Row(
    modifier = Modifier.fillMaxWidth().clickable { showValDesc = !showValDesc },
    horizontalArrangement = Arrangement.SpaceBetween,
) {
    Text("Value descriptions (${valueDescriptions.size})", 
        style = MaterialTheme.typography.labelMedium)
    Icon(if (showValDesc) Icons.Default.ExpandLess else Icons.Default.ExpandMore, null)
}

if (showValDesc) {
    valueDescriptions.entries.sortedBy { it.key }.forEach { (raw, label) ->
        Row(modifier = Modifier.fillMaxWidth().padding(start = 16.dp)) {
            Text("$raw → $label", modifier = Modifier.weight(1f), fontSize = 12.sp)
            IconButton(onClick = { valueDescriptions = valueDescriptions - raw }) {
                Icon(Icons.Default.Delete, null, tint = MaterialTheme.colorScheme.error)
            }
        }
    }
    // Add entry button
    var showAddDialog by remember { mutableStateOf(false) }
    TextButton(onClick = { showAddDialog = true }) { Text("+ Add entry") }
    if (showAddDialog) {
        ValueDescriptionDialog(
            onConfirm = { raw, label ->
                valueDescriptions = valueDescriptions + (raw to label)
                showAddDialog = false
            },
            onDismiss = { showAddDialog = false }
        )
    }
}
```

`ValueDescriptionDialog` takes a raw `Long` and a label string. Pass the final `valueDescriptions` map into `saveSignal()` and assign it to `newSignal`.

---

## Item 8 — Trigger window survives freeze

**Priority: Low**

### Problem
`triggeredInWindow` is evaluated in `processBatch()`, which is gated by `_isFrozen`. If the user freezes after a trigger, frames that arrived between the trigger and the freeze may not have their `triggeredInWindow` flag set because the batch that contained them was suppressed.

### Required changes

**`CanBusViewModel.kt`**

Buffer inbound frames even when frozen; only suppress the StateFlow updates to the UI. The `rawFrameChannel` already decouples BLE callbacks from the processing loop, so the simplest fix is to keep draining the channel and processing batches (including updating `unknownIdFrames` and `triggeredInWindow`), but skip the `viewModelScope.launch(Dispatchers.Main)` UI update block when frozen:

```kotlin
// In processBatch(), change the final block from:
if (!_isFrozen.value) {
    viewModelScope.launch(Dispatchers.Main) { /* update all StateFlows */ }
}

// To: always process, conditionally publish:
val snapshot = /* build updated state */
if (!_isFrozen.value) {
    viewModelScope.launch(Dispatchers.Main) {
        _knownMessages.value = snapshot.known
        _unknownIds.value = snapshot.unknown
        _liveFrames.value = liveBuffer.toList()
        updateConnectionFrameRate()
    }
}
// triggeredInWindow is still computed above regardless of freeze
```

Recording (`activeSession?.appendFrame()`) is already outside the freeze gate — keep it that way.

---

## Item 9 — Session notes at record time

**Priority: Low**

### Problem
`SessionMeta.notes` exists but is never populated. There is no affordance to add notes when starting or stopping a recording.

### Required changes

**`LiveScreen.kt` — `VehiclePickerDialog`**

Add an optional notes field to the vehicle picker dialog. Pass the notes string into `vm.startRecording(vehicleId, notes)`:

```kotlin
var sessionNotes by remember { mutableStateOf("") }

OutlinedTextField(
    value = sessionNotes,
    onValueChange = { sessionNotes = it },
    label = { Text("Session notes (optional)") },
    modifier = Modifier.fillMaxWidth(),
    singleLine = true,
)

// In the onPick callback:
onPick(v.id, sessionNotes)
```

**`CanBusViewModel.kt`**

Update `startRecording(vehicleId: String, notes: String = "")` to pass notes into the `SessionMeta` constructor.

**`LogBrowserScreen.kt`** / **`VehicleDetailScreen.kt`**

Display `meta.notes` in the session list rows and at the top of the log browser if non-empty.

---

## Item 10 — DLC guard in signal decoder

**Priority: Medium (correctness)**

### Problem
`SignalDecoder.extractRaw()` silently returns 0 for bit positions past `frameData.size`. No error is surfaced to the user when a defined signal references bits outside the actual frame. This makes misconfigured signals look like they're outputting a valid (but wrong) value.

### Required changes

**`SignalDecoder.kt`**

Add an explicit bounds check that returns `null` (or throws) when the signal's bit range extends past the data:

```kotlin
fun decodeOrNull(signal: DbcSignal, frameData: ByteArray): Double? {
    val maxBit = when (signal.byteOrder) {
        ByteOrder.INTEL -> signal.startBit + signal.length - 1
        ByteOrder.MOTOROLA -> {
            // Highest byte index touched by the Motorola traversal
            var byteIdx = signal.startBit / 8
            var bitIdx = signal.startBit % 8
            var maxByte = byteIdx
            repeat(signal.length) {
                maxByte = maxOf(maxByte, byteIdx)
                if (bitIdx == 0) { byteIdx++; bitIdx = 7 } else bitIdx--
            }
            maxByte * 8 + 7
        }
    }
    if (maxBit / 8 >= frameData.size) return null
    return decode(signal, frameData)
}
```

In `CanBusViewModel.processBatch()` and `LogPlayerViewModel.loadSession()`, replace `SignalDecoder.decode()` with `decodeOrNull()` and exclude null-decoded signals from the displayed map. Optionally flag the message as having an out-of-range signal so the user knows to fix the definition.

**`SignalEditorScreen.kt`**

In the live preview block, surface a warning if the signal extends past the observed frame DLC:

```kotlin
if (liveData != null && (startBit + length) > liveData.size * 8) {
    Text(
        "⚠ Signal extends past frame DLC (${liveData.size} bytes)",
        color = MaterialTheme.colorScheme.error,
        fontSize = 12.sp,
    )
}
```

---

## CHANGELOG reminder

Per repo convention, add an entry to `phone_companion/CHANGELOG.md` for each item implemented, dated and grouped under `### Added`, `### Changed`, or `### Fixed` as appropriate.

---

*Generated from code review of commit tree as of 2026-06-09.*
