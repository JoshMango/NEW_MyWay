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

    sealed interface ClaimResult {
        data class Success(val tag: String) : ClaimResult
        data object Taken : ClaimResult
        data class Error(val message: String) : ClaimResult
    }

    /**
     * Atomically reserve [display]'s handle for [uid]. Idempotent if the tag is already yours,
     * so re-running onboarding (e.g. after a reinstall with no local cache) is safe.
     */
    fun claimTag(uid: String, display: String, onResult: (ClaimResult) -> Unit) {
        val lower = normalize(display)
        val nameRef = db.collection("usernames").document(lower)
        val userRef = db.collection("users").document(uid)
        db.runTransaction { txn ->
            val owner = txn.get(nameRef).getString("uid")
            if (owner != null && owner != uid) throw TakenException()
            txn.set(nameRef, mapOf("uid" to uid))
            txn.set(
                userRef,
                mapOf("tag" to display, "tagLower" to lower, "createdAt" to FieldValue.serverTimestamp()),
                SetOptions.merge(),
            )
        }.addOnSuccessListener { onResult(ClaimResult.Success(display)) }
            .addOnFailureListener {
                onResult(if (it is TakenException) ClaimResult.Taken else ClaimResult.Error(it.message ?: "Could not save your tag"))
            }
    }

    private class TakenException : Exception()
}
