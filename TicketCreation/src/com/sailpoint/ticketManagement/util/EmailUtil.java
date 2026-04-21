package com.sailpoint.ticketManagement.util;

import com.sailpoint.ticketManagement.dao.TicketDao;
import com.sailpoint.ticketManagement.model.Ticket;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import sailpoint.api.ObjectUtil;
import sailpoint.api.SailPointContext;
import sailpoint.object.Custom;
import sailpoint.object.EmailOptions;
import sailpoint.object.EmailTemplate;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.object.QueryOptions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class EmailUtil {

    private static final Log log = LogFactory.getLog(EmailUtil.class);

    private static final String CUSTOM_OBJECT_NAME = "Custom-Ticket-Creation";
    private static final String DEFAULT_MENTION_TEMPLATE = "Ticket Creation Mention Notification";
    private static final String DEFAULT_ASSIGN_TEMPLATE  = "Ticket Creation Assignment Notification";

    private final SailPointContext context;
    private final TicketDao ticketDao;

    public EmailUtil(SailPointContext context) {
        this.context = context;
        this.ticketDao = new TicketDao(context);
    }

    public void sendMentionEmail(Long ticketId, String mentionedUsername, String commentText) {
        try {
            log.info("sendMentionEmail starting - ticketId=" + ticketId + ", user=" + mentionedUsername);

            String templateName = readCustomValue("mentionEmailTemplate");
            if (isBlank(templateName)) {
                templateName = DEFAULT_MENTION_TEMPLATE;
            }

            EmailTemplate template = context.getObjectByName(EmailTemplate.class, templateName);
            if (template == null) {
                log.warn("Mention template not found: " + templateName);
                return;
            }

            Identity mentionedIdentity = resolveIdentityOrWorkgroup(mentionedUsername);
            if (mentionedIdentity == null || isBlank(mentionedIdentity.getEmail())) {
                log.warn("Mention user not found or no email: " + mentionedUsername);
                return;
            }

            Ticket ticket = ticketDao.getById(ticketId);
            if (ticket == null) return;

            EmailTemplate cloned = template;

            List<String> recipients = new ArrayList<String>();
            recipients.add(mentionedIdentity.getEmail());

            Map<String, Object> args = buildTicketArgs(ticket);
            args.put("mentionedUser", safe(mentionedIdentity.getDisplayName(), mentionedUsername));
            args.put("commentText", commentText != null ? commentText : "");
            args.put("pluginUrl", buildPluginUrl());

            EmailOptions opts = new EmailOptions(recipients, args);
            context.sendEmailNotification(cloned, opts);

            log.info("Mention email sent to: " + mentionedIdentity.getEmail());

        } catch (Exception e) {
            log.error("sendMentionEmail failed for " + mentionedUsername, e);
        }
    }

    public void sendAssignmentEmail(Long ticketId, String assignedTo) {
        try {
            log.info("sendAssignmentEmail starting - ticketId=" + ticketId + ", assignedTo=" + assignedTo);

            if (!isAssignmentEmailEnabled()) {
                log.info("Assignment emails are disabled.");
                return;
            }

            Ticket ticket = ticketDao.getById(ticketId);
            if (ticket == null) {
                log.warn("Ticket not found for id: " + ticketId);
                return;
            }

            String templateName = readCustomValue("assignmentEmailTemplate");
            if (isBlank(templateName)) {
                templateName = DEFAULT_ASSIGN_TEMPLATE;
            }

            EmailTemplate template = context.getObjectByName(EmailTemplate.class, templateName);
            if (template == null) {
                log.warn("Assignment template not found: " + templateName);
                return;
            }

            Identity assignedIdentity = resolveIdentityOrWorkgroup(assignedTo);
            if (assignedIdentity == null) {
                log.warn("Assigned identity/workgroup not found: " + assignedTo);
                return;
            }

            if (assignedIdentity.isWorkgroup()) {
                sendToWorkgroup(template, ticket, assignedIdentity, assignedTo);
            } else {
                sendToUser(template, ticket, assignedIdentity);
            }

        } catch (Exception e) {
            log.error("sendAssignmentEmail failed for " + assignedTo, e);
        }
    }

    private void sendToUser(EmailTemplate template, Ticket ticket, Identity user) {
        try {
            String email = user.getEmail();
            if (isBlank(email)) {
                log.warn("User has no email: " + user.getName());
                return;
            }

            EmailTemplate cloned = template;

            List<String> recipients = new ArrayList<String>();
            recipients.add(email);

            Map<String, Object> args = buildTicketArgs(ticket);
            args.put("assignedUser", safe(user.getDisplayName(), user.getName()));
            args.put("pluginUrl", buildPluginUrl());

            EmailOptions opts = new EmailOptions(recipients, args);
            context.sendEmailNotification(cloned, opts);

            log.info("Email sent to user: " + email);

        } catch (Exception e) {
            log.error("Error sending email to user", e);
        }
    }

    private void sendToWorkgroup(EmailTemplate template, Ticket ticket, Identity workgroup, String assignedTo) {
        Iterator<?> members = null;
        boolean sent = false;

        try {
            log.info("Resolving workgroup members for: " + assignedTo);

            members = ObjectUtil.getWorkgroupMembers(context, workgroup, null);

            while (members != null && members.hasNext()) {
                Object[] row = (Object[]) members.next();
                if (row == null || row.length == 0) continue;

                Identity member = (Identity) row[0];
                if (member == null) continue;

                String email = member.getEmail();
                if (isBlank(email)) {
                    log.warn("Skipping member without email: " + member.getName());
                    continue;
                }

                try {
                    EmailTemplate cloned = template;

                    List<String> recipients = new ArrayList<String>();
                    recipients.add(email);

                    Map<String, Object> args = buildTicketArgs(ticket);
                    args.put("assignedUser", safe(member.getDisplayName(), member.getName()));
                    args.put("pluginUrl", buildPluginUrl());

                    EmailOptions opts = new EmailOptions(recipients, args);
                    context.sendEmailNotification(cloned, opts);

                    log.info("Email sent to workgroup member: " + email);
                    sent = true;

                } catch (Exception ex) {
                    log.error("Error sending to member: " + email, ex);
                }
            }

            if (!sent) {
                log.warn("No emails sent for workgroup: " + assignedTo);
            }

        } catch (Exception e) {
            log.error("Error resolving workgroup: " + assignedTo, e);
        } finally {
            if (members != null) {
                sailpoint.tools.Util.flushIterator(members);
            }
        }
    }

    private Identity resolveIdentityOrWorkgroup(String name) {
        if (isBlank(name)) return null;

        try {
            Identity id = context.getObjectByName(Identity.class, name);
            if (id != null) return id;

            QueryOptions qo = new QueryOptions();
            qo.addFilter(Filter.ignoreCase(Filter.eq("displayName", name)));
            List<Identity> res = context.getObjects(Identity.class, qo);
            if (res != null && !res.isEmpty()) return res.get(0);

        } catch (Exception e) {
            log.error("Error resolving identity: " + name, e);
        }

        return null;
    }

    private Map<String, Object> buildTicketArgs(Ticket t) {
        Map<String, Object> args = new HashMap<String, Object>();
        args.put("ticketId", t.getId() != null ? String.valueOf(t.getId()) : "");
        args.put("applicationName", safe(t.getApplicationName()));
        args.put("identityName", safe(t.getIdentityName()));
        args.put("operation", safe(t.getOperation()));
        args.put("status", safe(t.getStatus()));
        args.put("assignedTo", safe(t.getAssignedTo()));
        args.put("sourceType", safe(t.getSourceType()));
        args.put("requestId", safe(t.getRequestId()));

        String raw = safe(t.getErrorMessage());
        String affected = "";
        String error = raw;

        if (raw.startsWith("Access:") && raw.contains(" | Error:")) {
            int idx = raw.indexOf(" | Error:");
            affected = raw.substring(7, idx).trim().replace("\\n", "\n");
            error = raw.substring(idx + 9).trim();
        }

        args.put("affectedAccess", affected);
        args.put("errorMessage", error);

        return args;
    }

    private boolean isAssignmentEmailEnabled() {
        String val = readCustomValue("allowAssignmentEmails");
        return val == null || "true".equalsIgnoreCase(val);
    }

    private String readCustomValue(String key) {
        try {
            Custom c = context.getObjectByName(Custom.class, CUSTOM_OBJECT_NAME);
            if (c == null) return null;
            Object v = c.get(key);
            return v != null ? String.valueOf(v) : null;
        } catch (Exception e) {
            log.warn("Error reading custom value: " + key);
            return null;
        }
    }

    private String safe(String v) {
        return v != null ? v : "";
    }

    private String safe(String v, String fallback) {
        return !isBlank(v) ? v : fallback;
    }

    private boolean isBlank(String v) {
        return v == null || v.trim().isEmpty();
    }

    private String buildPluginUrl() {
        try {
            sailpoint.object.Configuration cfg =
                context.getObjectByName(sailpoint.object.Configuration.class, "SystemConfiguration");

            if (cfg != null) {
                String base = (String) cfg.get("serverRootPath");
                if (!isBlank(base)) {
                    return base.replaceAll("/$", "") + "/plugins/pluginPage.jsf?pn=TicketCreation";
                }
            }
        } catch (Exception e) {
            log.warn("Unable to build plugin URL", e);
        }
        return "";
    }
}