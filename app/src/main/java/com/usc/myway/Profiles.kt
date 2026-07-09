// First Firestore usage: user @tags for the social layer. Two collections —
//   users/{uid}       { tag, tagLower, createdAt }   the profile
//   usernames/{lower} { uid }                         uniqueness index / handle → uid lookup
// Uniqueness is enforced atomically in a transaction so two people can't grab the same tag.
// Callback-based (no coroutines-play-services dependency needed).
package com.usc.myway

import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.SetOptions

object Profiles {

    private val db get() = FirebaseFirestore.getInstance()
    private val FORMAT = Regex("^[a-z0-9_]{3,20}$")

    /** Strip a leading @ and lowercase — tags are matched case-insensitively. */
    fun normalize(raw: String) = raw.trim().removePrefix("@").lowercase()

    fun formatError(normalized: String): String? = when {
        normalized.length < 3 -> "At least 3 characters"
        normalized.length > 20 -> "Keep it under 20 characters"
        !FORMAT.matches(normalized) -> "Letters, numbers and _ only"
        else -> null
    }

    /** The signed-in user's existing tag, or null if they haven't onboarded (or on network error). */
    fun fetchTag(uid: String, onResult: (String?) -> Unit) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener { onResult(it.getString("tag")?.takeIf { t -> t.isNotBlank() }) }
            .addOnFailureListener { onResult(null) }
    }

    data class Profile(val tag: String, val firstName: String, val lastName: String, val photo: String)

    /** Full profile for the settings screen. null on network error. */
    fun fetchProfile(uid: String, onResult: (Profile?) -> Unit) {
        db.collection("users").document(uid).get()
            .addOnSuccessListener {
                onResult(Profile(
                    tag = it.getString("tag") ?: "",
                    firstName = it.getString("firstName") ?: "",
                    lastName = it.getString("lastName") ?: "",
                    photo = it.getString("photo") ?: "",
                ))
            }
            .addOnFailureListener { onResult(null) }
    }

    fun updateName(uid: String, first: String, last: String, onDone: (String?) -> Unit) {
        db.collection("users").document(uid)
            .set(mapOf("firstName" to first.trim(), "lastName" to last.trim()), SetOptions.merge())
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not save") }
    }

    /** photo = base64 JPEG (small avatar) stored inline in the user doc. "" clears it. */
    fun updatePhoto(uid: String, base64: String, onDone: (String?) -> Unit) {
        db.collection("users").document(uid)
            .set(mapOf("photo" to base64), SetOptions.merge())
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not save photo") }
    }

    // Banner = a wide Discord-style profile cover. Kept in its OWN doc (not users/{uid}) so friend search,
    // which pulls whole user docs, stays light — the big blob is only fetched when a profile card opens.
    fun updateBanner(uid: String, base64: String, onDone: (String?) -> Unit) {
        db.collection("user_banners").document(uid).set(mapOf("banner" to base64))
            .addOnSuccessListener { onDone(null) }.addOnFailureListener { onDone(it.message ?: "Could not save banner") }
    }

    fun fetchBanner(uid: String, onResult: (String) -> Unit) {
        db.collection("user_banners").document(uid).get()
            .addOnSuccessListener { onResult(it.getString("banner") ?: "") }
            .addOnFailureListener { onResult("") }
    }

    sealed interface ClaimResult {
        data class Success(val tag: String) : ClaimResult
        data object Taken : ClaimResult
        data class Error(val message: String) : ClaimResult
    }

    /**
     * Atomically reserve [display]'s handle for [uid]. Idempotent if the tag is already yours,
     * so re-running onboarding (reinstall with no local cache) is safe. Also used for renames:
     * frees the user's previous handle so they don't squat it. createdAt is set only on first claim.
     */
    fun claimTag(uid: String, display: String, onResult: (ClaimResult) -> Unit) {
        val lower = normalize(display)
        val nameRef = db.collection("usernames").document(lower)
        val userRef = db.collection("users").document(uid)
        db.runTransaction { txn ->
            val userSnap = txn.get(userRef)                 // read before writes
            val oldLower = userSnap.getString("tagLower")
            val owner = txn.get(nameRef).getString("uid")
            if (owner != null && owner != uid) throw TakenException()
            txn.set(nameRef, mapOf("uid" to uid))
            if (oldLower != null && oldLower != lower) txn.delete(db.collection("usernames").document(oldLower))
            val data = mutableMapOf<String, Any>("tag" to display, "tagLower" to lower)
            if (!userSnap.exists()) data["createdAt"] = FieldValue.serverTimestamp()
            txn.set(userRef, data, SetOptions.merge())
        }.addOnSuccessListener { onResult(ClaimResult.Success(display)) }
            .addOnFailureListener {
                onResult(if (it is TakenException) ClaimResult.Taken else ClaimResult.Error(it.message ?: "Could not save your tag"))
            }
    }

    /** Delete the user's own cloud profile: their user doc, @tag reservation, and banner. */
    fun deleteMyData(uid: String, tagLower: String, onDone: (String?) -> Unit) {
        val batch = db.batch()
        batch.delete(db.collection("users").document(uid))
        if (tagLower.isNotEmpty()) batch.delete(db.collection("usernames").document(tagLower))
        batch.delete(db.collection("user_banners").document(uid))
        batch.commit()
            .addOnSuccessListener { onDone(null) }
            .addOnFailureListener { onDone(it.message ?: "Couldn't delete your data") }
    }

    private class TakenException : Exception()
}
