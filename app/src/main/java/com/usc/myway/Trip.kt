// Group Trips: a live, Discord-voice-style session tied to a group. Real-time location sharing +
// shared pins. Single-trip-at-a-time is structural: ONE participant doc per user, keyed by uid, so
// joining another group's trip overwrites it (auto-leaving the previous one).
//   trip_participants/{uid}       { uid, gid, tag, photo, lat, lng, updatedAt }
//   groups/{gid}/trip_pins/{id}   { from, fromTag, lat, lng, name, note, createdAt }
// Callback-based (no coroutines-play-services dependency).
package com.usc.myway

import com.google.firebase.Timestamp
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.ListenerRegistration
import java.util.Date

object Trip {

    data class Member(val uid: String, val tag: String, val photo: String, val lat: Double?, val lng: Double?)
    data class TripPin(val id: String, val from: String, val fromTag: String, val fromPhoto: String, val lat: Double, val lng: Double, val name: String, val note: String)
    /** A shared destination for the whole trip. [done] = uids who've arrived or ended it; cleared once all live members are done.
     *  [planItemId] non-empty means it's driven by the Plan (auto-navigate, advances on "finished" not arrival). */
    data class TripDest(val id: String, val lat: Double, val lng: Double, val name: String, val by: String, val byTag: String, val done: List<String>, val planItemId: String = "")

    // ── Plan (a shared, ordered queue of objectives that auto-drives the trip direction) ──
    data class PlanItem(val id: String, val name: String, val lat: Double, val lng: Double, val finished: Boolean)
    data class TripPlan(val name: String, val paused: Boolean, val archived: Boolean, val items: List<PlanItem>) {
        /** First not-finished item, unless paused/archived — this is what drives the group direction. */
        val activeItem: PlanItem? get() = if (paused || archived) null else items.firstOrNull { !it.finished }
        val complete: Boolean get() = items.isNotEmpty() && items.all { it.finished }
    }
    data class OfferPin(val lat: Double, val lng: Double, val name: String, val note: String)
    /** An offer to add a whole collection's pins to the trip (trip-only "share collection" feature). */
    data class TripOffer(val id: String, val from: String, val fromTag: String, val fromPhoto: String, val name: String, val pins: List<OfferPin>)

    // A member is considered gone if their heartbeat is older than this (crash/no-cleanup guard).
    // The foreground service heartbeats every ~20s, so 60s is a safe 3× margin.
    private const val STALE_MS = 60_000L
    private const val TTL_MS = 90_000L

    private val db get() = FirebaseFirestore.getInstance()
    private fun meRef(uid: String) = db.collection("trip_participants").document(uid)

    private fun expiry() = Timestamp(Date(System.currentTimeMillis() + TTL_MS))

    /** Fresh if we've heard from them recently. A just-joined doc (no server timestamp yet) counts as fresh. */
    private fun fresh(d: DocumentSnapshot): Boolean {
        val u = d.getTimestamp("updatedAt") ?: return true
        return System.currentTimeMillis() - u.toDate().time < STALE_MS
    }

    /** Join [gid]'s trip. Overwrites any existing participation → you leave your previous trip. */
    fun join(uid: String, gid: String, tag: String, photo: String, lat: Double, lng: Double, onDone: (String?) -> Unit) {
        val data = hashMapOf<String, Any>(
            "uid" to uid, "gid" to gid, "tag" to tag, "photo" to photo,
            "updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expiry(),
        )
        if (lat != 0.0 || lng != 0.0) { data["lat"] = lat; data["lng"] = lng }
        meRef(uid).set(data).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not join") }
    }

