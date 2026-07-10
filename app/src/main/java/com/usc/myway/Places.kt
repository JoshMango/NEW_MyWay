// Personal map data in Firestore (private per user):
//   users/{uid}/places/{key}       { lat, lng, name, note, placeId }   key = App.locationKey(lat,lng)
//   users/{uid}/collections/{cid}  { name, icon, keys[] }
// Callback-based like the rest of the data layer. Firestore's offline cache covers the "works
// without a network" role SharedPreferences used to play.
package com.usc.myway

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.SetOptions

object Places {

    private val db get() = FirebaseFirestore.getInstance()

    data class Doc(
        val key: String, val lat: Double, val lng: Double,
        val name: String, val note: String, val placeId: String,
    )

    data class Coll(val id: String, val name: String, val icon: String, val keys: List<String>)

    private fun places(uid: String) = db.collection("users").document(uid).collection("places")
    private fun colls(uid: String) = db.collection("users").document(uid).collection("collections")

    fun listenPlaces(uid: String, onChange: (List<Doc>) -> Unit): ListenerRegistration =
        places(uid).addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            onChange(snap.documents.mapNotNull { d ->
                val lat = d.getDouble("lat") ?: return@mapNotNull null   // skip half-written docs
                val lng = d.getDouble("lng") ?: return@mapNotNull null
                Doc(d.id, lat, lng, d.getString("name") ?: "", d.getString("note") ?: "", d.getString("placeId") ?: "")
            })
        }

    fun listenCollections(uid: String, onChange: (List<Coll>) -> Unit): ListenerRegistration =
        colls(uid).addSnapshotListener { snap, _ ->
            if (snap == null) return@addSnapshotListener
            onChange(snap.documents.map { d ->
                Coll(d.id, d.getString("name") ?: "Collection", d.getString("icon") ?: "📁",
                    (d.get("keys") as? List<*>)?.filterIsInstance<String>() ?: emptyList())
            })
        }

    fun savePlace(uid: String, key: String, lat: Double, lng: Double) {
        places(uid).document(key).set(mapOf("lat" to lat, "lng" to lng), SetOptions.merge())
    }

    /** Merge one attribute onto a place; an empty value clears the field. */
    fun setPlaceField(uid: String, key: String, field: String, value: String) {
        places(uid).document(key)
            .set(mapOf(field to if (value.isEmpty()) FieldValue.delete() else value), SetOptions.merge())
    }

    fun deletePlace(uid: String, key: String) { places(uid).document(key).delete() }

    fun saveCollection(uid: String, c: Collection) {
        colls(uid).document(c.id).set(mapOf("name" to c.name, "icon" to c.icon, "keys" to c.locationKeys.toList()))
    }

    fun deleteCollection(uid: String, id: String) { colls(uid).document(id).delete() }

    /** One-time lift of the old SharedPreferences data into Firestore. */
    // ponytail: single batch (500-write cap). Personal maps don't get near it; chunk if that changes.
    fun uploadAll(uid: String, docs: List<Doc>, collections: List<Collection>) {
        if (docs.isEmpty() && collections.isEmpty()) return
        val batch = db.batch()
        for (d in docs) batch.set(places(uid).document(d.key), mapOf(
            "lat" to d.lat, "lng" to d.lng, "name" to d.name, "note" to d.note, "placeId" to d.placeId))
        for (c in collections) batch.set(colls(uid).document(c.id), mapOf(
            "name" to c.name, "icon" to c.icon, "keys" to c.locationKeys.toList()))
        batch.commit()
    }

    /** Wipe every place + collection for this user. */
    fun deleteAll(uid: String) {
        for (ref in listOf(places(uid), colls(uid))) {
            ref.get().addOnSuccessListener { snap ->
                if (snap.isEmpty) return@addOnSuccessListener
                val batch = db.batch()
                snap.documents.forEach { batch.delete(it.reference) }
                batch.commit()
            }
        }
    }
}
