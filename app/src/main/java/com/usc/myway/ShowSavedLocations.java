// page for showing (all)saved locations and collections
package com.usc.myway;

import android.location.Location;
import android.os.Bundle;
import android.widget.TextView;

import androidx.activity.EdgeToEdge;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.viewpager2.adapter.FragmentStateAdapter;
import androidx.viewpager2.widget.ViewPager2;

import com.google.android.material.tabs.TabLayout;
import com.google.android.material.tabs.TabLayoutMediator;

import java.util.List;

public class ShowSavedLocations extends AppCompatActivity {

    private TextView tv_subtitle;
    private App myApp;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_show_saved_locations);

        myApp = (App) getApplicationContext();

        tv_subtitle = findViewById(R.id.tv_waypointSubtitle);
        updateSubtitle();

        ViewPager2 viewPager = findViewById(R.id.view_pager);
        TabLayout tabLayout  = findViewById(R.id.tab_layout);

        viewPager.setAdapter(new FragmentStateAdapter(this) {
            @NonNull
            @Override
            public Fragment createFragment(int position) {
                return position == 0 ? new AllWaypointsFragment() : new CollectionsListFragment();
            }
            @Override
            public int getItemCount() { return 2; }
        });

        new TabLayoutMediator(tabLayout, viewPager, (tab, position) ->
                tab.setText(position == 0 ? "All Locations" : "Collections")
        ).attach();
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateSubtitle(); // refresh count when coming back from waypoint deletion
    }

    private void updateSubtitle() {
        List<Location> locs = myApp.getMyLocations();
        tv_subtitle.setText(locs.size() + " location"
                + (locs.size() == 1 ? "" : "s") + " saved");
    }
}