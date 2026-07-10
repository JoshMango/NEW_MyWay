// collections/album folder (item). `id` is the Firestore doc id under users/{uid}/collections.
package com.usc.myway

import java.util.UUID

class Collection(var name: String, var icon: String, val id: String = UUID.randomUUID().toString()) {
    val locationKeys: MutableList<String> = mutableListOf() // list of "lat,lng" keys
}
