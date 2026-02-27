package com.sailpoint.iiqconsole;

import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Response;

import sailpoint.api.SailPointContext;
import sailpoint.api.SailPointFactory;
import sailpoint.object.Identity;
import sailpoint.rest.plugin.AllowAll;
import sailpoint.rest.plugin.BasePluginResource;
import sailpoint.rest.plugin.RequiredRight;

import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.apache.commons.logging.Log; 
import org.apache.commons.logging.LogFactory;

@RequiredRight("IIQConsolePluginAccess")
@Path("/iiqconsole")
@Produces(MediaType.APPLICATION_JSON)
public class ConsolePluginResource extends BasePluginResource {
	
	private static final Log log = LogFactory.getLog(ConsolePluginResource.class);

    @Override
    public String getPluginName() {
        return "IIQConsolePlugin";
    }
    
    @GET
    @Path("/whoami")
    @Produces(MediaType.APPLICATION_JSON)
    @AllowAll
    public Response whoami() {
      try {
    	  log.error("### IIQConsole whoami() HIT ###");
        SailPointContext ctx = SailPointFactory.getCurrentContext();
        String userName = getLoggedInUserName();

        Map<String, Object> resp = new LinkedHashMap<>();
        resp.put("userName", userName);

        Identity me = (userName == null) ? null : ctx.getObjectByName(Identity.class, userName);
        resp.put("identityFound", me != null);

        if (me != null) {
          resp.put("isSpAdmin","Test");
        }
        return Response.ok(resp).build();
      } catch (Exception e) {
        return Response.status(500).entity(e.getMessage()).build();
      }
    }


    @GET
    @Path("/ping")
    @AllowAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response ping() {
        try {
            SailPointContext ctx = SailPointFactory.getCurrentContext();
            String user = getLoggedInUserName(); // BasePluginResource method

            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("message", "IIQ Console Plugin is running");
            resp.put("user", user);
            resp.put("timestamp", new Date().toString());
            return Response.ok(resp).build();
        } catch (Exception e) {
            return Response.status(Response.Status.INTERNAL_SERVER_ERROR)
                    .entity(buildResponse("ERROR: " + e.getMessage(), "error"))
                    .build();
        }
    }

    @POST
    @Path("/execute")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    @AllowAll
    public Response executeCommand(Map<String, String> body) {
    	
    	log.debug("Initiating the executeCommand method()...");

        String command = (body != null) ? body.get("command") : null;
        if (command == null || command.trim().isEmpty()) {
            return Response.ok(buildResponse("ERROR: No command provided.", "error")).build();
        }

        String raw = command;
        String lower = raw.trim().toLowerCase();
        if (lower.startsWith("import ")) {
            // keep as-is except remove leading spaces before "import"
            command = raw.replaceFirst("^\\s+", "");
        } else {
            command = raw.trim();
        }

        if (isBlockedCommand(command)) {
            return Response.ok(buildResponse("ERROR: Command blocked for security reasons.", "error")).build();
        }

        try {
            SailPointContext ctx = SailPointFactory.getCurrentContext();

            if (!hasAdminRights(ctx)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(buildResponse("ERROR: Access denied. Need SystemAdministrator/SPAdmin.", "error"))
                        .build();
            }
            String userName = getLoggedInUserName();
            Identity loggedUser = (userName == null) ? null : ctx.getObjectByName(Identity.class, userName);

            CommandRouter router = new CommandRouter(ctx);
            String result = router.route(command, loggedUser);
            log.debug("result from the executeCommand method() is ..."+result+", for the executed query.."+command);
            return Response.ok(buildResponse(result, "ok")).build();

        } catch (Exception e) {
            return Response.ok(buildResponse("ERROR: " + e.getClass().getSimpleName()
                    + " - " + e.getMessage(), "error")).build();
        }
    }

    @GET
    @Path("/objecttypes")
    @AllowAll
    @Produces(MediaType.APPLICATION_JSON)
    public Response getObjectTypes() {
        try {
            SailPointContext ctx = SailPointFactory.getCurrentContext();
            if (!hasAdminRights(ctx)) {
                return Response.status(Response.Status.FORBIDDEN)
                        .entity(buildResponse("Forbidden", "error"))
                        .build();
            }

            List<String> types = CommandRouter.getSupportedObjectTypes();
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("status", "ok");
            resp.put("types", types);
            return Response.ok(resp).build();

        } catch (Exception e) {
            return Response.ok(buildResponse("ERROR: " + e.getMessage(), "error")).build();
        }
    }

    private boolean isBlockedCommand(String cmd) {
        String lower = cmd.toLowerCase().trim();
        return lower.startsWith("!") ||
                lower.startsWith("shell") ||
                lower.contains("runtime.exec") ||
                lower.contains("processbuilder");
    }

    private Map<String, Object> buildResponse(String output, String status) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("status", status);
        map.put("output", output);
        map.put("timestamp", System.currentTimeMillis());
        return map;
    }

    private boolean hasAdminRights(SailPointContext ctx) {
    	  try {
    	    String userName = getLoggedInUserName();
    	    if (userName == null) return false;

    	    Identity me = ctx.getObjectByName(Identity.class, userName);
    	    if (me == null) return false;

    	    // ✅ Allow either capability OR your plugin right
    	    boolean isCapAdmin = me.hasCapability("SystemAdministrator") || me.hasCapability("IIQConsolePluginAccessAdmin");

    	    return isCapAdmin;
    	  } catch (Exception e) {
    	    return false;
    	  }
    	}

}