    fun leave(uid: String, onDone: (String?) -> Unit) {
        meRef(uid).delete().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    /**
     * Fire-and-forget live-location update + heartbeat. update() (not set) so a left/deleted doc
     * stays gone. Refreshes updatedAt (liveness) and expireAt (Firestore TTL cleanup) every write.
     */
    fun updateLocation(uid: String, lat: Double, lng: Double) {
        meRef(uid).update(
            mapOf("lat" to lat, "lng" to lng, "updatedAt" to FieldValue.serverTimestamp(), "expireAt" to expiry())
        )
    }

    /** My current trip's group id, or null when I'm not in one. */
    fun listenMyTrip(uid: String, onChange: (String?) -> Unit): ListenerRegistration =
        meRef(uid).addSnapshotListener { d, _ -> onChange(if (d != null && d.exists()) d.getString("gid") else null) }

    /** One-shot: my current trip's group id (or null). */
    fun currentTrip(uid: String, onResult: (String?) -> Unit) {
        meRef(uid).get().addOnSuccessListener { onResult(if (it.exists()) it.getString("gid") else null) }
            .addOnFailureListener { onResult(null) }
    }

    /** Everyone currently live in [gid]. */
    fun listenMembers(gid: String, onChange: (List<Member>) -> Unit): ListenerRegistration =
        db.collection("trip_participants").whereEqualTo("gid", gid)
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { d ->
                    if (!fresh(d)) return@mapNotNull null   // hide ghosts (crashed without leaving)
                    val uid = d.getString("uid") ?: return@mapNotNull null
                    Member(uid, d.getString("tag") ?: "", d.getString("photo") ?: "",
                        d.getDouble("lat"), d.getDouble("lng"))
                })
            }

    /** Start an ongoing session on the group (marks it LIVE / joinable). Join separately to go live. */
    fun startSession(gid: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).update("tripActive", true)
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not start trip") }
    }

    /**
     * End the session (any group member may). Clears the session: removes all participants (everyone
     * goes offline) and all shared pins, then marks the group not-in-trip. This is the ONLY thing that
     * clears session pins — leaving does not.
     */
    fun endSession(gid: String, onDone: (String?) -> Unit) {
        val groupRef = db.collection("groups").document(gid)
        val pinsCol = groupRef.collection("trip_pins")
        val offersCol = groupRef.collection("trip_offers")
        val partsQ = db.collection("trip_participants").whereEqualTo("gid", gid)
        pinsCol.get().addOnSuccessListener { pinSnap ->
            offersCol.get().addOnSuccessListener { offerSnap ->
                partsQ.get().addOnSuccessListener { partSnap ->
                    val batch = db.batch()
                    pinSnap.documents.forEach { batch.delete(it.reference) }
                    offerSnap.documents.forEach { batch.delete(it.reference) }
                    partSnap.documents.forEach { batch.delete(it.reference) }
                    batch.delete(planRef(gid))                               // clear the plan
                    batch.update(groupRef, "tripActive", false, "tripDest", FieldValue.delete())
                    batch.commit().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Failed") }
                }.addOnFailureListener { onDone(it.message ?: "Failed") }
            }.addOnFailureListener { onDone(it.message ?: "Failed") }
        }.addOnFailureListener { onDone(it.message ?: "Failed") }
    }

    // ── Shared trip direction ───────────────────────────────────────────────────────
    // Stored inline on the group doc as `tripDest`, so joining/leaving doesn't touch it.

    /** Set (or overwrite) the trip's shared destination — everyone live gets routed to it. */
    fun setTripDest(gid: String, lat: Double, lng: Double, name: String, byUid: String, byTag: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).update(
            "tripDest", mapOf(
                "id" to System.currentTimeMillis().toString(),  // ponytail: ms id; only used to tell a fresh dest from the old one
                "lat" to lat, "lng" to lng, "name" to name, "by" to byUid, "byTag" to byTag, "done" to emptyList<String>(),
            )
        ).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not set trip direction") }
    }

    fun listenTripDest(gid: String, onChange: (TripDest?) -> Unit): ListenerRegistration =
        db.collection("groups").document(gid).addSnapshotListener { d, _ ->
            @Suppress("UNCHECKED_CAST")
            val m = (d?.get("tripDest") as? Map<String, Any>)
            val lat = (m?.get("lat") as? Number)?.toDouble()
            val lng = (m?.get("lng") as? Number)?.toDouble()
            onChange(if (m == null || lat == null || lng == null) null else TripDest(
                id = m["id"]?.toString() ?: "", lat = lat, lng = lng,
                name = m["name"]?.toString() ?: "", by = m["by"]?.toString() ?: "", byTag = m["byTag"]?.toString() ?: "",
                done = (m["done"] as? List<*>)?.map { it.toString() } ?: emptyList(),
                planItemId = m["planItemId"]?.toString() ?: "",
            ))
        }

    /**
     * Mark that I've finished the current trip direction (arrived or pressed end). Ends it only for me;
     * once every live member is done, it clears for everyone. Overwriting with a new dest resets [done].
     */
    fun endTripDestForMe(gid: String, uid: String) {
        val groupRef = db.collection("groups").document(gid)
        groupRef.update("tripDest.done", FieldValue.arrayUnion(uid)).addOnSuccessListener {
            db.collection("trip_participants").whereEqualTo("gid", gid).get().addOnSuccessListener { parts ->
                groupRef.get().addOnSuccessListener { g ->
                    @Suppress("UNCHECKED_CAST")
                    val dest = g.get("tripDest") as? Map<String, Any> ?: return@addOnSuccessListener
                    val done = (dest["done"] as? List<*>)?.map { it.toString() }?.toSet() ?: emptySet()
                    val live = parts.documents.filter { fresh(it) }.mapNotNull { it.getString("uid") }
                    if (live.isNotEmpty() && live.all { it in done }) groupRef.update("tripDest", FieldValue.delete())
                }
            }
        }
    }

    // ── Shared collection offers (trip-only) ───────────────────────────────────────
    private const val OFFER_TTL_MS = 15 * 60_000L

    /** Broadcast a collection to the trip; members get a modal to add its pins. Attribution stays with [fromUid]. */
    fun shareCollection(gid: String, fromUid: String, fromTag: String, fromPhoto: String, name: String,
                        pins: List<OfferPin>, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).collection("trip_offers").document().set(
            mapOf(
                "from" to fromUid, "fromTag" to fromTag, "fromPhoto" to fromPhoto, "name" to name,
                "pins" to pins.map { mapOf("lat" to it.lat, "lng" to it.lng, "name" to it.name, "note" to it.note) },
                "createdAt" to FieldValue.serverTimestamp(),
                "expireAt" to Timestamp(Date(System.currentTimeMillis() + OFFER_TTL_MS)),
            )
        ).addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Couldn't share collection") }
    }

    fun listenOffers(gid: String, onChange: (List<TripOffer>) -> Unit): ListenerRegistration =
        db.collection("groups").document(gid).collection("trip_offers")
            .addSnapshotListener { snap, _ ->
                if (snap == null) return@addSnapshotListener
                onChange(snap.documents.mapNotNull { d ->
                    val exp = d.getTimestamp("expireAt")?.toDate()?.time ?: 0L
                    if (exp < System.currentTimeMillis()) return@mapNotNull null   // stale offer
                    @Suppress("UNCHECKED_CAST")
                    val raw = (d.get("pins") as? List<Map<String, Any>>) ?: emptyList()
                    val pins = raw.mapNotNull { m ->
                        val lat = (m["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
                        val lng = (m["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
                        OfferPin(lat, lng, m["name"]?.toString() ?: "", m["note"]?.toString() ?: "")
                    }
                    TripOffer(d.id, d.getString("from") ?: "", d.getString("fromTag") ?: "",
                        d.getString("fromPhoto") ?: "", d.getString("name") ?: "Collection", pins)
                })
            }

    /** Batch-add accepted offer pins as trip pins (attributed to the original sharer). */
    fun addPins(gid: String, fromUid: String, fromTag: String, fromPhoto: String, pins: List<OfferPin>, onDone: (String?) -> Unit) {
        if (pins.isEmpty()) { onDone(null); return }
        val col = db.collection("groups").document(gid).collection("trip_pins")
        val batch = db.batch()
        pins.forEach { p ->
            batch.set(col.document(), mapOf(
                "from" to fromUid, "fromTag" to fromTag, "fromPhoto" to fromPhoto,
                "lat" to p.lat, "lng" to p.lng, "name" to p.name, "note" to p.note,
                "createdAt" to FieldValue.serverTimestamp(),
            ))
        }
        batch.commit().addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Couldn't add") }
    }

    // ── Plan ────────────────────────────────────────────────────────────────────────
    // groups/{gid}/trip_plan/current — a single ordered queue. Every edit runs in a transaction that
    // ALSO writes group.tripDest to the active item, so the group direction stays in lock-step (no races).
    private fun planRef(gid: String) = db.collection("groups").document(gid).collection("trip_plan").document("current")

    private class PlanState(var name: String, var paused: Boolean, val items: MutableList<PlanItem>)

    private fun parseItems(d: DocumentSnapshot): MutableList<PlanItem> {
        @Suppress("UNCHECKED_CAST")
        val raw = (d.get("items") as? List<Map<String, Any>>) ?: emptyList()
        return raw.mapNotNull { m ->
            val lat = (m["lat"] as? Number)?.toDouble() ?: return@mapNotNull null
            val lng = (m["lng"] as? Number)?.toDouble() ?: return@mapNotNull null
            PlanItem(m["id"]?.toString() ?: "", m["name"]?.toString() ?: "", lat, lng, m["finished"] as? Boolean ?: false)
        }.toMutableList()
    }

    fun listenPlan(gid: String, onChange: (TripPlan?) -> Unit): ListenerRegistration =
        planRef(gid).addSnapshotListener { d, _ ->
            onChange(if (d == null || !d.exists()) null else
                TripPlan(d.getString("name") ?: "Plan", d.getBoolean("paused") ?: false,
                    d.getBoolean("archived") ?: false, parseItems(d)))
        }

    /** Core: read the plan, apply [edit] (return null to abort), write it back, and re-point tripDest at the active item. */
    private fun edit(gid: String, actorUid: String, actorTag: String, onDone: (String?) -> Unit,
                     edit: (PlanState?) -> PlanState?) {
        val pRef = planRef(gid); val gRef = db.collection("groups").document(gid)
        db.runTransaction { txn ->
            val snap = txn.get(pRef)
            val cur = if (snap.exists()) PlanState(snap.getString("name") ?: "Plan", snap.getBoolean("paused") ?: false, parseItems(snap)) else null
            val next = edit(cur) ?: return@runTransaction null
            val archived = next.items.isNotEmpty() && next.items.all { it.finished }
            txn.set(pRef, mapOf(
                "name" to next.name, "paused" to next.paused, "archived" to archived,
                "items" to next.items.map { mapOf("id" to it.id, "name" to it.name, "lat" to it.lat, "lng" to it.lng, "finished" to it.finished) },
            ))
            // Steer the group direction — unless paused (then freeze the current direction).
            if (!next.paused) {
                val active = if (archived) null else next.items.firstOrNull { !it.finished }
                if (active == null) txn.update(gRef, "tripDest", FieldValue.delete())
                else txn.update(gRef, "tripDest", mapOf(
                    "id" to active.id, "lat" to active.lat, "lng" to active.lng, "name" to active.name,
                    "by" to actorUid, "byTag" to actorTag, "done" to emptyList<String>(), "planItemId" to active.id))
            }
            null
        }.addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Plan update failed") }
    }

    private fun newId() = java.util.UUID.randomUUID().toString().take(10)

    fun createPlan(gid: String, name: String, actorUid: String, actorTag: String, onDone: (String?) -> Unit) =
        edit(gid, actorUid, actorTag, onDone) { PlanState(name.ifBlank { "Trip plan" }, false, mutableListOf()) }

    fun addPlanItem(gid: String, name: String, lat: Double, lng: Double, actorUid: String, actorTag: String, onDone: (String?) -> Unit) =
        edit(gid, actorUid, actorTag, onDone) { cur ->
            cur ?: return@edit null
            cur.items.add(PlanItem(newId(), name, lat, lng, false)); cur
        }

    /** A manual group direction while a plan is active → insert at the front of the uncrushed items (and resume). */
    fun prependPlanItem(gid: String, name: String, lat: Double, lng: Double, actorUid: String, actorTag: String, onDone: (String?) -> Unit) =
        edit(gid, actorUid, actorTag, onDone) { cur ->
            cur ?: return@edit null
            val idx = cur.items.indexOfFirst { !it.finished }.let { if (it < 0) cur.items.size else it }
            cur.items.add(idx, PlanItem(newId(), name, lat, lng, false)); cur.paused = false; cur
        }

    fun setItemFinished(gid: String, itemId: String, finished: Boolean, actorUid: String, actorTag: String, onDone: (String?) -> Unit) =
        edit(gid, actorUid, actorTag, onDone) { cur ->
            cur ?: return@edit null
            val i = cur.items.indexOfFirst { it.id == itemId }
            if (i >= 0) cur.items[i] = cur.items[i].copy(finished = finished)
            cur
        }

    fun setPlanPaused(gid: String, paused: Boolean, actorUid: String, actorTag: String, onDone: (String?) -> Unit) =
        edit(gid, actorUid, actorTag, onDone) { cur -> cur ?: return@edit null; cur.paused = paused; cur }

    // ── Shared pins ───────────────────────────────────────────────────────────────
    fun sharePin(gid: String, fromUid: String, fromTag: String, fromPhoto: String, lat: Double, lng: Double, name: String, note: String) {
        db.collection("groups").document(gid).collection("trip_pins").document().set(
            mapOf("from" to fromUid, "fromTag" to fromTag, "fromPhoto" to fromPhoto, "lat" to lat, "lng" to lng,
                "name" to name, "note" to note, "createdAt" to FieldValue.serverTimestamp())
        )
    }

    fun listenPins(gid: String, onChange: (List<TripPin>) -> Unit): ListenerRegistration =
        db.collection("groups").document(gid).collection("trip_pins")
            .addSnapshotListener { snap, _ ->
                if (snap != null) onChange(snap.documents.mapNotNull { d ->
                    val lat = d.getDouble("lat") ?: return@mapNotNull null
                    val lng = d.getDouble("lng") ?: return@mapNotNull null
                    TripPin(d.id, d.getString("from") ?: "", d.getString("fromTag") ?: "", d.getString("fromPhoto") ?: "",
                        lat, lng, d.getString("name") ?: "", d.getString("note") ?: "")
                })
            }

    /** Any member may edit a session pin's name/note (creator attribution is preserved). */
    fun updatePin(gid: String, pinId: String, name: String, note: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).collection("trip_pins").document(pinId)
            .update("name", name, "note", note)
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not edit") }
    }

    /** Any member may delete a session pin. */
    fun deletePin(gid: String, pinId: String, onDone: (String?) -> Unit) {
        db.collection("groups").document(gid).collection("trip_pins").document(pinId).delete()
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not delete") }
    }
}
