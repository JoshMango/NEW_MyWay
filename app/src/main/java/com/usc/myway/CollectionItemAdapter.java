// logic for the saved locations(item) in the collections
package com.usc.myway;

import android.content.Context;
import android.content.Intent;
import android.location.Address;
import android.location.Geocoder;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.List;
import java.util.Map;

public class CollectionItemAdapter extends ArrayAdapter<String> {

    private final Collection collection;
    private final App myApp;
    private final CollectionAdapter parentAdapter;

    public CollectionItemAdapter(Context context, List<String> keys,
                                 Map<String, String> locationNotes, // kept for compatibility, not stored
                                 Collection collection, App myApp,
                                 CollectionAdapter parentAdapter) {
        super(context, 0, keys);
        this.collection    = collection;
        this.myApp         = myApp;
        this.parentAdapter = parentAdapter;
        // locationNotes intentionally NOT stored as field
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null)
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_collectionitem, parent, false);

        String key = getItem(position);
        TextView tv_address = convertView.findViewById(R.id.tv_noted_address);
        TextView tv_note    = convertView.findViewById(R.id.tv_noted_note);
        TextView btn_remove = convertView.findViewById(R.id.btn_remove_from_collection);


        // always fresh from App, (for details of name/note, of each collection item)
        String[] parts = key.split(",");
        Map<String, String> freshNotes = myApp.getLocationNotes();
        String note = freshNotes.getOrDefault(key, "");
        if (note.isEmpty()) {
            tv_note.setVisibility(View.GONE);
        } else {
            tv_note.setVisibility(View.VISIBLE);
            tv_note.setText("📝 " + note);
        }
        // show saved name first, fallback to geocode, fallback to coords
        String savedName = myApp.getLocationName(key);
        if (!savedName.isEmpty()) {
            tv_address.setText(savedName);
        } else {
            try {
                double lat = Double.parseDouble(parts[0].trim());
                double lng = Double.parseDouble(parts[1].trim());
                Geocoder geocoder = new Geocoder(getContext());
                List<Address> addresses = geocoder.getFromLocation(lat, lng, 1);
                if (addresses != null && !addresses.isEmpty())
                    tv_address.setText(addresses.get(0).getAddressLine(0));
                else
                    tv_address.setText(key);
            } catch (Exception e) {
                tv_address.setText(key);
            }
        }

        btn_remove.setOnClickListener(v -> {
            collection.locationKeys.remove(key);
            myApp.saveCollectionsToPrefs();
            remove(key);
            notifyDataSetChanged();
            parentAdapter.syncAndNotify();
        });

        //show map and pin of collection item clicked in collections
        convertView.setOnClickListener(v -> {
            try {
                double lat = Double.parseDouble(parts[0].trim());
                double lng = Double.parseDouble(parts[1].trim());
                Intent intent = new Intent(getContext(), MainActivity.class);
                intent.putExtra("focus_lat", lat);
                intent.putExtra("focus_lng", lng);
                intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
                getContext().startActivity(intent);
            } catch (Exception e) { /* none */ }
        });
        return convertView;
    }
}