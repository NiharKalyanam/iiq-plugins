package com.sailpoint.iiqconsole;

import bsh.Interpreter;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.api.ObjectUtil;
import sailpoint.api.SailPointContext;

import sailpoint.object.Attributes;
import sailpoint.object.AuditEvent;
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
//import java.util.jar.Attributes;

import sailpoint.Version;
import java.util.*;

import org.w3c.dom.Document;

import sailpoint.server.Auditor;

import com.sailpoint.iiqconsole.AboutInfoService;
import com.sailpoint.iiqconsole.ImportService;

/**
 * CommandRouter - Routes IIQ console commands to their handlers.
 *
 * Supported commands (mirrors real ./iiq console behavior):
 *   about                          - infomation from the about page
 *   help                           - List all commands
 *   list   <Type>                  - List all objects of a type
 *   get    <Type> <name>           - Get and print XML of an object
 *   rm     <Type> <name>           - Delete an object (with dependency check)
 *   import <xml-content>           - Import an XML object
 *   export <Type> <name>           - Export object to XML string
 *   run    <RuleName>              - Run a Rule by name
 *   count  <Type>                  - Count objects of a type
 *   search <Type> <filter>         - Search objects by filter string
 *   lock   <Identity>              - Lock an Identity
 *   unlock <Identity>              - Unlock an Identity
 *   resetpwd <Identity>            - Reset identity's password
 *   reindex <Type>                 - Reindex a specific object type
 *   exec   <beanshell>             - Execute raw BeanShell code
 *   version                        - Show IIQ version info
 *   clear                          - (handled client-side)
 *
 * Author: Nihar Kalyanam
 * Date: Feb, 2026
 * Version: 1.0
 */
public class CommandRouter {

    private static final Log log = LogFactory.getLog(CommandRouter.class);

    private final SailPointContext ctx;
    
    public void auditEvent(SailPointContext context, sailpoint.object.Attributes attribute) throws GeneralException {
    	System.out.println("------------->");
		AuditEvent event = new AuditEvent();
        event.setAction("IIQ-Console");
        event.setApplication("IIQ");
        event.setAttributes(attribute);
        Auditor.log(event);
        context.commitTransaction();
        System.out.println(">>>>>>>>>>>>>>>>");
	}
    private boolean isFailureOutput(String out) {
        if (out == null) return true;
        String s = out.trim();
        if (s.isEmpty()) return false; // empty output isn't necessarily a failure
        String lower = s.toLowerCase(Locale.ROOT);
        return lower.startsWith("error")
                || lower.startsWith("unknown command")
                || lower.startsWith("usage:")
                || lower.contains("error during");
    }

    // All valid SailPoint object types that can be used in commands
    private static final List<String> SUPPORTED_TYPES = Arrays.asList(
        "Application", "Bundle", "Certification", "CertificationDefinition",
        "Configuration", "CorrelationConfig", "Custom", "DynamicScope",
        "EmailTemplate", "Form", "Identity", "IdentityTrigger",
        "IntegrationConfig", "ManagedAttribute", "MessageTemplate",
        "ObjectConfig", "Policy", "PolicyViolation", "Process",
        "ProcessLog", "Profile", "ProvisioningPlan", "ProvisioningProject",
        "QuickLink", "Request", "RequestDefinition", "Role",
        "Rule", "RuleRegistry", "SPRight", "Scope", "ServiceDefinition",
        "ServiceStatus", "Tag", "Target", "TargetAssociation",
        "TargetSource", "TaskDefinition", "TaskResult", "TaskSchedule",
        "UIConfig", "Workflow", "WorkflowCase", "WorkflowRegistry",
        "WorkItem", "WorkItemConfig"
    );

    public CommandRouter(SailPointContext ctx) {
        this.ctx = ctx;
    }

    public static List<String> getSupportedObjectTypes() {
        return Collections.unmodifiableList(SUPPORTED_TYPES);
    }

