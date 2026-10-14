package dev.regresslab.evaluation;
import java.sql.*;
public final class DatabaseLoop {
    public long total(Connection connection) throws SQLException {
        try (var query = connection.prepareStatement("SELECT sum(value) FROM eval_items WHERE id BETWEEN 1 AND 64")) {
            try (var rows = query.executeQuery()) { rows.next(); return rows.getLong(1); }
        }
    }
}
