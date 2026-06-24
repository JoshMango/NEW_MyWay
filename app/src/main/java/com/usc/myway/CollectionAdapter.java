//logic for the collections folder (item)
package com.usc.myway;

import android.app.AlertDialog;
import android.content.Context;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class CollectionAdapter extends RecyclerView.Adapter<CollectionAdapter.ViewHolder> {

    private final Context context;
    private final List<Collection> collections;
    private final Runnable onChanged;
    private final App myApp;

    // Track which collections are expanded
    private final List<Boolean> expandedStates;

    public CollectionAdapter(Context context, List<Collection> collections,
                             Map<String, String> locationNotes, Runnable onChanged) {
        this.context       = context;
        this.collections   = collections;
        this.onChanged     = onChanged;
        this.myApp         = (App) context.getApplicationContext();

        expandedStates = new ArrayList<>();
        for (int i = 0; i < collections.size(); i++) expandedStates.add(false);
    }
    public void syncAndNotify() {
        while (expandedStates.size() < collections.size()) {
            expandedStates.add(false);
        }
        while (expandedStates.size() > collections.size()) {
            expandedStates.remove(expandedStates.size() - 1);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(context).inflate(R.layout.item_collection, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        Collection c       = collections.get(position);
        boolean expanded = position < expandedStates.size() && expandedStates.get(position);
        holder.tv_icon.setText(c.icon);
        holder.tv_name.setText(c.name);

        // count location keys that exist in my locations (not deleted)
        long validCount = c.locationKeys.stream()
                .filter(key -> myApp.getMyLocations().stream()
                        .anyMatch(loc -> App.locationKey(loc.getLatitude(), loc.getLongitude()).equals(key)))
                .count();

        // expand / collapse
        holder.layout_expandable.setVisibility(expanded ? View.VISIBLE : View.GONE);
        holder.tv_arrow.setText(expanded ? "▼" : "▶");

        holder.layout_header.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            expandedStates.set(pos, !expandedStates.get(pos));
            notifyItemChanged(pos);
        });


        // delete/edit collection
        holder.btn_menu.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;

            android.widget.PopupMenu popup = new android.widget.PopupMenu(context, holder.btn_menu);
            popup.getMenu().add(0, 0, 0, "✏️ Edit");
            popup.getMenu().add(0, 1, 1, "🗑️ Delete");

            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 0)
                    // edit
                    showEditCollectionDialog(c, pos);
                else {
                    // delete
                    new AlertDialog.Builder(context)
                            .setTitle("Delete Collection")
                            .setMessage("Delete \"" + c.name + "\"? Locations won't be deleted.")
                            .setPositiveButton("Delete", (d, w) -> {
                                int currentPos = holder.getAdapterPosition();
                                if (currentPos == RecyclerView.NO_POSITION) return;
                                if (currentPos < expandedStates.size())
                                    expandedStates.remove(currentPos);
                                myApp.removeCollection(c);
                                syncAndNotify();
                                onChanged.run();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                } return true;
            });
            popup.show();
        });

        // Add location button
        holder.btn_add.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            showAddLocationDialog(c, pos);
        });

        // shows all locations in collection
        CollectionItemAdapter innerAdapter = new CollectionItemAdapter(
                context, new ArrayList<>(c.locationKeys), myApp.getLocationNotes(), c, myApp, this);

        holder.lv_items.setAdapter(innerAdapter);
        innerAdapter.notifyDataSetChanged();

        setListViewHeightBasedOnChildren(holder.lv_items);
    }


    private void showEditCollectionDialog(Collection c, int position) {
        View dialogView = LayoutInflater.from(context)
                .inflate(R.layout.dialog_new_collection, null);

        EditText et_icon = dialogView.findViewById(R.id.et_collection_icon);
        EditText et_name = dialogView.findViewById(R.id.et_collection_name);
        LinearLayout ll_emoji_row = dialogView.findViewById(R.id.ll_emoji_row);
        TextView btn_cancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        TextView btn_create = dialogView.findViewById(R.id.btn_dialog_create);

        // Pre-fill with current values
        et_icon.setText(c.icon);
        et_name.setText(c.name);
        btn_create.setText("Save");

        // Populate emoji quick pick row
        String[] quickEmojis = {"📁", "⭐", "🏠", "🍔", "🏋️", "🏥", "🏫", "🛍️", "🌿", "🚗",
                "📍", "💼", "🎯", "🌟", "❤️", "🎵", "📸", "🌍", "🏖️", "🎓"};
        for (String emoji : quickEmojis) {
            TextView btn = new TextView(context);
            btn.setText(emoji);
            btn.setTextSize(24);
            int pad = (int) (8 * context.getResources().getDisplayMetrics().density);
            btn.setPadding(pad, pad, pad, pad);
            btn.setOnClickListener(v -> et_icon.setText(emoji));
            ll_emoji_row.addView(btn);
        }

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        btn_cancel.setOnClickListener(v -> dialog.dismiss());
        btn_create.setOnClickListener(v -> {
            String newIcon = et_icon.getText().toString().trim();
            String newName = et_name.getText().toString().trim();
            if (newIcon.isEmpty()) newIcon = "📁";
            if (newName.isEmpty()) newName = "New Collection";
            c.icon = newIcon;
            c.name = newName;
            myApp.saveCollectionsToPrefs();
            syncAndNotify();
            onChanged.run();
            dialog.dismiss();
        });

        dialog.show();
    }
    private void showAddLocationDialog(Collection c, int position) {
        Map<String, String> freshNotes = myApp.getLocationNotes();
        List<Location> allLocations = myApp.getMyLocations();

        List<String> availableKeys      = new ArrayList<>();
        List<String> availableAddresses = new ArrayList<>();

        // Show ALL saved locations not already in this collection
        for (Location loc : allLocations) {
            String key = App.locationKey(loc.getLatitude(), loc.getLongitude());
            if (!c.locationKeys.contains(key)) {
                availableKeys.add(key);
                availableAddresses.add(resolveAddress(key));
            }
        }

        if (availableKeys.isEmpty()) {
            Toast.makeText(context, "No more locations to add.", Toast.LENGTH_SHORT).show();
            return;
        }

        View dialogView = LayoutInflater.from(context)
                .inflate(R.layout.addto_collections, null);

        TextView tv_title    = dialogView.findViewById(R.id.tv_dialog_title);
        TextView tv_subtitle = dialogView.findViewById(R.id.tv_dialog_subtitle);
        LinearLayout ll_items = dialogView.findViewById(R.id.ll_noted_items);

        tv_title.setText("Add to \"" + c.name + "\"");
        tv_subtitle.setText(availableKeys.size() + " location"
                + (availableKeys.size() == 1 ? "" : "s") + " available");

        AlertDialog dialog = new AlertDialog.Builder(context)
                .setView(dialogView)
                .setNegativeButton("Close", null)
                .create();

        for (int i = 0; i < availableKeys.size(); i++) {
            String key     = availableKeys.get(i);
            String address = availableAddresses.get(i);
            String note    = freshNotes.getOrDefault(key, "");
            String[] parts = key.split(",");

            View row = LayoutInflater.from(context)
                    .inflate(R.layout.item_addloc_choice, ll_items, false);

            TextView tv_address = row.findViewById(R.id.tv_choice_address);
            TextView tv_note    = row.findViewById(R.id.tv_choice_note);
            TextView tv_coords  = row.findViewById(R.id.tv_choice_coords);
            TextView btn_add    = row.findViewById(R.id.btn_add_choice);

            tv_address.setText(address);
            // Show note if exists, otherwise show a placeholder
            if (note.isEmpty()) {
                tv_note.setText("📍 No note");
                tv_note.setAlpha(0.5f);
            } else {
                tv_note.setText("📝 " + note);
                tv_note.setAlpha(1f);
            }
            tv_coords.setText(parts[0].trim() + ", " + parts[1].trim());

            btn_add.setOnClickListener(v -> {
                c.locationKeys.add(key);
                myApp.saveCollectionsToPrefs();
                notifyItemChanged(position);
                ll_items.removeView(row);
                int remaining = ll_items.getChildCount();
                tv_subtitle.setText(remaining + " location"
                        + (remaining == 1 ? "" : "s") + " available");
                Toast.makeText(context, "Added!", Toast.LENGTH_SHORT).show();
                if (remaining == 0) dialog.dismiss();
            });
            ll_items.addView(row);
        }
        dialog.show();
    }

    private String resolveAddress(String key) {
        String[] parts = key.split(",");
        try {
            double lat = Double.parseDouble(parts[0].trim());
            double lng = Double.parseDouble(parts[1].trim());
            Geocoder geocoder = new Geocoder(context);
            List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
            if (addresses != null && !addresses.isEmpty())
                return addresses.get(0).getAddressLine(0);
        } catch (Exception e) { /* fall through */ }
        return key;
    }

    @Override
    public int getItemCount() { return collections.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        TextView tv_icon, tv_name, tv_count, btn_menu, tv_arrow;
        LinearLayout layout_header, layout_expandable, btn_add;
        ListView lv_items;

        ViewHolder(View v) {
            super(v);
            tv_icon           = v.findViewById(R.id.tv_collection_icon);
            tv_name           = v.findViewById(R.id.tv_collection_name);
            tv_count          = v.findViewById(R.id.tv_collection_count);
            btn_menu          = v.findViewById(R.id.btn_collection_menu);
            tv_arrow          = v.findViewById(R.id.tv_arrow);
            layout_header     = v.findViewById(R.id.layout_collection_header);
            layout_expandable = v.findViewById(R.id.layout_expandable);
            btn_add           = v.findViewById(R.id.btn_add_to_collection);
            lv_items          = v.findViewById(R.id.lv_collection_items);
        }
    }

    private static void setListViewHeightBasedOnChildren(ListView lv) {
        ArrayAdapter adapter = (ArrayAdapter) lv.getAdapter();
        if (adapter == null || adapter.getCount() == 0) {
            lv.getLayoutParams().height = 0;
            lv.requestLayout();
            return;
        }
        int totalHeight = 0;
        for (int i = 0; i < adapter.getCount(); i++) {
            View item = adapter.getView(i, null, lv);
            item.measure(0, 0);
            totalHeight += item.getMeasuredHeight();
        }
        ViewGroup.LayoutParams params = lv.getLayoutParams();
        params.height = totalHeight + (lv.getDividerHeight() * (adapter.getCount() - 1));
        lv.setLayoutParams(params);
        lv.requestLayout();
    }
}