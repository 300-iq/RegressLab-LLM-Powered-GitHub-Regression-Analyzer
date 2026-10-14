package dev.regresslab.evaluation;
import java.sql.*;
public final class DatabaseLoop {
    public long total(Connection connection) throws SQLException {
        long sum = 0;
        for (int id = 1; id <= 64; id++) {
            try (var query = connection.prepareStatement("SELECT value FROM eval_items WHERE id = ?")) {
                query.setInt(1, id);
                try (var rows = query.executeQuery()) { rows.next(); sum += rows.getLong(1); }
            }
        }
        return sum;
    }
}
