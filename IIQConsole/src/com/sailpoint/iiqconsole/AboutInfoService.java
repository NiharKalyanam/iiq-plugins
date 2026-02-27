package com.sailpoint.iiqconsole;

import sailpoint.Version;
import sailpoint.api.SailPointContext;
import sailpoint.object.Identity;
import sailpoint.object.Filter;
import sailpoint.object.QueryOptions;
import sailpoint.tools.GeneralException;

import java.net.InetAddress;
import java.net.JarURLConnection;
import java.net.URL;
import java.net.URLConnection;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TimeZone;

import java.util.jar.Attributes;
import java.util.jar.Manifest;

import javax.faces.context.FacesContext;

public class AboutInfoService {

	public String buildAboutText(SailPointContext ctx) throws GeneralException {
	    Map<String,Object> about = buildAbout(ctx);
	    StringBuilder sb = new StringBuilder();

	    sb.append("About IdentityIQ\n\n");

	    sb.append("Server Date\n");
	    sb.append(about.get("serverDate")).append("\n\n");

	    Map server = (Map) about.get("serverInformation");
	    sb.append("Server Information\n");
	    sb.append("Host Name\t").append(server.get("hostName")).append("\n");
	    sb.append("Task Scheduler Hosts\t").append(server.get("taskSchedulerHosts")).append("\n");
	    sb.append("Task Scheduler Status\t").append(server.get("taskSchedulerStatus")).append("\n");
	    sb.append("Request Processor Hosts\t").append(server.get("requestProcessorHosts")).append("\n");
	    sb.append("Request Scheduler Status\t").append(server.get("requestSchedulerStatus")).append("\n\n");

	    Map prod = (Map) about.get("productInformation");
	    sb.append("Product Information\n");
	    sb.append("Version\t").append(prod.get("version")).append("\n");
	    sb.append("E-Fixes\t").append(prod.get("eFixes")).append("\n");
	    sb.append("Schema Version\t").append(prod.get("schemaVersion")).append("\n");
	    sb.append("Source Repository Location\t").append(prod.get("sourceRepositoryLocation")).append("\n");
	    sb.append("Builder\t").append(prod.get("builder")).append("\n");
	    sb.append("Build Date\t").append(prod.get("buildDate")).append("\n");
	    sb.append("Application Home\t").append(prod.get("applicationHome")).append("\n\n");

	    Map cb = (Map) about.get("connectorBundleInformation");
	    sb.append("Connector Bundle Information\n");
	    sb.append("Version\t").append(cb.get("version")).append("\n");
	    sb.append("Source Repository Location\t").append(cb.get("sourceRepositoryLocation")).append("\n");
	    sb.append("Builder\t").append(cb.get("builder")).append("\n");
	    sb.append("Build Date\t").append(cb.get("buildDate")).append("\n\n");

	    Map client = (Map) about.get("clientInformation");
	    sb.append("Client Information\n");
	    sb.append("Locale\t").append(client.get("locale")).append("\n");
	    sb.append("Time Zone\t").append(client.get("timeZone")).append("\n\n");

	    Map stats = (Map) about.get("identityStatistics");
	    sb.append("Identity Statistics\n");
	    sb.append("Number of Identities\t").append(stats.get("numberOfIdentities")).append("\n");
	    sb.append("Active Identities\t").append(stats.get("activeIdentities")).append("\n");
	    sb.append("Inactive Identities\t").append(stats.get("inactiveIdentities")).append("\n");
	    sb.append("Uncorrelated Identities\t").append(stats.get("uncorrelatedIdentities")).append("\n");
	    sb.append("Identity Snapshots\t").append(stats.get("identitySnapshots")).append("\n");
	    sb.append("Licensed Identities\t").append(stats.get("licensedIdentities")).append("\n\n");

	    Map sys = (Map) about.get("javaSystemProperties");
	    sb.append("Java System Properties\n");
	    sb.append("Available processors\t").append(sys.get("availableProcessors")).append("\n");

	    Map props = (Map) sys.get("properties");
	    if (props != null) {
	        for (Object k : props.keySet()) {
	            sb.append(k).append("\t").append(props.get(k)).append("\n");
	        }
	    }

	    return sb.toString();
	}
	
