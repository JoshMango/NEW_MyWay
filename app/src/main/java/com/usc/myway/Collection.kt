// collections/album folder (item)
package com.usc.myway

class Collection(var name: String, var icon: String) {
    val locationKeys: MutableList<String> = mutableListOf() // list of "lat,lng" keys
}
