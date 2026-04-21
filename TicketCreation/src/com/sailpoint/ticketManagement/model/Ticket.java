package com.sailpoint.ticketManagement.model;

import java.util.Date;

public class Ticket {

    private Long id;
    private String sourceType;
    private String applicationName;
    private String identityName;
    private String operation;
    private String errorMessage;
    private String aiSummary;
    private String status;
    private String assignedTo;
    private String requestId;
    private String resolutionNotes;
    private Date created;
    private Date resolved;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getSourceType() { return sourceType; }
    public void setSourceType(String sourceType) { this.sourceType = sourceType; }

    public String getApplicationName() { return applicationName; }
    public void setApplicationName(String applicationName) { this.applicationName = applicationName; }

    public String getIdentityName() { return identityName; }
    public void setIdentityName(String identityName) { this.identityName = identityName; }

    public String getOperation() { return operation; }
    public void setOperation(String operation) { this.operation = operation; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getAiSummary() { return aiSummary; }
    public void setAiSummary(String aiSummary) { this.aiSummary = aiSummary; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getAssignedTo() { return assignedTo; }
    public void setAssignedTo(String assignedTo) { this.assignedTo = assignedTo; }

    public String getRequestId() { return requestId; }
    public void setRequestId(String requestId) { this.requestId = requestId; }

    public String getResolutionNotes() { return resolutionNotes; }
    public void setResolutionNotes(String resolutionNotes) { this.resolutionNotes = resolutionNotes; }

    public Date getCreated() { return created; }
    public void setCreated(Date created) { this.created = created; }

    public Date getResolved() { return resolved; }
    public void setResolved(Date resolved) { this.resolved = resolved; }
}