    /**
     * Main routing method. Parses the command string and dispatches to handler.
     */
    public String route(String input, Identity loggedUser) throws Exception {

        String cmd = (input == null) ? "" : input;
        String output = "";
        boolean success = false;
        Exception failure = null;

        try {
            if (cmd.trim().isEmpty()) {
                output = "";
                success = true;
                return output;
            }

            // Handle multi-line beanshell exec blocks first
            if (cmd.toLowerCase().startsWith("exec ") || cmd.toLowerCase().startsWith("bsh ")) {
                int spaceIdx = cmd.indexOf(' ');
                String script = (spaceIdx >= 0) ? cmd.substring(spaceIdx + 1).trim() : "";
                output = handleExec(script);
                success = !isFailureOutput(output);
                return output;
            }

            // Tokenize (verb only)
            String[] tokens = cmd.trim().split("\\s+", 3);
            String verb = tokens[0].toLowerCase(Locale.ROOT);

            switch (verb) {

                case "help":
                case "?":
                    output = getHelp();
                    break;

                case "about":
                    output = new AboutInfoService().buildAboutText(ctx);
                    break;

                case "version":
                    output = handleVersion();
                    break;

                case "list":
                case "ls":
                    output = (tokens.length < 2)
                            ? usage("list <ObjectType>\nExample: list Identity")
                            : handleList(tokens[1], tokens.length > 2 ? tokens[2] : null);
                    break;

                case "get":
                    output = (tokens.length < 3)
                            ? usage("get <ObjectType> <name>\nExample: get Identity John.Doe")
                            : handleGet(tokens[1], tokens[2]);
                    break;

                case "rm":
                case "delete":
                case "remove":
                    output = (tokens.length < 3)
                            ? usage("rm <ObjectType> <name>\nExample: rm Rule MyRule")
                            : handleDelete(tokens[1], tokens[2]);
                    break;

                case "import":
                    if (tokens.length < 2) {
                        output = usage("import <xml-content>\nPaste the full XML object content");
                    } else {
                        String xmlContent = cmd.substring(verb.length()).trim();
                        ImportService importService = new ImportService(ctx);
                        output = importService.handleImport(xmlContent);
                    }
                    break;

                case "export":
                    output = (tokens.length < 3)
                            ? usage("export <ObjectType> <name>\nExample: export Rule MyRule")
                            : handleExport(tokens[1], tokens[2]);
                    break;

                case "run":
                    if (tokens.length < 2) {
                        output = usage("run <RuleName>\nExample: run MyBeanShellRule");
                    } else {
                        String ruleName = (tokens.length >= 3) ? (tokens[1] + " " + tokens[2]) : tokens[1];
                        output = handleRunRule(ruleName);
                    }
                    break;

                case "count":
                    output = (tokens.length < 2)
                            ? usage("count <ObjectType>\nExample: count Identity")
                            : handleCount(tokens[1]);
                    break;

                case "search":
                    output = (tokens.length < 3)
                            ? usage("search <ObjectType> <filter>\nExample: search Identity name==\"John Doe\"")
                            : handleSearch(tokens[1], tokens[2]);
                    break;

                case "lock":
                    output = (tokens.length < 2) ? usage("lock <IdentityName>") : handleLock(tokens[1], true);
                    break;

                case "unlock":
                    output = (tokens.length < 2) ? usage("unlock <IdentityName>") : handleLock(tokens[1], false);
                    break;

                case "resetpwd":
                case "resetpassword":
                    output = (tokens.length < 2) ? usage("resetpwd <IdentityName>") : handleResetPassword(tokens[1]);
                    break;

                case "reindex":
                    output = (tokens.length < 2)
                            ? usage("reindex <ObjectType>\nExample: reindex Identity")
                            : handleReindex(tokens[1]);
                    break;

                case "clear":
                    output = "CLEAR_CONSOLE";
                    break;

                case "exec":
                case "bsh":
                    output = (tokens.length < 2)
                            ? usage("exec <beanshell code>\nExample: exec return context.getObjectByName(Identity.class, \"spadmin\");")
                            : handleExec(cmd.substring(verb.length()).trim());
                    break;

                default:
                    output = "Unknown command: '" + verb + "'\nType 'help' to see all available commands.";
                    break;
            }

            success = !isFailureOutput(output);
            return output;

        } catch (Exception e) {
            failure = e;
            success = false;
            throw e;

        } finally {
            // Audit should never break console execution
            try {
            	System.out.println("#########################");
                Attributes attr = new Attributes();
                if (loggedUser != null) {
                    attr.put("Logged User", loggedUser.getName());
                    attr.put("Logged User DisplayName", loggedUser.getDisplayName());
                }
                attr.put("IIQ Console Command input", cmd);
                attr.put("IIQ Console Command status", success ? "Success" : "Fail");

                if (!success) {
                    if (failure != null) {
                        attr.put("IIQ Console Error", failure.getClass().getName() + ": " + failure.getMessage());
                    } else if (output != null) {
                        attr.put("IIQ Console Error", output.length() > 2000 ? output.substring(0, 2000) : output);
                    }
                }
                System.out.println("************************");
                auditEvent(ctx, attr);
                System.out.println("^^^^^^^^^^^^^^^^^^^^^^^^^");

            } catch (Throwable auditErr) {
                log.warn("Audit failed: " + auditErr.getMessage(), auditErr);
            }
        }
    }

