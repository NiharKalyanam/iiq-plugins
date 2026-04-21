package com.sailpoint.ticketManagement.model;

import java.util.Date;

public class TicketComment {

    private Long id;
    private Long ticketId;
    private String commentBy;
    private String commentText;
    private Date created;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getTicketId() { return ticketId; }
    public void setTicketId(Long ticketId) { this.ticketId = ticketId; }

    public String getCommentBy() { return commentBy; }
    public void setCommentBy(String commentBy) { this.commentBy = commentBy; }

    public String getCommentText() { return commentText; }
    public void setCommentText(String commentText) { this.commentText = commentText; }

    public Date getCreated() { return created; }
    public void setCreated(Date created) { this.created = created; }
}
