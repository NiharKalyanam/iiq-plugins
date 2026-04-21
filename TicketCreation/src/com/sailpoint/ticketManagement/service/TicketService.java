package com.sailpoint.ticketManagement.service;

import com.sailpoint.ticketManagement.dao.TicketAccessDao;
import com.sailpoint.ticketManagement.dao.TicketCommentDao;
import com.sailpoint.ticketManagement.dao.TicketDao;
import com.sailpoint.ticketManagement.model.Ticket;
import com.sailpoint.ticketManagement.model.TicketComment;
import com.sailpoint.ticketManagement.util.AIClient;
import com.sailpoint.ticketManagement.util.ConfigUtil;
import com.sailpoint.ticketManagement.util.EmailUtil;
import com.sailpoint.ticketManagement.util.UserDetails;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import sailpoint.api.SailPointContext;
import sailpoint.object.Custom;
import sailpoint.tools.GeneralException;
import sailpoint.tools.Util;

import java.util.List;
import java.util.Map;

public class TicketService {

    private static final Log log = LogFactory.getLog(TicketService.class);

    private static final String CUSTOM_OBJECT_NAME = "Custom-Ticket-Creation";

    private final SailPointContext context;
    private final TicketDao ticketDao;
    private final TicketAccessDao ticketAccessDao;
    private final TicketCommentDao ticketCommentDao;
    private final AIClient aiClient;
    private final EmailUtil emailUtil;

    public TicketService(SailPointContext context) {
        this.context = context;
        this.ticketDao = new TicketDao(context);
        this.ticketAccessDao = new TicketAccessDao(context);
        this.ticketCommentDao = new TicketCommentDao(context);
        this.aiClient = new AIClient(context);
        this.emailUtil = new EmailUtil(context);
    }

    public long createTicket(Ticket ticket) {
        boolean aiEnabled = ConfigUtil.getBooleanConfig(context, CUSTOM_OBJECT_NAME, "aiEnabled", false);

        if (aiEnabled && ticket != null && isBlank(ticket.getAiSummary())) {
            ticket.setAiSummary(aiClient.analyzeFailure(ticket));
        }

        if (ticket != null && isBlank(ticket.getStatus())) {
            ticket.setStatus("OPEN");
        }

        long ticketId = ticketDao.insert(ticket);

        String defaultAssignmentGroup = ConfigUtil.getStringConfig(context, CUSTOM_OBJECT_NAME, "defaultAssignmentGroup");
        if (ticket != null && isBlank(ticket.getAssignedTo()) && !isBlank(defaultAssignmentGroup)) {
            ticketDao.assign(ticketId, defaultAssignmentGroup);

            if (ConfigUtil.getBooleanConfig(context, CUSTOM_OBJECT_NAME, "allowAssignmentEmails", false)) {
                emailUtil.sendAssignmentEmail(ticketId, defaultAssignmentGroup);
            }
        }

        return ticketId;
    }

    public List<Ticket> listTickets(String status) {
        return ticketDao.list(status);
    }

    public List<Ticket> listMyTickets(String username, String status) {
        List<Long> accessibleIds = ticketAccessDao.getAccessibleTicketIds(username);
        return ticketDao.listByIds(accessibleIds, status);
    }

    public Ticket getTicket(Long id) {
        return ticketDao.getById(id);
    }

    public Ticket getTicketIfAuthorized(Long id, String username) {
        if (!ticketAccessDao.hasAccess(id, username)) {
            return null;
        }
        return ticketDao.getById(id);
    }

    public String askAboutTicket(Long id, String question) {
        try {
            Ticket ticket = ticketDao.getById(id);
            if (ticket == null) {
                log.warn("askAboutTicket: ticket not found for id: " + id);
                return "Not available in this ticket";
            }

            List<TicketComment> comments = ticketCommentDao.listByTicketId(id);
            String enrichedContext = buildAdditionalAiContext(ticket);

            return aiClient.answerTicketQuestion(ticket, comments, question, enrichedContext);

        } catch (Exception e) {
            log.error("Error while answering question for ticket id: " + id, e);
            return "Not available in this ticket";
        }
    }

    public String askAboutTicketIfAuthorized(Long id, String username, String question) {
        try {
            if (!ticketAccessDao.hasAccess(id, username)) {
                log.warn("Unauthorized ticket access attempt. ticketId=" + id + ", username=" + username);
                return "Not available in this ticket";
            }

            Ticket ticket = ticketDao.getById(id);
            if (ticket == null) {
                log.warn("askAboutTicketIfAuthorized: ticket not found for id: " + id);
                return "Not available in this ticket";
            }

            List<TicketComment> comments = ticketCommentDao.listByTicketId(id);
            String enrichedContext = buildAdditionalAiContext(ticket);

            return aiClient.answerTicketQuestion(ticket, comments, question, enrichedContext);

        } catch (Exception e) {
            log.error("Error while answering authorized question for ticket id: " + id + ", username=" + username, e);
            return "Not available in this ticket";
        }
    }

    public void assignTicket(Long id, String assignedTo) {
        ticketDao.assign(id, assignedTo);

        if (ConfigUtil.getBooleanConfig(context, CUSTOM_OBJECT_NAME, "allowAssignmentEmails", false)) {
            emailUtil.sendAssignmentEmail(id, assignedTo);
        }
    }

    public void resolveTicket(Long id, String resolutionNotes) {
        ticketDao.resolve(id, resolutionNotes);
    }

    private String buildAdditionalAiContext(Ticket ticket) {
        StringBuilder contextBuilder = new StringBuilder();

        if (ticket == null) {
            return "";
        }

        String identityName = ticket.getIdentityName();
        if (isBlank(identityName)) {
            log.debug("No identityName available on ticket id: " + ticket.getId());
            return "";
        }

        try {
            UserDetails userDetails = new UserDetails(context);
            Custom customObject = context.getObjectByName(Custom.class, CUSTOM_OBJECT_NAME);

            appendSection(contextBuilder, "User Information",
                getMessageValue(userDetails.getUserInformation(identityName, customObject)));

            appendSection(contextBuilder, "Pending Requests",
                getMessageValue(userDetails.getUsersPendingRequests(identityName)));

            appendSection(contextBuilder, "Account / Link Information",
                getMessageValue(userDetails.getUserLinkInformation(identityName)));

            Map<String, Object> entitlementResponse = userDetails.getUserEntitlementInformation(identityName);
            Object entitlementList = entitlementResponse.get("userEntitlementInformation");

            if (entitlementList != null) {
                appendSection(contextBuilder, "User Entitlements", String.valueOf(entitlementList));
            } else {
                appendSection(contextBuilder, "User Entitlements", getMessageValue(entitlementResponse));
            }

        } catch (GeneralException e) {
            log.error("Error while building additional AI context for identity: " + identityName, e);
        } catch (Exception e) {
            log.error("Unexpected error while building additional AI context for identity: " + identityName, e);
        }

        return contextBuilder.toString().trim();
    }

    private void appendSection(StringBuilder builder, String title, String content) {
        if (builder == null || isBlank(title) || isBlank(content)) {
            return;
        }

        if (builder.length() > 0) {
            builder.append("\n\n");
        }

        builder.append(title).append(":\n").append(content);
    }

    private String getMessageValue(Map<String, Object> responseMap) {
        if (responseMap == null || responseMap.isEmpty()) {
            return "";
        }

        Object message = responseMap.get("message");
        if (message != null && !isBlank(String.valueOf(message))) {
            return String.valueOf(message);
        }

        return "";
    }

    private boolean isBlank(String value) {
        return Util.isEmpty(value) || value.trim().length() == 0;
    }
}