    // ===================================================================
    // COMMAND HANDLERS
    // ===================================================================

    /**
     * help - Print all available commands
     */
    private String getHelp() {
        StringBuilder sb = new StringBuilder();
        sb.append("╔══════════════════════════════════════════════════════════╗\n");
        sb.append("║           IIQ Console Plugin - Available Commands         ║\n");
        sb.append("╚══════════════════════════════════════════════════════════╝\n\n");

        sb.append("OBJECT OPERATIONS:\n");
        sb.append("  list   <Type>              List all objects of a type\n");
        sb.append("  get    <Type> <name>        Get object XML\n");
        sb.append("  rm     <Type> <name>        Delete object (with dependency handling)\n");
        sb.append("  import <xml>               Import XML object\n");
        sb.append("  export <Type> <name>        Export object as XML\n");
        sb.append("  count  <Type>              Count objects of a type\n");
        sb.append("  search <Type> <filter>      Search with filter\n\n");

        sb.append("RULE & EXECUTION:\n");
        sb.append("  run    <RuleName>          Run a Rule by name\n");
        sb.append("  exec   <beanshell>         Execute raw BeanShell code\n\n");

        sb.append("IDENTITY OPERATIONS:\n");
        sb.append("  lock   <IdentityName>      Lock an Identity\n");
        sb.append("  unlock <IdentityName>      Unlock an Identity\n");
        sb.append("  resetpwd <IdentityName>    Reset Identity password\n\n");

        sb.append("SYSTEM:\n");
        sb.append("  reindex <Type>             Reindex object type\n");
        sb.append("  version                    Show IIQ version\n");
        sb.append("  clear                      Clear console\n");
        sb.append("  help                       Show this help\n\n");

        sb.append("SUPPORTED OBJECT TYPES:\n  ");
        sb.append(String.join(", ", SUPPORTED_TYPES));
        sb.append("\n\n");

        sb.append("EXAMPLES:\n");
        sb.append("  list Identity\n");
        sb.append("  list Application\n");
        sb.append("  get Rule CorrelationRule\n");
        sb.append("  rm TaskResult My Old Task Result\n");
        sb.append("  export Application MyApp\n");
        sb.append("  run MyBeanShellRule\n");
        sb.append("  exec return context.countObjects(Identity.class, null);\n");
        sb.append("  search Identity name.startsWith(\"john\")\n");
        sb.append("  count Bundle\n");

        return sb.toString();
    }

    /**
     * version - Show IIQ version
     */
    private String handleVersion() {
        String v = "Unknown";
        String build = null;

        try {
            v = sailpoint.Version.getVersion(); // 8.1

            URL u = sailpoint.Version.class.getResource("Version.class");
            URLConnection c = u.openConnection();
            if (c instanceof JarURLConnection) {
                Manifest m = ((JarURLConnection) c).getManifest();
                java.util.jar.Attributes a = m.getMainAttributes();

                String impl = a.getValue("Implementation-Version");
                if (impl != null && !impl.trim().isEmpty()) {
                    v = impl.trim();
                }

                build = a.getValue("Build"); // 573234b5be3-20201216-122000
            }
        } catch (Exception ignored) { }

        String full = (build != null && !build.trim().isEmpty())
                ? (v + " Build " + build.trim())
                : v;

        return "IdentityIQ Version: " + full + "\nIIQ Console Plugin v1.0";
    }

