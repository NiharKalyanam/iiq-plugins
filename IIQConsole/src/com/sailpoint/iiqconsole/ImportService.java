package com.sailpoint.iiqconsole;

import bsh.Interpreter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.api.ObjectUtil;
import sailpoint.api.SailPointContext;

import sailpoint.object.*;

import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;
import sailpoint.tools.xml.XMLObjectFactory;
import sailpoint.tools.XmlTools;

import java.io.PrintWriter;
import java.io.StringWriter;

import java.net.URL;
import java.net.URLConnection;
import java.net.JarURLConnection;

import java.util.jar.Manifest;
import java.util.jar.Attributes;

import sailpoint.Version;
import java.util.*;

import org.w3c.dom.Document;

public class ImportService {
	
	private static final Log log = LogFactory.getLog(ImportService.class);

    private final SailPointContext ctx;
    
    public ImportService(SailPointContext ctx) {
        this.ctx = ctx;
    }
 // ======================= IMPORT START =======================

    /**
     * import <xml> - Import XML object(s) into IIQ.
     * Supports:
     *  - <sailpoint>...</sailpoint>
     *  - single object XML: <Rule>...</Rule>, <Application>...</Application>, etc.
     * Version-safe:
     *  - Uses XMLObjectFactory.getInstance() when available
     *  - Falls back to other parseXml signatures
     * Safe TX:
     *  - commit once
     *  - rollback on failure
     */
    public String handleImport(String xmlContent) {
        if (Util.isNullOrEmpty(xmlContent)) {
            return "ERROR: No XML content provided. Paste the full XML after 'import'";
        }

        boolean committed = false;

        try {
            String normalizedXml = normalizeImportXml(xmlContent);

            Object parsed = parseXmlCompatible(ctx, normalizedXml);
            List<SailPointObject> objects = extractSailPointObjects(parsed);

            if (objects == null || objects.isEmpty()) {
                return "ERROR: XML parsed, but produced no SailPointObject(s). Parsed=" +
                        (parsed == null ? "null" : parsed.getClass().getName());
            }

            // OOTB-like behavior: create if missing, update if exists
            int created = 0;
            int updated = 0;

            for (SailPointObject o : objects) {
                if (o == null) continue;

                SailPointObject existing = null;

                // 1) If incoming XML has an id, try by id first (update path)
                try {
                    if (!Util.isNullOrEmpty(o.getId())) {
                        existing = ctx.getObjectById((Class) o.getClass(), o.getId());
                    }
                } catch (Throwable ignored) {
                    // ignore lookup failures, fallback to name
                }

                // 2) If not found by id (or no id), try by name (OOTB console behavior)
                try {
                    if (existing == null && !Util.isNullOrEmpty(o.getName())) {
                        existing = ctx.getObjectByName((Class) o.getClass(), o.getName());
                    }
                } catch (Throwable ignored) {
                    // ignore lookup failures
                }

                // 3) If object exists and incoming object has no id (or wrong id), set existing id -> forces UPDATE
                if (existing != null && !Util.isNullOrEmpty(existing.getId())) {
                    o.setId(existing.getId());
                    try {
                        // These exist on many SailPointObject types
                        o.setCreated(existing.getCreated());
                    } catch (Throwable ignored) { }
                    updated++;
                } else {
                    created++;
                }
                ctx.saveObject(o);
            }

            ctx.commitTransaction();
            committed = true;

            StringBuilder sb = new StringBuilder();
            sb.append("✓ Import successful: ").append(objects.size()).append(" object(s)\n");
            sb.append("  Created: ").append(created).append("\n");
            sb.append("  Updated: ").append(updated).append("\n\n");

            for (SailPointObject o : objects) {
                if (o == null) continue;
                sb.append("  - ").append(o.getClass().getSimpleName());
                if (!Util.isNullOrEmpty(o.getName())) sb.append(" '").append(o.getName()).append("'");
                if (!Util.isNullOrEmpty(o.getId())) sb.append(" (id=").append(o.getId()).append(")");
                sb.append("\n");
            }

            return sb.toString();

        } catch (Exception e) {
            try { if (!committed) ctx.rollbackTransaction(); } catch (Exception ignored) {}

            // Show deepest/root cause (Hibernate constraint errors become clear)
            Throwable root = e;
            while (root.getCause() != null) root = root.getCause();

            String msg = (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName());
            String rootMsg = (root.getMessage() != null ? root.getMessage() : root.getClass().getSimpleName());

            return "ERROR during import: " + msg + " | Root: " + root.getClass().getName() + ": " + rootMsg;
        }
    }

    
    /**
     * Normalize XML for web-console importing:
     * - trims junk before first '<'
     * - strips DOCTYPE (avoids DTD / root mismatch in web context)
     * - wraps single objects into <sailpoint>...</sailpoint>
     */
    private String normalizeImportXml(String xmlContent) {
        String s = (xmlContent == null) ? "" : xmlContent.trim();

        int firstLt = s.indexOf('<');
        if (firstLt > 0) s = s.substring(firstLt);

        // Remove XML declaration (must be first in a real XML document; wrapping breaks it)
        s = s.replaceAll("(?is)<\\?xml[^>]*\\?>", "");

        // Remove DOCTYPE (web-side parsing often breaks on DTD)
        s = s.replaceAll("(?is)<!DOCTYPE[^>]*>", "");

        s = s.trim();

        // If already wrapped with <sailpoint>, return as-is
        if (s.matches("(?is)^\\s*<sailpoint\\b.*")) {
            return s;
        }

        // Wrap a single object to make parsing consistent across versions
        return "<sailpoint>\n" + s + "\n</sailpoint>";
    }