	public Map<String, Object> buildAbout(SailPointContext ctx) throws GeneralException {
	    Map<String, Object> about = new LinkedHashMap<>();

	    // Server Date
	    about.put("serverDate", formatServerDate());

	    // Server Information
	    Map<String, Object> server = new LinkedHashMap<>();
	    server.put("hostName", getHostNameSafe());
	    server.put("taskSchedulerHosts", "global");
	    server.put("taskSchedulerStatus", "Started");
	    server.put("requestProcessorHosts", "global");
	    server.put("requestSchedulerStatus", "Started");
	    about.put("serverInformation", server);

	    // Product Information
	    Map<String, Object> product = new LinkedHashMap<>();
	    Map<String, String> core = readCoreBuildManifest();
	    // This matches what you already got: "8.1 Build 573..."
	    String displayVersion = buildDisplayVersion(core);

	    product.put("version", displayVersion);
	    product.put("eFixes", "None"); // If you later find the real source, replace here.
	    product.put("schemaVersion", getSchemaVersionBestEffort(ctx)); // see method below
	    product.put("sourceRepositoryLocation", nvl(core.get("Source-Repository-Location"), "Unknown"));
	    product.put("builder", nvl(core.get("Builder"), "Unknown"));
	    product.put("buildDate", nvl(core.get("Build-Date"), "Unknown"));
	    product.put("applicationHome", getApplicationHomeSafe());
	    about.put("productInformation", product);

	    // Connector Bundle Information (best-effort)
	    Map<String, Object> connector = new LinkedHashMap<>();
	    Map<String, String> cb = readConnectorBundleManifestBestEffort();
	    connector.put("version", nvl(cb.get("Version"), "Unknown"));
	    connector.put("sourceRepositoryLocation", nvl(cb.get("Source-Repository-Location"), "Unknown"));
	    connector.put("builder", nvl(cb.get("Builder"), "Unknown"));
	    connector.put("buildDate", nvl(cb.get("Build-Date"), "Unknown"));
	    about.put("connectorBundleInformation", connector);

	    // Client Information
	    Map<String, Object> client = new LinkedHashMap<>();
	    client.put("locale", getClientLocaleSafe());
	    client.put("timeZone", TimeZone.getDefault().getID());
	    about.put("clientInformation", client);

	    // Identity Statistics (small env: fine to compute)
	    about.put("identityStatistics", getIdentityStats(ctx));

	    // Java System Properties
	    Map<String, Object> sys = new LinkedHashMap<>();
	    sys.put("availableProcessors", Runtime.getRuntime().availableProcessors());
	    sys.put("properties", systemPropsAsMap());
	    about.put("javaSystemProperties", sys);

	    return about;
	  }

	  private static  String formatServerDate() {
	    SimpleDateFormat sdf = new SimpleDateFormat("EEEE, MMMM d, yyyy 'at' h:mm:ss a z", Locale.US);
	    sdf.setTimeZone(TimeZone.getDefault());
	    return sdf.format(new Date());
	  }

	  private static String getHostNameSafe() {
	    try { return InetAddress.getLocalHost().getHostName(); }
	    catch (Exception e) { return "Unknown"; }
	  }

	  private static  String getApplicationHomeSafe() {
	    try {
	      FacesContext fc = FacesContext.getCurrentInstance();
	      if (fc != null && fc.getExternalContext() != null) {
	        String p = fc.getExternalContext().getRealPath("/");
	        if (p != null) return p;
	      }
	    } catch (Exception ignored) {}
	    // fallback for Tomcat
	    String home = System.getProperty("catalina.base");
	    if (home != null) return home;
	    return "Unknown";
	  }

	  private static  String getClientLocaleSafe() {
	    try {
	      FacesContext fc = FacesContext.getCurrentInstance();
	      if (fc != null && fc.getViewRoot() != null && fc.getViewRoot().getLocale() != null) {
	        return fc.getViewRoot().getLocale().toString();
	      }
	    } catch (Exception ignored) {}
	    return Locale.getDefault().toString();
	  }

