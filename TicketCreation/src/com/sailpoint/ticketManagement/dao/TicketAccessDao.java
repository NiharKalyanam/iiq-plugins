package com.sailpoint.ticketManagement.dao;

import sailpoint.api.SailPointContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;

public class TicketAccessDao {

    private final SailPointContext context;

    public TicketAccessDao(SailPointContext context) {
        this.context = context;
    }

    /**
     * Grant a user access to a specific ticket (ignore if already exists).
     */
    public void grantAccess(Long ticketId, String username) {
        String sql = "INSERT IGNORE INTO iiq_ai_failure_ticket_access (ticket_id, username) VALUES (?, ?)";
        Connection conn = null;
        PreparedStatement ps = null;
        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setLong(1, ticketId);
            ps.setString(2, username);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to grant ticket access", e);
        } finally {
            closeQuietly(ps);
        }
    }

    /**
     * Returns all ticket IDs this user has been granted access to via mention.
     */
    public List<Long> getAccessibleTicketIds(String username) {
        List<Long> ids = new ArrayList<Long>();
        String sql = "SELECT ticket_id FROM iiq_ai_failure_ticket_access WHERE username = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setString(1, username);
            rs = ps.executeQuery();
            while (rs.next()) {
                ids.add(rs.getLong("ticket_id"));
            }
            return ids;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get accessible ticket ids", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    /**
     * Check if a user has been granted access to a specific ticket.
     */
    public boolean hasAccess(Long ticketId, String username) {
        String sql = "SELECT COUNT(*) FROM iiq_ai_failure_ticket_access WHERE ticket_id = ? AND username = ?";
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;
        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setLong(1, ticketId);
            ps.setString(2, username);
            rs = ps.executeQuery();
            if (rs.next()) {
                return rs.getInt(1) > 0;
            }
            return false;
        } catch (Exception e) {
            throw new RuntimeException("Failed to check ticket access", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try { closeable.close(); } catch (Exception ignored) {}
        }
    }
}
