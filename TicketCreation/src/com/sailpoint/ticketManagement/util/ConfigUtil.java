package com.sailpoint.ticketManagement.util;

import sailpoint.api.SailPointContext;
import sailpoint.object.Custom;
import sailpoint.tools.GeneralException;

import java.util.Map;

public class ConfigUtil {

    public static String getStringConfig(SailPointContext context, String customName, String key) {
        try {
            Custom custom = context.getObjectByName(Custom.class, customName);
            if (custom == null) return null;
            Object value = custom.get(key);
            return value == null ? null : String.valueOf(value);
        } catch (GeneralException e) {
            throw new RuntimeException("Failed to read custom config: " + customName, e);
        }
    }

    public static boolean getBooleanConfig(SailPointContext context, String customName, String key, boolean defaultValue) {
        String value = getStringConfig(context, customName, key);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }
}