	  private static Map<String, String> readCoreBuildManifest() {
	    Map<String, String> out = new LinkedHashMap<>();
	    try {
	      URL u = Version.class.getResource("Version.class");
	      URLConnection c = u.openConnection();
	      if (c instanceof JarURLConnection) {
	        Manifest m = ((JarURLConnection) c).getManifest();
	        Attributes a = m.getMainAttributes();
	        putIfNotNull(out, "Implementation-Version", a.getValue("Implementation-Version"));
	        putIfNotNull(out, "Build", a.getValue("Build"));
	        putIfNotNull(out, "Build-Date", a.getValue("Build-Date"));
	        putIfNotNull(out, "Builder", a.getValue("Builder"));
	        putIfNotNull(out, "Source-Repository-Location", a.getValue("Source-Repository-Location"));
	      }
	    } catch (Exception ignored) {}
	    return out;
	  }

	  private static String buildDisplayVersion(Map<String, String> core) {
	    String v = nvl(core.get("Implementation-Version"), Version.getVersion()); // 8.1
	    String b = core.get("Build");
	    if (b != null && !b.trim().isEmpty()) {
	      return v.trim() + " Build " + b.trim();
	    }
	    return v;
	  }

	  private static String getSchemaVersionBestEffort(SailPointContext ctx) {
	    try {
	      sailpoint.object.Configuration sc =
	          ctx.getObjectByName(sailpoint.object.Configuration.class, "SystemConfiguration");
	      if (sc != null && sc.getAttributes() != null) {
	        String sv = sc.getAttributes().getString("schemaVersion");
	        if (sv != null && !sv.trim().isEmpty()) return sv.trim();
	      }
	    } catch (Exception ignored) {}

	    return "Unknown";
	  }

	  private static Map<String, Object> getIdentityStats(SailPointContext ctx) throws GeneralException {
	    Map<String, Object> stats = new LinkedHashMap<>();
	    int total = 0, active = 0, inactive = 0, uncorrelated = 0;

	    sailpoint.object.Filter f = null;
	    sailpoint.object.QueryOptions qo = new sailpoint.object.QueryOptions();
	    qo.addFilter(f);

	    java.util.Iterator<?> it = ctx.search(Identity.class, qo);
	    while (it != null && it.hasNext()) {
	      total++;
	      Identity id = (Identity) it.next();

	      boolean disabled = Boolean.TRUE.equals(id.isDisabled());
	      if (disabled) inactive++; else active++;

	      try {
	        if (id.getLinks() == null || id.getLinks().isEmpty()) uncorrelated++;
	      } catch (Exception ignored) {}
	    }

	    stats.put("numberOfIdentities", total);
	    stats.put("activeIdentities", active);
	    stats.put("inactiveIdentities", inactive);
	    stats.put("uncorrelatedIdentities", uncorrelated);
	    stats.put("identitySnapshots", 0);
	    stats.put("licensedIdentities", 0);

	    return stats;
	  }

	  private static Map<String, String> readConnectorBundleManifestBestEffort() {
	    Map<String, String> out = new LinkedHashMap<>();
	    try {
	      // Try the bundle marker class if present in your build (may vary)
	      Class<?> cls = Class.forName("sailpoint.connector.ConnectorServices");
	      URL u = cls.getResource(cls.getSimpleName() + ".class");
	      URLConnection c = u.openConnection();
	      if (c instanceof JarURLConnection) {
	        Manifest m = ((JarURLConnection) c).getManifest();
	        Attributes a = m.getMainAttributes();
	        putIfNotNull(out, "Version", a.getValue("Implementation-Version"));
	        putIfNotNull(out, "Build-Date", a.getValue("Build-Date"));
	        putIfNotNull(out, "Builder", a.getValue("Builder"));
	        putIfNotNull(out, "Source-Repository-Location", a.getValue("Source-Repository-Location"));
	      }
	    } catch (Exception ignored) {
	      // leave Unknown if class not found
	    }
	    return out;
	  }

	  private static Map<String, String> systemPropsAsMap() {
	    Properties p = System.getProperties();
	    Map<String, String> m = new LinkedHashMap<>();
	    for (String name : p.stringPropertyNames()) {
	      m.put(name, p.getProperty(name));
	    }
	    return m;
	  }

	  private static void putIfNotNull(Map<String, String> m, String k, String v) {
	    if (v != null && !v.trim().isEmpty()) m.put(k, v.trim());
	  }

	  private static String nvl(String v, String def) {
	    return (v == null || v.trim().isEmpty()) ? def : v.trim();
	  }
	}

