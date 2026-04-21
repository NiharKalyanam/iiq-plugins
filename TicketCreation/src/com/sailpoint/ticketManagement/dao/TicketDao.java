package com.sailpoint.ticketManagement.dao;

import com.sailpoint.ticketManagement.model.Ticket;
import sailpoint.api.SailPointContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class TicketDao {

    private final SailPointContext context;

    public TicketDao(SailPointContext context) {
        this.context = context;
    }

    public long insert(Ticket ticket) {
        String sql = "INSERT INTO iiq_ai_failure_ticket " +
                "(source_type, application_name, identity_name, operation, error_message, ai_summary, status, assigned_to, request_id, created) " +
                "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql, new String[]{"id"});

            ps.setString(1, ticket.getSourceType());
            ps.setString(2, ticket.getApplicationName());
            ps.setString(3, ticket.getIdentityName());
            ps.setString(4, ticket.getOperation());
            ps.setString(5, ticket.getErrorMessage());
            ps.setString(6, ticket.getAiSummary());
            ps.setString(7, ticket.getStatus());
            ps.setString(8, ticket.getAssignedTo());
            ps.setString(9, ticket.getRequestId());
            ps.setTimestamp(10, new Timestamp(System.currentTimeMillis()));

            ps.executeUpdate();

            rs = ps.getGeneratedKeys();
            if (rs.next()) {
                return rs.getLong(1);
            }

            throw new RuntimeException("Ticket created but generated key not returned");
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert ticket", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public List<Ticket> list(String status) {
        String sql = "SELECT id, source_type, application_name, identity_name, operation, error_message, ai_summary, status, assigned_to, request_id, created, resolved, resolution_notes " +
                "FROM iiq_ai_failure_ticket " +
                ((status != null && !status.trim().isEmpty()) ? "WHERE status = ? " : "") +
                "ORDER BY created DESC";

        List<Ticket> tickets = new ArrayList<Ticket>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);

            if (status != null && !status.trim().isEmpty()) {
                ps.setString(1, status);
            }

            rs = ps.executeQuery();
            while (rs.next()) {
                tickets.add(mapRow(rs));
            }
            return tickets;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list tickets", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    /**
     * Returns only tickets whose IDs are in the given list.
     * Used for mentioned-only users who do not have full admin access.
     */
    public List<Ticket> listByIds(List<Long> ids, String status) {
        if (ids == null || ids.isEmpty()) {
            return new ArrayList<Ticket>();
        }

        StringBuilder placeholders = new StringBuilder();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) placeholders.append(",");
            placeholders.append("?");
        }

        String statusClause = (status != null && !status.trim().isEmpty()) ? " AND status = ?" : "";
        String sql = "SELECT id, source_type, application_name, identity_name, operation, error_message, ai_summary, status, assigned_to, request_id, created, resolved, resolution_notes " +
                "FROM iiq_ai_failure_ticket " +
                "WHERE id IN (" + placeholders + ")" + statusClause +
                " ORDER BY created DESC";

        List<Ticket> tickets = new ArrayList<Ticket>();
        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);

            int idx = 1;
            for (Long id : ids) {
                ps.setLong(idx++, id);
            }
            if (status != null && !status.trim().isEmpty()) {
                ps.setString(idx, status);
            }

            rs = ps.executeQuery();
            while (rs.next()) {
                tickets.add(mapRow(rs));
            }
            return tickets;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list tickets by ids", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public Ticket getById(Long id) {
        String sql = "SELECT id, source_type, application_name, identity_name, operation, error_message, ai_summary, status, assigned_to, request_id, created, resolved, resolution_notes " +
                "FROM iiq_ai_failure_ticket WHERE id = ?";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setLong(1, id);

            rs = ps.executeQuery();
            if (rs.next()) {
                return mapRow(rs);
            }
            return null;
        } catch (Exception e) {
            throw new RuntimeException("Failed to get ticket", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public void assign(Long id, String assignedTo) {
        String sql = "UPDATE iiq_ai_failure_ticket SET assigned_to = ?, status = ? WHERE id = ?";

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);

            ps.setString(1, assignedTo);
            ps.setString(2, "ASSIGNED");
            ps.setLong(3, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to assign ticket", e);
        } finally {
            closeQuietly(ps);
        }
    }

    public void resolve(Long id, String resolutionNotes) {
        String sql = "UPDATE iiq_ai_failure_ticket SET status = ?, resolved = ?, resolution_notes = ? WHERE id = ?";

        Connection conn = null;
        PreparedStatement ps = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);

            ps.setString(1, "RESOLVED");
            ps.setTimestamp(2, new Timestamp(System.currentTimeMillis()));
            ps.setString(3, resolutionNotes);
            ps.setLong(4, id);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve ticket", e);
        } finally {
            closeQuietly(ps);
        }
    }

    private Ticket mapRow(ResultSet rs) throws Exception {
        Ticket t = new Ticket();
        t.setId(rs.getLong("id"));
        t.setSourceType(rs.getString("source_type"));
        t.setApplicationName(rs.getString("application_name"));
        t.setIdentityName(rs.getString("identity_name"));
        t.setOperation(rs.getString("operation"));
        t.setErrorMessage(rs.getString("error_message"));
        t.setAiSummary(rs.getString("ai_summary"));
        t.setStatus(rs.getString("status"));
        t.setAssignedTo(rs.getString("assigned_to"));
        t.setRequestId(rs.getString("request_id"));
        t.setCreated(rs.getTimestamp("created"));
        t.setResolved(rs.getTimestamp("resolved"));
        t.setResolutionNotes(rs.getString("resolution_notes"));
        return t;
    }

    private void closeQuietly(AutoCloseable closeable) {
        if (closeable != null) {
            try {
                closeable.close();
            } catch (Exception ignored) {
            }
        }
    }
}
