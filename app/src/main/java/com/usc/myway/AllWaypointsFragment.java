package com.usc.myway;

import android.location.Location;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.usc.myway.R;

import java.util.ArrayList;
import java.util.List;

public class AllWaypointsFragment extends Fragment {

    private WaypointAdapter adapter;
    private List<Location> filteredLocs;
    private List<Location> allLocs;
    private App myApp;
    private View layout_empty;
    private View layout_no_results;
    private TextView tv_no_results_query;
    private ListView lv;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        View view = inflater.inflate(R.layout.frag_allwaypoints, container, false);

        lv                  = view.findViewById(R.id.lv_waypoints);
        layout_empty        = view.findViewById(R.id.layout_empty);
        layout_no_results   = view.findViewById(R.id.layout_no_results);
        tv_no_results_query = view.findViewById(R.id.tv_no_results_query);
        EditText et_search  = view.findViewById(R.id.et_waypoint_search);
        TextView btn_clear  = view.findViewById(R.id.btn_waypoint_search_clear);

        myApp    = (App) requireContext().getApplicationContext();
        allLocs  = myApp.getMyLocations();
        filteredLocs = new ArrayList<>(allLocs);

        if (allLocs.isEmpty()) {
            lv.setVisibility(View.GONE);
            layout_empty.setVisibility(View.VISIBLE);
            et_search.setEnabled(false);
        } else {
            layout_empty.setVisibility(View.GONE);
            setupAdapter();
        }

        // Search watcher
        TextWatcher searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                btn_clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                filterList(query);
            }
        };
        et_search.addTextChangedListener(searchWatcher);

        btn_clear.setOnClickListener(v -> {
            et_search.removeTextChangedListener(searchWatcher);
            et_search.setText("");
            et_search.addTextChangedListener(searchWatcher);
            btn_clear.setVisibility(View.GONE);
            filterList("");
        });

        return view;
    }

    private void setupAdapter() {
        adapter = new WaypointAdapter(requireContext(), filteredLocs) {
            @Override
            public void notifyDataSetChanged() {
                super.notifyDataSetChanged();
                updateVisibility("");
            }
        };
        lv.setAdapter(adapter);
        updateVisibility("");
    }

    //filter for search bar
    private void filterList(String query) {
        filteredLocs.clear();

        if (query.isEmpty()) {
            filteredLocs.addAll(allLocs);
        } else {
            for (Location loc : allLocs) {
                String key  = App.locationKey(loc.getLatitude(), loc.getLongitude());
                String name = myApp.getLocationName(key).toLowerCase();
                String note = myApp.getLocationNotes().getOrDefault(key, "").toLowerCase();
                // Match against saved name or note
                if (name.contains(query) || note.contains(query)) {
                    filteredLocs.add(loc);
                }
            }
        }
        if (adapter != null) adapter.notifyDataSetChanged();
        updateVisibility(query);
    }
    private void updateVisibility(String query) {
        if (allLocs.isEmpty()) {
            lv.setVisibility(View.GONE);
            layout_empty.setVisibility(View.VISIBLE);
            layout_no_results.setVisibility(View.GONE);
        } else if (filteredLocs.isEmpty() && !query.isEmpty()) {
            lv.setVisibility(View.GONE);
            layout_empty.setVisibility(View.GONE);
            layout_no_results.setVisibility(View.VISIBLE);
            tv_no_results_query.setText("No results for \"" + query + "\"");
        } else {
            lv.setVisibility(View.VISIBLE);
            layout_empty.setVisibility(View.GONE);
            layout_no_results.setVisibility(View.GONE);
        }
    }
}