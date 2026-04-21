package com.sailpoint.ticketManagement.service;

import com.sailpoint.ticketManagement.dao.TicketAccessDao;
import com.sailpoint.ticketManagement.dao.TicketCommentDao;
import com.sailpoint.ticketManagement.model.TicketComment;
import com.sailpoint.ticketManagement.util.EmailUtil;
import com.sailpoint.ticketManagement.util.MentionUtil;
import sailpoint.api.SailPointContext;
import sailpoint.api.SailPointFactory;
import sailpoint.object.Capability;
import sailpoint.object.Filter;
import sailpoint.object.Identity;
import sailpoint.object.QueryOptions;

import java.util.List;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

public class TicketCommentService {

    private static final Log log = LogFactory.getLog(TicketCommentService.class);

    private final SailPointContext context;
    private final TicketCommentDao ticketCommentDao;
    private final TicketAccessDao ticketAccessDao;
    private final EmailUtil emailUtil;

    public TicketCommentService(SailPointContext context) {
        this.context = context;
        this.ticketCommentDao = new TicketCommentDao(context);
        this.ticketAccessDao = new TicketAccessDao(context);
        this.emailUtil = new EmailUtil(context);
    }

    public List<TicketComment> listComments(Long ticketId) {
        return ticketCommentDao.listByTicketId(ticketId);
    }

    public long addComment(TicketComment comment) {
        long id = ticketCommentDao.insert(comment);

        List<String> mentions = MentionUtil.extractMentions(comment.getCommentText());
        log.info("TicketCreation: addComment - extracted mentions: " + mentions);

        for (String mention : mentions) {
            Identity identity = resolveIdentity(mention);
            if (identity != null) {
                log.info("TicketCreation: resolved mention '" + mention + "' to username '" + identity.getName() + "'");

                ticketAccessDao.grantAccess(comment.getTicketId(), identity.getName());
                log.info("TicketCreation: granted ticket access - ticketId=" + comment.getTicketId() + " username=" + identity.getName());

                grantViewCapability(identity.getName());

                emailUtil.sendMentionEmail(comment.getTicketId(), identity.getName(), comment.getCommentText());
            } else {
                log.warn("TicketCreation: could not resolve mention '" + mention + "' to any IIQ identity");
            }
        }

        return id;
    }

    private void grantViewCapability(String username) {
        SailPointContext privateCtx = null;
        try {
            log.info("TicketCreation: grantViewCapability starting for username=" + username);
            privateCtx = SailPointFactory.createPrivateContext();

            Identity identity = privateCtx.getObjectByName(Identity.class, username);
            if (identity == null) {
                log.warn("TicketCreation: grantViewCapability - identity not found for username=" + username);
                return;
            }

            Capability cap = privateCtx.getObjectByName(Capability.class, "TicketCreationView");
            if (cap == null) {
                log.warn("TicketCreation: grantViewCapability - TicketCreationView capability not found in IIQ");
                return;
            }

            log.info("TicketCreation: found capability TicketCreationView id=" + cap.getId());

            List<Capability> existing = identity.getCapabilities();
            if (existing != null) {
                for (Capability c : existing) {
                    if ("TicketCreationView".equals(c.getName())) {
                        log.info("TicketCreation: " + username + " already has TicketCreationView, skipping");
                        return;
                    }
                }
            }

            identity.add(cap);
            privateCtx.saveObject(identity);
            privateCtx.commitTransaction();
            log.info("TicketCreation: successfully granted TicketCreationView to " + username);

        } catch (Exception e) {
            log.error("TicketCreation: grantViewCapability failed for " + username + " - " + e.getMessage(), e);
        } finally {
            if (privateCtx != null) {
                try {
                    SailPointFactory.releasePrivateContext(privateCtx);
                } catch (Exception ignored) {}
            }
        }
    }

    private Identity resolveIdentity(String mention) {
        if (mention == null || mention.trim().isEmpty()) return null;
        String trimmed = mention.trim();

        try {
            Identity byName = context.getObjectByName(Identity.class, trimmed);
            if (byName != null) {
                log.info("TicketCreation: resolveIdentity - found by username: " + trimmed);
                return byName;
            }

            QueryOptions qo = new QueryOptions();
            qo.addFilter(Filter.ignoreCase(Filter.eq("displayName", trimmed)));
            qo.addFilter(Filter.eq("workgroup", false));
            List<Identity> results = context.getObjects(Identity.class, qo);
            if (results != null && !results.isEmpty()) {
                log.info("TicketCreation: resolveIdentity - found by displayName: " + trimmed + " -> " + results.get(0).getName());
                return results.get(0);
            }

            QueryOptions qo2 = new QueryOptions();
            qo2.addFilter(Filter.ignoreCase(Filter.like("displayName", trimmed, Filter.MatchMode.ANYWHERE)));
            qo2.addFilter(Filter.eq("workgroup", false));
            List<Identity> partial = context.getObjects(Identity.class, qo2);
            if (partial != null && !partial.isEmpty()) {
                log.info("TicketCreation: resolveIdentity - found by partial displayName: " + trimmed + " -> " + partial.get(0).getName());
                return partial.get(0);
            }

            log.warn("TicketCreation: resolveIdentity - no identity found for: " + trimmed);

        } catch (Exception e) {
            log.error("TicketCreation: resolveIdentity failed for: " + trimmed, e);
        }

        return null;
    }
}