package com.sailpoint.ticketManagement.dao;

import com.sailpoint.ticketManagement.model.TicketComment;
import sailpoint.api.SailPointContext;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.util.ArrayList;
import java.util.List;

public class TicketCommentDao {

    private final SailPointContext context;

    public TicketCommentDao(SailPointContext context) {
        this.context = context;
    }

    public List<TicketComment> listByTicketId(Long ticketId) {
        List<TicketComment> comments = new ArrayList<TicketComment>();

        String sql = "SELECT id, ticket_id, comment_by, comment_text, created " +
                "FROM iiq_ai_failure_ticket_comment " +
                "WHERE ticket_id = ? " +
                "ORDER BY created ASC";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql);
            ps.setLong(1, ticketId);

            rs = ps.executeQuery();
            while (rs.next()) {
                TicketComment comment = new TicketComment();
                comment.setId(rs.getLong("id"));
                comment.setTicketId(rs.getLong("ticket_id"));
                comment.setCommentBy(rs.getString("comment_by"));
                comment.setCommentText(rs.getString("comment_text"));
                comment.setCreated(rs.getTimestamp("created"));
                comments.add(comment);
            }

            return comments;
        } catch (Exception e) {
            throw new RuntimeException("Failed to list ticket comments", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
    }

    public long insert(TicketComment comment) {
        String sql = "INSERT INTO iiq_ai_failure_ticket_comment " +
                "(ticket_id, comment_by, comment_text, created) VALUES (?, ?, ?, ?)";

        Connection conn = null;
        PreparedStatement ps = null;
        ResultSet rs = null;

        try {
            conn = context.getConnection();
            ps = conn.prepareStatement(sql, new String[]{"id"});

            ps.setLong(1, comment.getTicketId());
            ps.setString(2, comment.getCommentBy());
            ps.setString(3, comment.getCommentText());
            ps.setTimestamp(4, new Timestamp(System.currentTimeMillis()));
            ps.executeUpdate();

            rs = ps.getGeneratedKeys();
            if (rs.next()) {
                return rs.getLong(1);
            }

            throw new RuntimeException("Comment created but generated key not returned");
        } catch (Exception e) {
            throw new RuntimeException("Failed to insert ticket comment", e);
        } finally {
            closeQuietly(rs);
            closeQuietly(ps);
        }
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