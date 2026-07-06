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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.google.android.gms.maps.model.LatLng
import com.google.android.libraries.places.api.model.AutocompletePrediction
import com.google.android.libraries.places.api.model.Place
import com.google.android.libraries.places.api.net.FetchPlaceRequest
import com.google.android.libraries.places.api.net.FindAutocompletePredictionsRequest
import com.google.android.libraries.places.api.net.PlacesClient
import com.usc.myway.ui.theme.MyWayTheme

private val Teal = Color(0xFF00C99D)

/** Java-friendly callback: fired with the chosen place's coordinates. */
fun interface PlacePickedListener {
    fun onPicked(latLng: LatLng)
}

object SearchHost {
    @JvmStatic
    fun install(view: ComposeView, placesClient: PlacesClient, dark: Boolean, onPicked: PlacePickedListener) {
        view.setContent { MyWayTheme(darkTheme = dark) { SearchBar(placesClient, onPicked) } }
    }
}

@Composable
private fun SearchBar(placesClient: PlacesClient, onPicked: PlacePickedListener) {
    var query by remember { mutableStateOf("") }
    var predictions by remember { mutableStateOf<List<AutocompletePrediction>>(emptyList()) }

    Column(Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = query,
            onValueChange = { q ->
                query = q
                if (q.trim().length >= 2) {
                    placesClient.findAutocompletePredictions(
                        FindAutocompletePredictionsRequest.builder().setQuery(q.trim()).build()
                    ).addOnSuccessListener { predictions = it.autocompletePredictions }
                        .addOnFailureListener { predictions = emptyList() }
                } else predictions = emptyList()
            },
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
                            query = pred.getPrimaryText(null).toString()
                            predictions = emptyList()
                            placesClient.fetchPlace(
                                FetchPlaceRequest.newInstance(pred.placeId, listOf(Place.Field.LAT_LNG))
                            ).addOnSuccessListener { resp -> resp.place.latLng?.let(onPicked::onPicked) }
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