    /**
     * Version-safe XML parse:
     * - prefers XMLObjectFactory.getInstance()
     * - supports different parseXml signatures across IIQ versions
     * - IMPORTANT: validate=false to accept single-object XML without DOCTYPE
     */
    private Object parseXmlCompatible(SailPointContext ctx, String xml) throws Exception {

        Class<?> xofClass = Class.forName("sailpoint.tools.xml.XMLObjectFactory");

        // Prefer getInstance() (present in many 8.x builds)
        Object xof = null;
        try {
            java.lang.reflect.Method getInstance = xofClass.getMethod("getInstance");
            xof = getInstance.invoke(null);
        } catch (NoSuchMethodException ignored) {
            // older builds may not have getInstance()
        }

        // Fallback: try no-arg constructor if available (some builds)
        if (xof == null) {
            try {
                xof = xofClass.getDeclaredConstructor().newInstance();
            } catch (Throwable ignored) {
                // can't instantiate - may still have static parse methods
            }
        }

        java.util.List<java.lang.reflect.Method> methods = new java.util.ArrayList<>();
        try { methods.addAll(java.util.Arrays.asList(xofClass.getMethods())); } catch (Throwable ignored) {}
        try { methods.addAll(java.util.Arrays.asList(xofClass.getDeclaredMethods())); } catch (Throwable ignored) {}

        Throwable last = null;

        // A) parseXml(XMLReferenceResolver, String, boolean)  (common in IIQ 8.x)
        for (java.lang.reflect.Method m : methods) {
            if (!"parseXml".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();
            if (p.length == 3
                    && "sailpoint.tools.xml.XMLReferenceResolver".equals(p[0].getName())
                    && p[1] == String.class
                    && (p[2] == boolean.class || p[2] == Boolean.class)) {
                try {
                    m.setAccessible(true);

                    boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    Object target = isStatic ? null : xof;
                    if (!isStatic && target == null) continue;

                    Object resolver = (p[0].isInstance(ctx)) ? ctx : buildXmlReferenceResolver(ctx, p[0]);

                    // CRITICAL: validate=false (prevents DOCTYPE/root mismatch errors)
                    return m.invoke(target, resolver, xml, Boolean.FALSE);

                } catch (java.lang.reflect.InvocationTargetException ite) {
                    last = ite.getTargetException();
                } catch (Throwable t) {
                    last = t;
                }
            }
        }

        // B) parseXml(SailPointContext, String)
        for (java.lang.reflect.Method m : methods) {
            if (!"parseXml".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();
            if (p.length == 2
                    && sailpoint.api.SailPointContext.class.isAssignableFrom(p[0])
                    && p[1] == String.class) {
                try {
                    m.setAccessible(true);

                    boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    Object target = isStatic ? null : xof;
                    if (!isStatic && target == null) continue;

                    return m.invoke(target, ctx, xml);

                } catch (java.lang.reflect.InvocationTargetException ite) {
                    last = ite.getTargetException();
                } catch (Throwable t) {
                    last = t;
                }
            }
        }

        // C) parseXml(String)
        for (java.lang.reflect.Method m : methods) {
            if (!"parseXml".equals(m.getName())) continue;

            Class<?>[] p = m.getParameterTypes();
            if (p.length == 1 && p[0] == String.class) {
                try {
                    m.setAccessible(true);

                    boolean isStatic = java.lang.reflect.Modifier.isStatic(m.getModifiers());
                    Object target = isStatic ? null : xof;
                    if (!isStatic && target == null) continue;

                    return m.invoke(target, xml);

                } catch (java.lang.reflect.InvocationTargetException ite) {
                    last = ite.getTargetException();
                } catch (Throwable t) {
                    last = t;
                }
            }
        }

        if (last != null) {
            throw new RuntimeException("XML parse failed: " + last.getMessage(), last);
        }
        throw new RuntimeException("No compatible XMLObjectFactory.parseXml(...) signature found in this IIQ build.");
    }
    @SuppressWarnings("unchecked")
    private List<SailPointObject> extractSailPointObjects(Object parsed) {
        List<SailPointObject> out = new ArrayList<>();
        if (parsed == null) return out;

        // IMPORTANT: SailPointImport is NOT a SailPointObject in your IIQ build.
        // So detect it FIRST by class name.
        if ("sailpoint.object.SailPointImport".equals(parsed.getClass().getName())) {
            out.addAll(extractFromSailPointImport(parsed));
            return out;
        }

        // Direct object
        if (parsed instanceof SailPointObject) {
            out.add((SailPointObject) parsed);
            return out;
        }

        // Collection
        if (parsed instanceof Collection) {
            for (Object o : (Collection<?>) parsed) {
                if (o == null) continue;

                if ("sailpoint.object.SailPointImport".equals(o.getClass().getName())) {
                    out.addAll(extractFromSailPointImport(o));
                } else if (o instanceof SailPointObject) {
                    out.add((SailPointObject) o);
                }
            }
            return out;
        }

        // Array
        if (parsed.getClass().isArray()) {
            int len = java.lang.reflect.Array.getLength(parsed);
            for (int i = 0; i < len; i++) {
                Object o = java.lang.reflect.Array.get(parsed, i);
                if (o == null) continue;

                if ("sailpoint.object.SailPointImport".equals(o.getClass().getName())) {
                    out.addAll(extractFromSailPointImport(o));
                } else if (o instanceof SailPointObject) {
                    out.add((SailPointObject) o);
                }
            }
            return out;
        }

        // Iterator
        if (parsed instanceof Iterator) {
            Iterator<?> it = (Iterator<?>) parsed;
            while (it.hasNext()) {
                Object o = it.next();
                if (o == null) continue;

                if ("sailpoint.object.SailPointImport".equals(o.getClass().getName())) {
                    out.addAll(extractFromSailPointImport(o));
                } else if (o instanceof SailPointObject) {
                    out.add((SailPointObject) o);
                }
            }
            return out;
        }

        return out;
    }

    @SuppressWarnings("unchecked")
    private List<SailPointObject> extractFromSailPointImport(Object sailPointImportObj) {
        List<SailPointObject> out = new ArrayList<>();
        if (sailPointImportObj == null) return out;

        try {
            // In your identityiq.jar: SailPointImport has getObjects()
            java.lang.reflect.Method m = sailPointImportObj.getClass().getMethod("getObjects");
            Object val = m.invoke(sailPointImportObj);

            if (val == null) return out;

            // getObjects() returns List<AbstractXmlObject> in your build
            if (val instanceof Collection) {
                for (Object o : (Collection<?>) val) {
                    if (o instanceof SailPointObject) {
                        out.add((SailPointObject) o);
                    }
                }
            } else if (val.getClass().isArray()) {
                int len = java.lang.reflect.Array.getLength(val);
                for (int i = 0; i < len; i++) {
                    Object o = java.lang.reflect.Array.get(val, i);
                    if (o instanceof SailPointObject) {
                        out.add((SailPointObject) o);
                    }
                }
            }

            return out;

        } catch (Exception e) {
            return out;
        }
    }

    /**
     * Builds an XMLReferenceResolver instance compatible with your IIQ.
     * Uses your existing approach; keep as-is if already present in your class.
     */
    private Object buildXmlReferenceResolver(SailPointContext ctx, Class<?> resolverType) throws Exception {

        if (resolverType.isInstance(ctx)) return ctx;

        try {
            Class<?> def = Class.forName("sailpoint.tools.xml.DefaultXMLReferenceResolver");
            if (resolverType.isAssignableFrom(def)) {
                return def.getConstructor(sailpoint.api.SailPointContext.class).newInstance(ctx);
            }
        } catch (Throwable ignored) { }

        String[] candidates = new String[] {
                "sailpoint.tools.xml.XMLReferenceResolverImpl",
                "sailpoint.tools.xml.ContextXMLReferenceResolver",
                "sailpoint.tools.xml.SailPointXMLReferenceResolver"
        };

        for (String cn : candidates) {
            try {
                Class<?> c = Class.forName(cn);
                if (resolverType.isAssignableFrom(c)) {
                    try {
                        return c.getConstructor(sailpoint.api.SailPointContext.class).newInstance(ctx);
                    } catch (NoSuchMethodException nsm) {
                        return c.getDeclaredConstructor().newInstance();
                    }
                }
            } catch (Throwable ignored) { }
        }

        throw new RuntimeException("Unable to create XMLReferenceResolver of type: " + resolverType.getName());
    }

}
