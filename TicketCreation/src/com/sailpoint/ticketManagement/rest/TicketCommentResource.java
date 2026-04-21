package com.sailpoint.ticketManagement.rest;

import com.sailpoint.ticketManagement.model.ApiResponse;
import com.sailpoint.ticketManagement.model.TicketComment;
import com.sailpoint.ticketManagement.service.TicketCommentService;
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
@Path("ticketComments")
@Produces(MediaType.APPLICATION_JSON)
public class TicketCommentResource extends BasePluginResource {

    @Override
    public String getPluginName() {
        return "TicketCreation";
    }

    private TicketCommentService getService() {
        return new TicketCommentService(getContext());
    }

    @GET
    @Path("ping")
    public ApiResponse ping() {
        return ApiResponse.success("ticketComments pong");
    }

    @GET
    @Path("list")
    public List<TicketComment> list(@QueryParam("ticketId") Long ticketId) {
        return getService().listComments(ticketId);
    }

    @POST
    @Path("add")
    public ApiResponse add(@QueryParam("ticketId") Long ticketId,
                           @QueryParam("commentBy") String commentBy,
                           @QueryParam("commentText") String commentText) {
        TicketComment comment = new TicketComment();
        comment.setTicketId(ticketId);
        comment.setCommentBy(commentBy);
        comment.setCommentText(commentText);
        long id = getService().addComment(comment);
        return ApiResponse.success("Comment added", id);
    }
}