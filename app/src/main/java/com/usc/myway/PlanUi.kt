// Trip Plan sheet: a shared, ordered queue of objectives that auto-drives the group direction.
// Finished objectives are crossed out (undoable); the top not-finished one is "Next". Pause/resume,
// and add objectives by searching a place. Completing all objectives archives the plan.
package com.usc.myway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.tasks.Tasks
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.AutocompleteSessionToken
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

private val Teal = Color(0xFF00C99D)
private val TealDeep = Color(0xFF00A77D)
private val Danger = Color(0xFFEF4444)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlanSheet(
    plan: Trip.TripPlan?,
    placesClient: PlacesClient,
    onCreate: (String) -> Unit,
    onAddItem: (name: String, lat: Double, lng: Double) -> Unit,
    onToggle: (itemId: String, finished: Boolean) -> Unit,
    onPause: (Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    val onSurface = MaterialTheme.colorScheme.onSurface
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp).padding(bottom = 28.dp)) {
            if (plan == null || plan.archived) {
                Text("📋  Trip Plan", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onSurface)
                if (plan?.archived == true) Text("“${plan.name}” complete — ${plan.items.size} objectives done.",
                    fontSize = 13.sp, color = onSurface.copy(alpha = 0.6f), modifier = Modifier.padding(top = 4.dp, bottom = 12.dp))
                else Spacer(Modifier.height(12.dp))
                var name by remember { mutableStateOf("") }
                OutlinedTextField(name, { name = it }, Modifier.fillMaxWidth(), label = { Text("Plan name") },
                    singleLine = true, shape = RoundedCornerShape(12.dp))
                Button(onClick = { onCreate(name.trim().ifEmpty { "Trip plan" }) },
                    modifier = Modifier.fillMaxWidth().padding(top = 12.dp), shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
                    Text(if (plan?.archived == true) "Start a new plan" else "Create plan", fontWeight = FontWeight.Bold)
                }
                return@Column
            }

            // Header + pause/resume
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(plan.name, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = onSurface,
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    val doneN = plan.items.count { it.finished }
                    Text("${doneN}/${plan.items.size} done" + if (plan.paused) " · paused" else "",
                        fontSize = 12.sp, color = if (plan.paused) Danger else onSurface.copy(alpha = 0.6f))
                }
                OutlinedButton(onClick = { onPause(!plan.paused) }, shape = RoundedCornerShape(20.dp)) {
                    Text(if (plan.paused) "▶ Resume" else "⏸ Pause")
                }
            }

            Spacer(Modifier.height(10.dp))
            val activeId = plan.activeItem?.id
            LazyColumn(Modifier.fillMaxWidth().heightIn(max = 300.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                items(plan.items, key = { it.id }) { item ->
                    val isNext = item.id == activeId
                    Row(
                        Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp))
                            .background(if (isNext) Teal.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Column(Modifier.weight(1f)) {
                            Text(item.name.ifEmpty { "Objective" }, fontSize = 15.sp,
                                fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
                                color = onSurface.copy(alpha = if (item.finished) 0.5f else 1f),
                                textDecoration = if (item.finished) TextDecoration.LineThrough else null,
                                maxLines = 1, overflow = TextOverflow.Ellipsis)
                            if (isNext && !plan.paused) Text("▶ Next stop", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealDeep)
                        }
                        if (item.finished) TextButton(onClick = { onToggle(item.id, false) }) { Text("Undo", color = TealDeep) }
                        else TextButton(onClick = { onToggle(item.id, true) }) { Text("Done", color = Teal, fontWeight = FontWeight.Bold) }
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("ADD OBJECTIVE", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = TealDeep)
            AddObjectiveField(placesClient) { name, lat, lng -> onAddItem(name, lat, lng) }
        }
    }
}

/** Search a place → pick → name the activity → add. Supports multiple activities at the same place. */
@Composable
private fun AddObjectiveField(placesClient: PlacesClient, onAdd: (name: String, lat: Double, lng: Double) -> Unit) {
    val onSurface = MaterialTheme.colorScheme.onSurface
    var query by remember { mutableStateOf("") }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var token by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }
    var picked by remember { mutableStateOf<Triple<String, Double, Double>?>(null) } // name, lat, lng
    var justPicked by remember { mutableStateOf(false) }

    LaunchedEffect(query) {
        if (justPicked) { justPicked = false; return@LaunchedEffect }
        val q = query.trim()
        if (q.length < 2) { predictions = emptyList(); return@LaunchedEffect }
        delay(300)
        predictions = try {
            withContext(Dispatchers.IO) {
                Tasks.await(placesClient.findAutocompletePredictions(
                    FindAutocompletePredictionsRequest.builder().setSessionToken(token).setQuery(q).build()))
            }.autocompletePredictions
        } catch (_: Exception) { emptyList() }
    }

    OutlinedTextField(
        value = query, onValueChange = { query = it; if (picked != null) picked = null },
        placeholder = { Text("Search a place") }, leadingIcon = { Text("🔍") },
        singleLine = true, shape = RoundedCornerShape(12.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
    )
    predictions.forEach { pred ->
        Column(Modifier.fillMaxWidth().clickable {
            justPicked = true
            val nm = pred.getPrimaryText(null).toString()
            query = nm; predictions = emptyList()
            placesClient.fetchPlace(FetchPlaceRequest.builder(pred.placeId, listOf(Place.Field.LAT_LNG)).setSessionToken(token).build())
                .addOnSuccessListener { resp -> resp.place.latLng?.let { picked = Triple(nm, it.latitude, it.longitude) } }
            token = AutocompleteSessionToken.newInstance()
        }.padding(vertical = 8.dp, horizontal = 4.dp)) {
            Text(pred.getPrimaryText(null).toString(), fontSize = 14.sp, fontWeight = FontWeight.Medium, color = onSurface)
            Text(pred.getSecondaryText(null).toString(), fontSize = 12.sp, color = onSurface.copy(alpha = 0.6f),
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    picked?.let { (name, lat, lng) ->
        var label by remember(name) { mutableStateOf(name) }
        Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(label, { label = it }, Modifier.weight(1f), label = { Text("Name / activity") },
                singleLine = true, shape = RoundedCornerShape(12.dp))
            Spacer(Modifier.width(8.dp))
            Button(onClick = { onAdd(label.trim().ifEmpty { name }, lat, lng); query = ""; picked = null },
                shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.buttonColors(containerColor = Teal)) {
                Text("Add", fontWeight = FontWeight.Bold)
            }
        }
    }
}
