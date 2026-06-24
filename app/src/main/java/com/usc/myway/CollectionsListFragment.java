package com.usc.myway;

import android.app.AlertDialog;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.util.ArrayList;
import java.util.List;

public class CollectionsListFragment extends Fragment {
    private App myApp;
    private CollectionAdapter collectionAdapter;
    private List<Collection> filteredCollections;

    private RecyclerView rv;
    private View layout_empty;
    private View layout_no_results;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {

        View view = inflater.inflate(R.layout.frag_collections, container, false);

        myApp = (App) requireContext().getApplicationContext();

        // Views
        rv = view.findViewById(R.id.rv_collections);
        layout_empty = view.findViewById(R.id.layout_empty_noted);
        layout_no_results = view.findViewById(R.id.layout_no_results2);

        FloatingActionButton fab = view.findViewById(R.id.fab_new_collection);
        EditText et_search = view.findViewById(R.id.et_collection_search);
        TextView btn_clear = view.findViewById(R.id.btn_collection_search_clear);

        rv.setLayoutManager(new LinearLayoutManager(requireContext()));

        // Initial data
        filteredCollections = new ArrayList<>(myApp.getCollections());

        collectionAdapter = new CollectionAdapter(
                requireContext(),
                filteredCollections,
                myApp.getLocationNotes(),
                () -> {
                    // Sync filteredCollections with actual collections
                    filteredCollections.clear();
                    filteredCollections.addAll(myApp.getCollections());
                    collectionAdapter.syncAndNotify();
                    refreshState("");
                }
        );

        rv.setAdapter(collectionAdapter);

        // initial state
        refreshState("");
        // fab
        fab.setOnClickListener(v -> showCreateCollectionDialog());
        // search watcher
        TextWatcher searchWatcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void afterTextChanged(Editable s) {}

            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {
                String query = s.toString().trim().toLowerCase();
                btn_clear.setVisibility(query.isEmpty() ? View.GONE : View.VISIBLE);
                filterCollections(query);
            }
        };

        et_search.addTextChangedListener(searchWatcher);

        // Clear button
        btn_clear.setOnClickListener(v -> {
            et_search.removeTextChangedListener(searchWatcher);
            et_search.setText("");
            et_search.addTextChangedListener(searchWatcher);

            btn_clear.setVisibility(View.GONE);
            filterCollections("");
        });

        return view;
    }

// filtering logic
    private void filterCollections(String query) {
        filteredCollections.clear();

        List<Collection> all = myApp.getCollections();

        if (query.isEmpty()) {
            filteredCollections.addAll(all);
        } else {
            for (Collection c : all) {
                if (c.name.toLowerCase().contains(query)) {
                    filteredCollections.add(c);
                }
            }
        }

        collectionAdapter.syncAndNotify();
        refreshState(query);
    }

// state handler
    private void refreshState(String query) {
        if (filteredCollections.isEmpty()) {
            rv.setVisibility(View.GONE);

            if (query.isEmpty()) {
                layout_empty.setVisibility(View.VISIBLE);      // no collections
                layout_no_results.setVisibility(View.GONE);
            } else {
                layout_empty.setVisibility(View.GONE);
                layout_no_results.setVisibility(View.VISIBLE); // no search results
            }
        } else {
            rv.setVisibility(View.VISIBLE);
            layout_empty.setVisibility(View.GONE);
            layout_no_results.setVisibility(View.GONE);
        }
    }

 //create collection dialog
    private void showCreateCollectionDialog() {

        View dialogView = LayoutInflater.from(requireContext())
                .inflate(R.layout.dialog_new_collection, null);

        EditText et_icon = dialogView.findViewById(R.id.et_collection_icon);
        EditText et_name = dialogView.findViewById(R.id.et_collection_name);
        LinearLayout ll_emoji = dialogView.findViewById(R.id.ll_emoji_row);
        TextView btn_cancel = dialogView.findViewById(R.id.btn_dialog_cancel);
        TextView btn_create = dialogView.findViewById(R.id.btn_dialog_create);

        // Emoji shortcuts
        String[] quickEmojis = {
                "📁","⭐","🏠","🍔","🏋️","🏥","🏫","🛍️","🌿","🚗",
                "📍","💼","🎯","🌟","❤️","🎵","📸","🌍","🏖️","🎓"
        };

        for (String emoji : quickEmojis) {
            TextView btn = new TextView(requireContext());
            btn.setText(emoji);
            btn.setTextSize(24);

            int pad = (int) (8 * getResources().getDisplayMetrics().density);
            btn.setPadding(pad, pad, pad, pad);

            btn.setOnClickListener(v -> et_icon.setText(emoji));
            ll_emoji.addView(btn);
        }

        AlertDialog dialog = new AlertDialog.Builder(requireContext())
                .setView(dialogView)
                .create();

        if (dialog.getWindow() != null) {
            dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        }

        btn_cancel.setOnClickListener(v -> dialog.dismiss());

        btn_create.setOnClickListener(v -> {
            String icon = et_icon.getText().toString().trim();
            String name = et_name.getText().toString().trim();

            if (icon.isEmpty()) icon = "📁";
            if (name.isEmpty()) name = "New Collection";

            myApp.saveCollection(new Collection(name, icon));

            // Refresh list
            filteredCollections.clear();
            filteredCollections.addAll(myApp.getCollections());
            collectionAdapter.syncAndNotify();

            // Refresh UI state
            refreshState("");

            dialog.dismiss();
        });

        dialog.show();
    }
}