    /**
     * list <Type> [filter] - List all objects of the given type
     */
    private String handleList(String typeName, String filterStr) throws Exception {
        Class<? extends SailPointObject> clazz = resolveClass(typeName);
        if (clazz == null) {
            return "Unknown object type: " + typeName + "\nRun 'help' to see supported types.";
        }

        QueryOptions qo = new QueryOptions();
        qo.addOrdering("name", true);
        qo.setResultLimit(500); // cap to prevent huge output

        if (filterStr != null && !filterStr.trim().isEmpty()) {
            try {
                Filter f = Filter.compile(filterStr);
                qo.add(f);
            } catch (Exception e) {
                return "Invalid filter expression: " + e.getMessage();
            }
        }

        List<String> names = new ArrayList<>();
        try {
            Iterator<Object[]> it = ctx.search(clazz, qo, "name");
            while (it != null && it.hasNext()) {
                Object[] row = it.next();
                if (row != null && row[0] != null) {
                    names.add((String) row[0]);
                }
            }
        } catch (Exception e) {
            // Some types don't have a 'name' field - try id
            try {
                Iterator<Object[]> it = ctx.search(clazz, qo, "id");
                while (it != null && it.hasNext()) {
                    Object[] row = it.next();
                    if (row != null && row[0] != null) {
                        names.add((String) row[0]);
                    }
                }
            } catch (Exception e2) {
                return "ERROR listing " + typeName + ": " + e2.getMessage();
            }
        }

        if (names.isEmpty()) {
            return "No " + typeName + " objects found.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(names.size()).append(" ").append(typeName).append(" object(s)");
        if (names.size() >= 500) sb.append(" (capped at 500)");
        sb.append(":\n");
        sb.append("─".repeat(50)).append("\n");
        for (int i = 0; i < names.size(); i++) {
            sb.append(String.format("  [%3d]  %s%n", i + 1, names.get(i)));
        }
        return sb.toString();
    }

    /**
     * get <Type> <name> - Fetch and pretty-print an object as XML
     */
    private String prettyPrintXml(String rawXml) {
        if (rawXml == null) return null;

        try {
            // XmlTools exists in your identityiq.jar and formats nicely
            Document doc = XmlTools.getDocumentFromXmlString(rawXml);
            return XmlTools.getFormattedXml(doc);
        } catch (Exception e) {
            // If formatting fails, return raw so user still gets output
            return rawXml;
        }
    }
    private String handleGet(String typeName, String name) throws Exception {
        Class<? extends SailPointObject> clazz = resolveClass(typeName);
        if (clazz == null) {
            return "Unknown object type: " + typeName;
        }

        SailPointObject obj = ctx.getObjectByName(clazz, name);
        if (obj == null) {
            // Try by ID
            obj = ctx.getObjectById(clazz, name);
        }
        if (obj == null) {
            return typeName + " not found: " + name;
        }

        try {
        	String xml = obj.toXml();
        	return prettyPrintXml(xml);
        } catch (Exception e) {
            return "ERROR serializing object: " + e.getMessage();
        }
    }

    /**
     * rm <Type> <name> - Delete an object
     *
     * CRITICAL: This is why we need the console on UI.
     * Unlike a normal Rule, this directly calls ctx.removeObject()
     * which handles dependency cleanup just like the real console.
     */
    private String handleDelete(String typeName, String name) throws Exception {
        Class<? extends SailPointObject> clazz = resolveClass(typeName);
        if (clazz == null) {
            return "Unknown object type: " + typeName;
        }

        SailPointObject obj = ctx.getObjectByName(clazz, name);
        if (obj == null) {
            obj = ctx.getObjectById(clazz, name);
        }
        if (obj == null) {
            return typeName + " not found: '" + name + "'";
        }

        String objectId = obj.getId();
        String objectName = obj.getName();

        try {
            ctx.removeObject(obj);
            ctx.commitTransaction();
            return "✓ Deleted " + typeName + ": '" + objectName + "' (id=" + objectId + ")";
        } catch (Exception e) {
            ctx.rollbackTransaction();
            return "ERROR deleting " + typeName + " '" + objectName + "': " + e.getMessage()
                    + "\n\nHint: This object may have dependencies. Try removing dependent objects first,"
                    + "\nor use: exec context.removeObject(context.getObjectByName("
                    + typeName + ".class,\"" + name + "\")); context.commitTransaction();";
        }
    }

    /**
     * export <Type> <name> - Export object as XML string
     */
    private String handleExport(String typeName, String name) throws Exception {
        return handleGet(typeName, name); // Same as get - returns XML
    }

    /**
     * run <RuleName> - Execute a Rule by name
     */
    private String handleRunRule(String ruleName) throws Exception {
        Rule rule = ctx.getObjectByName(Rule.class, ruleName);
        if (rule == null) {
            return "Rule not found: '" + ruleName + "'";
        }

        try {
            Object result = ctx.runRule(rule, null);
            if (result == null) {
                return "Rule '" + ruleName + "' executed successfully.\nResult: null";
            }
            return "Rule '" + ruleName + "' executed successfully.\nResult: " + result.toString();
        } catch (Exception e) {
            return "ERROR running rule '" + ruleName + "': " + e.getMessage();
        }
    }

    /**
     * count <Type> - Count objects of a type
     */
    private String handleCount(String typeName) throws Exception {
        Class<? extends SailPointObject> clazz = resolveClass(typeName);
        if (clazz == null) {
            return "Unknown object type: " + typeName;
        }

        try {
            int count = ctx.countObjects(clazz, null);
            return "Count of " + typeName + ": " + count;
        } catch (Exception e) {
            return "ERROR counting " + typeName + ": " + e.getMessage();
        }
    }

    /**
     * search <Type> <filter> - Search objects by filter string
     */
    private String handleSearch(String typeName, String filterStr) throws Exception {
        Class<? extends SailPointObject> clazz = resolveClass(typeName);
        if (clazz == null) {
            return "Unknown object type: " + typeName;
        }

        Filter f;
        try {
            f = Filter.compile(filterStr);
        } catch (Exception e) {
            return "Invalid filter: " + filterStr + "\nError: " + e.getMessage()
                    + "\nExample filters:\n"
                    + "  name == \"John Doe\"\n"
                    + "  name.startsWith(\"john\")\n"
                    + "  inactive == true\n";
        }

        QueryOptions qo = new QueryOptions();
        qo.add(f);
        qo.addOrdering("name", true);
        qo.setResultLimit(200);

        List<String> results = new ArrayList<>();
        try {
            Iterator<Object[]> it = ctx.search(clazz, qo, Arrays.asList("name", "id"));
            while (it != null && it.hasNext()) {
                Object[] row = it.next();
                String name = row[0] != null ? (String) row[0] : "(no name)";
                String id = row[1] != null ? (String) row[1] : "";
                results.add(name + "  [id=" + id + "]");
            }
        } catch (Exception e) {
            return "ERROR searching " + typeName + ": " + e.getMessage();
        }

        if (results.isEmpty()) {
            return "No " + typeName + " objects matched filter: " + filterStr;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Found ").append(results.size()).append(" ").append(typeName).append(" match(es):\n");
        sb.append("─".repeat(60)).append("\n");
        for (String r : results) {
            sb.append("  ").append(r).append("\n");
        }
        return sb.toString();
    }

    /**
     * lock / unlock <Identity> - Lock or unlock an Identity account
     */
    private String handleLock(String identityName, boolean lock) throws Exception {
        Identity identity = ctx.getObjectByName(Identity.class, identityName);
        if (identity == null) {
            return "Identity not found: " + identityName;
        }

        try {
            identity.setInactive(lock);
            ctx.saveObject(identity);
            ctx.commitTransaction();
            return "✓ Identity '" + identityName + "' has been " + (lock ? "LOCKED" : "UNLOCKED");
        } catch (Exception e) {
            ctx.rollbackTransaction();
            return "ERROR " + (lock ? "locking" : "unlocking") + " identity: " + e.getMessage();
        }
    }

    /**
     * resetpwd <Identity> - Reset Identity password
     */
    private String handleResetPassword(String identityName) throws Exception {
        Identity identity = ctx.getObjectByName(Identity.class, identityName);
        if (identity == null) {
            return "Identity not found: " + identityName;
        }

        try {
            // Generate a secure random password
            String newPassword = generateSecurePassword();
            identity.setPassword(ctx.encrypt(newPassword));
            ctx.saveObject(identity);
            ctx.commitTransaction();
            return "✓ Password reset for '" + identityName + "'\nTemporary Password: " + newPassword
                    + "\n\n⚠ Please share this securely with the user.";
        } catch (Exception e) {
            ctx.rollbackTransaction();
            return "ERROR resetting password: " + e.getMessage();
        }
    }

    /**
     * reindex <Type> - Trigger re-indexing for an object type
     */
    private String handleReindex(String typeName) throws Exception {
    	log.debug("Initiating the handleReindex method()...."+typeName);
        Class<? extends SailPointObject> clazz = resolveClass(typeName);
        if (clazz == null) {
            return "Unknown object type: " + typeName;
        }

        try {
        	String value = clazz.getName(); // e.g., sailpoint.object.Identity
        	log.debug("handleReindex method() class name is...."+value);
            // Use BeanShell to invoke the internal reindex logic
        	String script =
        	        "import sailpoint.api.FullTextifier;\n" +
        	        "FullTextifier ftf = new FullTextifier(context);\n" +
        	        "Class c = Class.forName(\"" + value + "\");\n" +
        	        "ftf.reindex(c);\n" +
        	        "return \"Reindex started for " + clazz.getSimpleName() + "\";";
        	log.debug("handleReindex method() class final script is...."+script);
            return handleExec(script);
        } catch (Exception e) {
            return "ERROR during reindex of " + typeName + ": " + e.getMessage()
                    + "\nNote: Reindex may need to be triggered via Task instead.";
        }
    }

    /**
     * exec <beanshell> - Execute raw BeanShell code, we can look here bsh-2.1.8.jar
     *
     * This is MORE powerful than a normal Rule because:
     * - You don't need to save a Rule first
     * - Full access to context
     * - Can call commitTransaction() directly
     * - Multi-line scripts supported
     * exec import sailpoint.object.*; 
     * Identity i=context.getObjectByName(Identity.class,"spadmin");
     * return i.getDisplayName();
     */
    private String handleExec(String script) {
        if (Util.isNullOrEmpty(script)) {
            return "ERROR: No BeanShell code provided.";
        }

        try {
            Interpreter bsh = new Interpreter();
            // Inject the same variables available in real IIQ Rules
            bsh.set("context", ctx);
            bsh.set("log", LogFactory.getLog("IIQConsolePlugin.BeanShell"));

            // Capture stdout from bsh
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
            java.io.PrintStream ps = new java.io.PrintStream(baos, true, "UTF-8");
            bsh.setOut(ps);
            bsh.setErr(ps);
            Object result = bsh.eval(script);

            ps.flush();
            String capturedOutput = baos.toString("UTF-8");
            
            StringBuilder sb = new StringBuilder();

            if (!Util.isNullOrEmpty(capturedOutput)) {
                sb.append("Output:\n").append(capturedOutput).append("\n");
            }

            if (result != null) {
                sb.append("Return value: ").append(result.toString());
            } else {
                sb.append("Return value: null");
            }

            return sb.toString();

        } catch (bsh.EvalError ee) {
            return "BeanShell Error at line " + ee.getErrorLineNumber() + ": " + ee.getMessage()
                    + "\n\nScript:\n" + script;
        } catch (Exception e) {
            return "ERROR executing BeanShell: " + e.getClass().getSimpleName()
                    + ": " + e.getMessage();
        }
    }

    // ===================================================================
    // PRIVATE UTILITIES
    // ===================================================================

    /**
     * Resolves a type name string to the actual SailPoint class.
     * Case-insensitive matching.
     */
    @SuppressWarnings("unchecked")
    private Class<? extends SailPointObject> resolveClass(String typeName) {
        if (Util.isNullOrEmpty(typeName)) return null;

        // Try direct match first
        try {
            Class<?> c = Class.forName("sailpoint.object." + typeName);
            if (SailPointObject.class.isAssignableFrom(c)) {
                return (Class<? extends SailPointObject>) c;
            }
        } catch (ClassNotFoundException e) {
            // fall through to fuzzy match
        }

        // Case-insensitive match
        for (String supported : SUPPORTED_TYPES) {
            if (supported.equalsIgnoreCase(typeName)) {
                try {
                    Class<?> c = Class.forName("sailpoint.object." + supported);
                    if (SailPointObject.class.isAssignableFrom(c)) {
                        return (Class<? extends SailPointObject>) c;
                    }
                } catch (ClassNotFoundException e) {
                    // skip
                }
            }
        }

        return null;
    }

    /**
     * Returns a formatted usage hint string.
     */
    private String usage(String hint) {
        return "Usage: " + hint;
    }

    /**
     * Generates a secure random temporary password.
     */
    private String generateSecurePassword() {
        String chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789!@#$";
        StringBuilder sb = new StringBuilder();
        Random rnd = new Random();
        for (int i = 0; i < 12; i++) {
            sb.append(chars.charAt(rnd.nextInt(chars.length())));
        }
        return sb.toString();
    }
}
