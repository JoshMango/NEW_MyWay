// MyWay push notifications. Firestore triggers + a scheduled tick send FCM data-messages so the app
// (Android or iOS) can notify members even when it's fully closed:
//   • a new group message   -> "GroupName — @tag: preview"
//   • a trip just started    -> "Trip started in GroupName"
//   • a scheduled trip       -> a day-before / 15-min-before reminder, and auto-go-live at the start time
// The client (MyFirebaseMessagingService) posts the notification; foreground alerts are handled in-app,
// so it ignores the push when the app is in the foreground.
const { onDocumentCreated, onDocumentUpdated } = require("firebase-functions/v2/firestore");
const { onSchedule } = require("firebase-functions/v2/scheduler");
const { initializeApp } = require("firebase-admin/app");
const { getFirestore, FieldValue, Timestamp } = require("firebase-admin/firestore");
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

// Scheduled trips: a group may set `tripScheduledAt` (a future Timestamp) instead of going live now.
// This tick drives the whole lifecycle server-side so it works cross-platform even with every app closed:
//   • ~a day before  -> reminder push       (tripDayNotified guards against re-sending)
//   • ~15 min before -> reminder push       (tripSoonNotified)
//   • at start time  -> flip tripActive=true (clears the schedule); onTripStart then sends "Trip started".
const DAY_MS = 24 * 60 * 60 * 1000;
const SOON_MS = 15 * 60 * 1000;

// Pure decision for one scheduled trip: what to do this tick given the start time and what's been sent.
// Returns "activate" | "soon" | "day" | null (nothing due). Kept side-effect-free so it's self-checkable.
function reminderDecision(startMs, now, dayNotified, soonNotified) {
  if (startMs <= now) return "activate";
  if (startMs <= now + SOON_MS) return soonNotified ? null : "soon";   // in the 15-min window
  return dayNotified ? null : "day";                                   // caller only passes trips within DAY_MS
}

exports.tripScheduleTick = onSchedule("every 5 minutes", async () => {
  const now = Date.now();
  // Only groups whose scheduled start is within the next day (docs without the field are excluded).
  const snap = await db.collection("groups")
    .where("tripScheduledAt", "<=", Timestamp.fromMillis(now + DAY_MS)).get();

  await Promise.all(snap.docs.map(async (doc) => {
    const g = doc.data();
    const at = g.tripScheduledAt;
    if (!at || typeof at.toMillis !== "function") return;

    switch (reminderDecision(at.toMillis(), now, g.tripDayNotified, g.tripSoonNotified)) {
      case "activate":   // start time reached → go live; onTripStart then sends the "Trip started" push.
        return doc.ref.update({
          tripActive: true,
          tripScheduledAt: FieldValue.delete(),
          tripDayNotified: FieldValue.delete(),
          tripSoonNotified: FieldValue.delete(),
        });
      case "soon": {
        await doc.ref.update({ tripSoonNotified: true, tripDayNotified: true });   // day window has passed
        const { tokens, owners } = await collectTokens(g.members, null);
        return pushData(tokens, owners, { type: "tripScheduled", gid: doc.id,
          groupName: g.name || "Group", body: "Your trip starts in about 15 minutes." });
      }
      case "day": {
        await doc.ref.update({ tripDayNotified: true });
        const { tokens, owners } = await collectTokens(g.members, null);
        return pushData(tokens, owners, { type: "tripScheduled", gid: doc.id,
          groupName: g.name || "Group", body: "Your trip is coming up." });
      }
      default:
        return null;
    }
  }));
});

// ponytail: self-check for the reminder branch logic — `node index.js`. No framework.
if (require.main === module) {
  const assert = require("assert");
  const now = 1_000_000_000;
  assert.equal(reminderDecision(now - 1, now, false, false), "activate");        // past-due → go live
  assert.equal(reminderDecision(now, now, true, true), "activate");              // exactly now → go live
  assert.equal(reminderDecision(now + 60_000, now, false, false), "soon");       // 1 min out → soon
  assert.equal(reminderDecision(now + 60_000, now, false, true), null);          // soon already sent
  assert.equal(reminderDecision(now + 60 * 60_000, now, false, false), "day");   // 1 h out → day reminder
  assert.equal(reminderDecision(now + 60 * 60_000, now, true, false), null);     // day already sent
  console.log("reminderDecision self-check passed");
}
