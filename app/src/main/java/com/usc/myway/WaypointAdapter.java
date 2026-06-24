// logic for saved waypoints(item) in saved locations
package com.usc.myway;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.location.Location;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.io.IOException;
import java.util.List;

public class WaypointAdapter extends ArrayAdapter<Location> {

    private final App myApp;

    public WaypointAdapter(Context context, List<Location> locations) {
        super(context, 0, locations);
        myApp = (App) context.getApplicationContext();
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_savedwaypoint, parent, false);
        }

        Location loc = getItem(position);

        TextView tv_index    = convertView.findViewById(R.id.tv_index);
        TextView tv_address  = convertView.findViewById(R.id.tv_waypoint_address);
        TextView tv_coords   = convertView.findViewById(R.id.tv_waypoint_coords);
        TextView tv_note     = convertView.findViewById(R.id.tv_waypoint_note);
        TextView btn_menu    = convertView.findViewById(R.id.btn_waypoint_menu);
        TextView tv_collection = convertView.findViewById(R.id.tv_waypoint_collection);

        tv_index.setText(String.valueOf(position + 1));
        tv_coords.setText(String.format("%.5f, %.5f", loc.getLatitude(), loc.getLongitude()));

        String key  = App.locationKey(loc.getLatitude(), loc.getLongitude());

        //note name
        String note = myApp.getLocationNotes().getOrDefault(key, "");
        if (!note.isEmpty()) { //not empty note
            tv_note.setVisibility(View.VISIBLE);
            tv_note.setText("📝 " + note);
        } else {
            tv_note.setVisibility(View.GONE);
        }

        //collection name
        String collectionName = null;
        for (Collection c : myApp.getCollections()) {
            if (c.locationKeys.contains(key)) {
                collectionName = c.icon + " " + c.name;
                break;
            }
        }
        if (collectionName != null) {
            tv_collection.setVisibility(View.VISIBLE);
            tv_collection.setText(collectionName);
        } else {
            tv_collection.setVisibility(View.GONE);
        }

        String name = myApp.getLocationName(key);

        if (!name.isEmpty())
            tv_address.setText(name);
        else {
            Geocoder geocoder = new Geocoder(getContext());
            try {
                List<Address> addresses = geocoder.getFromLocation(
                        loc.getLatitude(), loc.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty())
                    tv_address.setText(addresses.get(0).getAddressLine(0));
                else
                    tv_address.setText("Unknown Location");
            } catch (IOException e) {
                tv_address.setText("Unknown Location");
            }
        }

        //show map and pin of the location(item) clicked
        convertView.setOnClickListener(v -> {
            Intent intent = new Intent(getContext(), MainActivity.class);
            intent.putExtra("focus_lat", loc.getLatitude());
            intent.putExtra("focus_lng", loc.getLongitude());
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            getContext().startActivity(intent);
        });

        // edit/delete button click
        btn_menu.setOnClickListener(v -> {
            android.widget.PopupMenu popup = new android.widget.PopupMenu(getContext(), btn_menu);
            popup.getMenu().add(0, 0, 0, "✏️ Edit");
            popup.getMenu().add(0, 1, 1, "🗑️ Delete");

            popup.setOnMenuItemClickListener(item -> {
                if (item.getItemId() == 0) {
                    showEditDialog(loc, position);
                } else {
                    new AlertDialog.Builder(getContext())
                            .setTitle("Delete Waypoint")
                            .setMessage("Are you sure you want to delete this waypoint?")
                            .setPositiveButton("Delete", (dialog, which) -> {
                                myApp.removeLocation(loc);
                                remove(loc);
                                notifyDataSetChanged();
                                Toast.makeText(getContext(), "Waypoint deleted.",
                                        Toast.LENGTH_SHORT).show();
                            })
                            .setNegativeButton("Cancel", null)
                            .show();
                }
                return true;
            });
            popup.show();
        });
        return convertView;
    }
    private void showEditDialog(Location loc, int position) {
        String key          = App.locationKey(loc.getLatitude(), loc.getLongitude());
        String currentName  = myApp.getLocationName(key);
        String currentNote  = myApp.getLocationNotes().getOrDefault(key, "");

        View dialogView = LayoutInflater.from(getContext())
                .inflate(R.layout.dialog_editpin, null);

        android.widget.EditText et_name  = dialogView.findViewById(R.id.et_pin_name);
        android.widget.EditText et_notes = dialogView.findViewById(R.id.et_pin_notes);
        TextView tv_coords               = dialogView.findViewById(R.id.tv_pin_coords);
        TextView tv_addr                 = dialogView.findViewById(R.id.tv_pin_address);
        TextView btn_cancel              = dialogView.findViewById(R.id.btn_pin_cancel);
        TextView btn_save                = dialogView.findViewById(R.id.btn_pin_save);
        TextView tv_collection           = dialogView.findViewById(R.id.tv_pin_collection);
        LinearLayout btn_collection          = dialogView.findViewById(R.id.btn_pin_collection);

        // Pre-fill with current values
        et_name.setText(currentName);
        et_notes.setText(currentNote);
        tv_coords.setText(String.format("%.6f, %.6f", loc.getLatitude(), loc.getLongitude()));
        tv_addr.setText("Loading address...");
        btn_save.setText("Save Changes");

        // Find which collection this location currently belongs to
        final String[] selectedCollection = {null};
        for (Collection c : myApp.getCollections()) {
            if (c.locationKeys.contains(key)) {
                selectedCollection[0] = c.name;
                tv_collection.setText(c.icon + " " + c.name);
                break;
            }
        }
        if (selectedCollection[0] == null) {
            tv_collection.setText("Tap to choose a collection...");
        }

        // Collection picker click
        btn_collection.setOnClickListener(v -> {
            List<Collection> collections = myApp.getCollections();
            if (collections.isEmpty()) {
                Toast.makeText(getContext(), "No collections yet.", Toast.LENGTH_SHORT).show();
                return;
            }

            String[] options = new String[collections.size() + 1];
            options[0] = "❌ None";
            for (int i = 0; i < collections.size(); i++)
                options[i + 1] = collections.get(i).icon + " " + collections.get(i).name;

            new AlertDialog.Builder(getContext())
                    .setTitle("Add to Collection")
                    .setItems(options, (d, which) -> {
                        if (which == 0) {
                            selectedCollection[0] = null;
                            tv_collection.setText("Tap to choose a collection...");
                        } else {
                            Collection chosen = collections.get(which - 1);
                            selectedCollection[0] = chosen.name;
                            tv_collection.setText(chosen.icon + " " + chosen.name);
                        }
                    })
                    .show();
        });

        // Geocode address
        new Thread(() -> {
            try {
                Geocoder geocoder = new Geocoder(getContext());
                List<Address> addresses = geocoder.getFromLocation(
                        loc.getLatitude(), loc.getLongitude(), 1);
                if (addresses != null && !addresses.isEmpty()) {
                    String addr = addresses.get(0).getAddressLine(0);
                    ((android.app.Activity) getContext()).runOnUiThread(
                            () -> tv_addr.setText(addr));
                }
            } catch (Exception ignored) {}
        }).start();

        AlertDialog dialog = new AlertDialog.Builder(getContext())
                .setView(dialogView)
                .setCancelable(true)
                .create();
        if (dialog.getWindow() != null)
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        btn_cancel.setOnClickListener(v -> dialog.dismiss());

        btn_save.setOnClickListener(v -> {
            String newName  = et_name.getText().toString().trim();
            String newNotes = et_notes.getText().toString().trim();

            // save name
            if (!newName.isEmpty()) myApp.saveLocationName(key, newName);
            else myApp.removeLocationName(key);

            // save note
            if (!newNotes.isEmpty()) myApp.saveNote(key, newNotes);
            else myApp.removeNote(key);

            // remove from all collections first, then add to selected one
            for (Collection c : myApp.getCollections()) {
                c.locationKeys.remove(key);
            }
            if (selectedCollection[0] != null) {
                for (Collection c : myApp.getCollections()) {
                    if (c.name.equals(selectedCollection[0])) {
                        if (!c.locationKeys.contains(key)) c.locationKeys.add(key);
                        break;
                    }
                }
            }
            myApp.saveCollectionsToPrefs();
            notifyDataSetChanged();
            Toast.makeText(getContext(), "Waypoint updated!", Toast.LENGTH_SHORT).show();
            dialog.dismiss();
        });

        dialog.show();
    }
}