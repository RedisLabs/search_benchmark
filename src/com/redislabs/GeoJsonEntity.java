package com.redislabs;

/**
 * Just a wrapper class to load from json
 */
public class GeoJsonEntity {
    public static class Geometry {
        public Double[] coordinates;
        public String type;
    }

    public static class Classifier {
        public String category;
        public String subcategory;
        public String type;
    }

    public static class Properties {
        public String address;
        public String city;
        public Classifier[] classifiers;

        public String country;
        public String href;
        public String name;
        public String owner;
        public String phone;
        public String postcode;
        public String province;
        public String[] tags;
    }

    public Geometry geometry;
    public String id;
    public Properties properties;
    public String type;
}
