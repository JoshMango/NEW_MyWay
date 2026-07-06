// Compose port of MainActivity's location search + Places autocomplete overlay.
// Hosted in a ComposeView; on selection it hands the LatLng back so the activity recenters the map.
package com.usc.myway

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
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

/** Java-friendly callback: fired with the chosen place's coordinates. */
fun interface PlacePickedListener {
    fun onPicked(latLng: LatLng)
}

@Composable
internal fun SearchBar(placesClient: PlacesClient, onPicked: PlacePickedListener) {
    var query by remember { mutableStateOf("") }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }
    var token by remember { mutableStateOf(AutocompleteSessionToken.newInstance()) }
    var justPicked by remember { mutableStateOf(false) }

    // Debounced autocomplete: one request ~300ms after typing stops, grouped by a session token
    // so the whole search + the final fetchPlace bill as one cheaper Places session.
    LaunchedEffect(query) {
        if (justPicked) { justPicked = false; return@LaunchedEffect }
        val q = query.trim()
        if (q.length < 2) { predictions = emptyList(); return@LaunchedEffect }
        delay(300)
        predictions = try {
            withContext(Dispatchers.IO) {
                Tasks.await(
                    placesClient.findAutocompletePredictions(
                        FindAutocompletePredictionsRequest.builder().setSessionToken(token).setQuery(q).build()
                    )
                )
            }.autocompletePredictions
        } catch (e: Exception) { emptyList() }
    }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            placeholder = { Text("Search location…") },
            leadingIcon = { Text("🔍", fontSize = 16.sp) },
            trailingIcon = {
                if (query.isNotEmpty()) Text("✕", modifier = Modifier
                    .clickable { query = ""; predictions = emptyList() }.padding(8.dp))
            },
            singleLine = true,
            shape = RoundedCornerShape(14.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Teal, focusedLabelColor = Teal,
                unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                focusedContainerColor = MaterialTheme.colorScheme.surface,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (predictions.isNotEmpty()) {
            Surface(
                shape = RoundedCornerShape(14.dp),
                color = MaterialTheme.colorScheme.surface,
                shadowElevation = 6.dp,
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp),
            ) {
                LazyColumn(Modifier.heightIn(max = 240.dp)) {
                    items(predictions) { pred ->
                        Column(Modifier.fillMaxWidth().clickable {
                            justPicked = true
                            query = pred.getPrimaryText(null).toString()
                            predictions = emptyList()
                            placesClient.fetchPlace(
                                FetchPlaceRequest.builder(pred.placeId, listOf(Place.Field.LAT_LNG)).setSessionToken(token).build()
                            ).addOnSuccessListener { resp -> resp.place.latLng?.let(onPicked::onPicked) }
                            token = AutocompleteSessionToken.newInstance() // end session; fresh token next search
                        }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                            Text(pred.getPrimaryText(null).toString(), fontWeight = FontWeight.SemiBold,
                                fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurface)
                            Text(pred.getSecondaryText(null).toString(), fontSize = 12.sp, maxLines = 1,
                                overflow = TextOverflow.Ellipsis, color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f))
                        }
                        HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                    }
                }
            }
        }
    }
}
