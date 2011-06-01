package com.woe.adapter;

/**
 * Since checkout dynamically creates property id's on store creationg we'll
 * need some constants here that have to be updated on the creation of each
 * store.
 * 
 * @author esklein
 *
 */
public final class InventoryConstants {
    public static final int DEFAULT_CATEGORY = 1;

    public static final String NEW_ACTION = "newAction";
    public static final String UPDATE_ACTION = "updateAction";

    public static final int PROPERTY_NAME = 2;
    public static final int PROPERTY_TAGS = 20;
    public static final int PROPERTY_BARCODE = 14;
    public static final int PROPERTY_BRAND = 15;
    public static final int PROPERTY_DESCRIPTON = 17;
    public static final int PROPERTY_CODE = 16;
    public static final int PROPERTY_WEIGHT = 21;
    public static final int PROPERTY_PRICE = 18;
    public static final int PROPERTY_COST = 19;
}
