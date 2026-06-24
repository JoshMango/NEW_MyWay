// collections/album folder (item)
package com.usc.myway;

import java.util.ArrayList;
import java.util.List;

public class Collection {
    public String name;
    public String icon;
    public List<String> locationKeys; // list of "lat,lng" keys

    public Collection(String name, String icon) {
        this.name = name;
        this.icon = icon;
        this.locationKeys = new ArrayList<>();
    }
}