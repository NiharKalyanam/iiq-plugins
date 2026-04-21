package com.sailpoint.ticketManagement.service;

import com.sailpoint.ticketManagement.model.Ticket;
import sailpoint.api.SailPointContext;

/**
 * Call this service from workflow/rule code when a failure happens.
 * This is the event-driven entry point.
 */
public class TicketEventService {

    private final TicketService ticketService;

    public TicketEventService(SailPointContext context) {
        this.ticketService = new TicketService(context);
    }

    public long createFailureTicket(String sourceType,
                                    String applicationName,
                                    String identityName,
                                    String operation,
                                    String errorMessage,
                                    String requestId) {

        Ticket ticket = new Ticket();
        ticket.setSourceType(sourceType);
        ticket.setApplicationName(applicationName);
        ticket.setIdentityName(identityName);
        ticket.setOperation(operation);
        ticket.setErrorMessage(errorMessage);
        ticket.setRequestId(requestId);
        ticket.setStatus("OPEN");
        return ticketService.createTicket(ticket);
    }
}
