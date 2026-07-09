// MyWay push notifications. Two Firestore triggers send FCM data-messages so the Android app can
// notify members even when it's fully closed:
//   • a new group message  -> "GroupName — @tag: preview"
//   • a trip just started   -> "Trip started in GroupName"
// The client (MyFirebaseMessagingService) posts the notification; foreground alerts are handled in-app,
// so it ignores the push when the app is in the foreground.
const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue } = require("firebase-admin/firestore");
const { getMessaging } = require("firebase-admin/messaging");

initializeApp();
const db = getFirestore();

// Collect the push tokens of every member except [excludeUid]. Also remembers which uid owns each
// token so we can prune dead ones after a failed send.
async function collectTokens(memberUids, excludeUid) {
  const uids = (memberUids || []).filter((u) => u && u !== excludeUid);
  const tokens = [];
  const owners = {};
  await Promise.all(uids.map(async (uid) => {
    const snap = await db.collection("fcm_tokens").doc(uid).get();
    const list = (snap.exists && snap.data().tokens) || [];
    for (const t of list) { tokens.push(t); owners[t] = uid; }
  }));
  return { tokens, owners };
}

async function pushData(tokens, owners, data) {
  if (!tokens.length) return;
  const res = await getMessaging().sendEachForMulticast({
    tokens,
    data, // all values must be strings
    android: { priority: "high" },
  });
  // Remove tokens the platform reports as gone, so the registry stays clean.
  const stale = {};
  res.responses.forEach((r, i) => {
    const code = r.success ? null : (r.error && r.error.code);
    if (code === "messaging/invalid-registration-token" ||
        code === "messaging/registration-token-not-registered") {
      const tok = tokens[i]; const uid = owners[tok];
      (stale[uid] = stale[uid] || []).push(tok);
    }
  });
  await Promise.all(Object.entries(stale).map(([uid, toks]) =>
    db.collection("fcm_tokens").doc(uid).set(
      { tokens: FieldValue.arrayRemove(...toks) }, { merge: true },
    )));
}

function messagePreview(m) {
  if (m.image) return "📷 Photo";
  if (m.liveFrom) return "🔴 Live location";
  if (m.pinLat !== undefined && m.pinLat !== null) return "📍 " + (m.pinName || "Location");
  return m.text || "";
}

exports.onGroupMessage = onDocumentCreated("groups/{gid}/messages/{mid}", async (event) => {
  const msg = event.data && event.data.data();
  if (!msg || msg.system === true) return;                 // skip system notices
  const gid = event.params.gid;
  const groupSnap = await db.collection("groups").doc(gid).get();
  if (!groupSnap.exists) return;
  const group = groupSnap.data();
  const { tokens, owners } = await collectTokens(group.members, msg.from || "");
  await pushData(tokens, owners, {
    type: "message",
    gid,
    groupName: group.name || "Group",
    fromTag: msg.fromTag || "",
    preview: messagePreview(msg),
  });
});

exports.onTripStart = onDocumentUpdated("groups/{gid}", async (event) => {
  const before = event.data.before.data();
  const after = event.data.after.data();
  if (!before || !after) return;
  if (before.tripActive === true || after.tripActive !== true) return;   // only false/undefined -> true
  const gid = event.params.gid;
  const { tokens, owners } = await collectTokens(after.members, null);   // starter's app is foreground → it self-skips
  await pushData(tokens, owners, {
    type: "trip",
    gid,
    groupName: after.name || "Group",
  });
});
