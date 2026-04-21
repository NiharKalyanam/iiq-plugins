package com.sailpoint.ticketManagement.rest;

import com.sailpoint.ticketManagement.model.ApiResponse;
import com.sailpoint.ticketManagement.model.Ticket;
import com.sailpoint.ticketManagement.service.TicketService;
import sailpoint.api.SailPointContext;
import sailpoint.rest.plugin.BasePluginResource;
import sailpoint.rest.plugin.RequiredRight;

import javax.ws.rs.GET;
import javax.ws.rs.POST;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.QueryParam;
import javax.ws.rs.core.MediaType;
import java.util.List;

@RequiredRight("TicketCreationView")
@Path("tickets")
@Produces(MediaType.APPLICATION_JSON)
public class TicketResource extends BasePluginResource {

    @Override
    public String getPluginName() {
        return "TicketCreation";
    }

    private TicketService getService() {
        return new TicketService(getContext());
    }

    @GET
    @Path("ping")
    public ApiResponse ping() {
        return ApiResponse.success("tickets pong");
    }

    @GET
    @Path("health")
    public ApiResponse health() {
        return ApiResponse.success("Ticket API is up");
    }

    @GET
    @Path("me")
    public ApiResponse me() {
        try {
            sailpoint.object.Identity identity = getLoggedInUser();
            if (identity == null) return ApiResponse.success("Unknown");
            String display = identity.getDisplayName();
            if (display == null || display.trim().isEmpty()) display = identity.getName();
            if (display == null || display.trim().isEmpty()) display = "N/A";
            return ApiResponse.success(display);
        } catch (Exception e) {
            return ApiResponse.success("N/A");
        }
    }

    /**
     * Admins (TicketCreationView + TicketCreationAdmin capability) see all tickets.
     * Mentioned users (TicketCreationView only) see only their granted tickets.
     */
    @GET
    @Path("list")
    public List<Ticket> list(@QueryParam("status") String status) {
        String username = getCurrentUsername();
        if (isAdmin()) {
            return getService().listTickets(status);
        }
        return getService().listMyTickets(username, status);
    }

    /**
     * Admins see any ticket.
     * Mentioned users only see tickets they were granted access to.
     */
    @GET
    @Path("get")
    public Ticket get(@QueryParam("id") Long id) {
        String username = getCurrentUsername();
        if (isAdmin()) {
            return getService().getTicket(id);
        }
        return getService().getTicketIfAuthorized(id, username);
    }
    
    @POST
    @Path("ask")
    public ApiResponse ask(@QueryParam("id") Long id,
                           @QueryParam("question") String question) {
        String answer;
        if (isAdmin()) {
            answer = getService().askAboutTicket(id, question);
        } else {
            answer = getService().askAboutTicketIfAuthorized(id, getCurrentUsername(), question);
        }
        return ApiResponse.success(answer);
    }

    @POST
    @Path("create")
    public ApiResponse create(@QueryParam("sourceType") String sourceType,
                              @QueryParam("applicationName") String applicationName,
                              @QueryParam("identityName") String identityName,
                              @QueryParam("operation") String operation,
                              @QueryParam("errorMessage") String errorMessage,
                              @QueryParam("status") String status,
                              @QueryParam("assignedTo") String assignedTo,
                              @QueryParam("requestId") String requestId) {
        Ticket ticket = new Ticket();
        ticket.setSourceType(sourceType);
        ticket.setApplicationName(applicationName);
        ticket.setIdentityName(identityName);
        ticket.setOperation(operation);
        ticket.setErrorMessage(errorMessage);
        ticket.setStatus(status);
        ticket.setAssignedTo(assignedTo);
        ticket.setRequestId(requestId);
        long id = getService().createTicket(ticket);
        return ApiResponse.success("Ticket created", id);
    }

    @POST
    @Path("assign")
    public ApiResponse assign(@QueryParam("id") Long id,
                              @QueryParam("assignedTo") String assignedTo) {
        getService().assignTicket(id, assignedTo);
        return ApiResponse.success("Ticket assigned");
    }

    @POST
    @Path("resolve")
    public ApiResponse resolve(@QueryParam("id") Long id,
                               @QueryParam("resolutionNotes") String resolutionNotes) {
        getService().resolveTicket(id, resolutionNotes);
        return ApiResponse.success("Ticket resolved");
    }

    private String getCurrentUsername() {
        try {
            sailpoint.object.Identity identity = getLoggedInUser();
            return identity != null ? identity.getName() : "unknown";
        } catch (Exception e) {
            return "unknown";
        }
    }

    /**
     * Admins have the TicketCreationAdmin capability.
     * Mentioned-only users have TicketCreationView but NOT TicketCreationAdmin.
     */
    private boolean isAdmin() {
        try {
            sailpoint.object.Identity identity = getLoggedInUser();
            if (identity == null) return false;
            java.util.List<sailpoint.object.Capability> caps =
                identity.getCapabilityManager().getEffectiveCapabilities();
            if (caps != null) {
                for (sailpoint.object.Capability cap : caps) {
                    if ("TicketCreationAdmin".equals(cap.getName())) return true;
                }
            }
            return false;
        } catch (Exception e) {
            return false;
        }
    }
}