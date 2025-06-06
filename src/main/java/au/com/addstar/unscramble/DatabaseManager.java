package au.com.addstar.unscramble;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

public class DatabaseManager {
    private HikariDataSource dataSource;

    public DatabaseManager(String jdbcUrl, String jdbcUsername, String jdbcPassword) {
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(jdbcUrl);
        config.setUsername(jdbcUsername);
        config.setPassword(jdbcPassword);

        try {
            dataSource = new HikariDataSource(config);
        } catch (Exception e) {
            Unscramble.instance.getLogger().warning("Failed to establish database connection!");
            e.printStackTrace();
        }
    }

    public void close() {
        if (dataSource != null) {
            dataSource.close();
        }
    }

    public void saveRecord(PlayerRecord rec) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO players (playerid, totalpoints, points, wins) " +
                             "VALUES (?, ?, ?, ?) " +
                             "ON DUPLICATE KEY UPDATE " +
                             "totalpoints = VALUES(totalpoints), " +
                             "points = VALUES(points), " +
                             "wins = VALUES(wins)")) {
            statement.setString(1, rec.getId().toString());
            statement.setInt(2, rec.getTotalPoints());
            statement.setInt(3, rec.getPoints());
            statement.setInt(4, rec.getWins());

            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public void saveWin(UUID playerid, String word, int diff, int points, double duration) {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement(
                     "INSERT INTO wins (playerid, phrase, duration, difficulty, points) VALUES (?, ?, ?, ?, ?)")) {

            statement.setString(1, playerid.toString());
            statement.setString(2, word);
            statement.setDouble(3, duration);
            statement.setInt(4, diff);
            statement.setInt(5, points);

            statement.executeUpdate();
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    public PlayerRecord getRecord(UUID id) {
        try (Connection connection = dataSource.getConnection();
            PreparedStatement statement = connection.prepareStatement("SELECT * FROM players WHERE playerid = ?")) {
            statement.setObject(1, id.toString());

            try (ResultSet resultSet = statement.executeQuery()) {
                if (resultSet.next()) {
                    int totalpoints = resultSet.getInt("totalpoints");
                    int points = resultSet.getInt("points");
                    int wins = resultSet.getInt("wins");
                    return new PlayerRecord(id, totalpoints, points, wins);
                } else {
                    return new PlayerRecord(id, 0, 0, 0);
                }
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
        return null;
    }

    public static class PlayerRecord {
        private UUID id;
        private int points;
        private int totalpoints;
        private int wins;

        public PlayerRecord(UUID id, int totalpoints, int points, int wins) {
            this.id = id;
            this.points = points;
            this.totalpoints = totalpoints;
            this.wins = wins;
        }

        public UUID getId() {
            return id;
        }

        public int getPoints() {
            return points;
        }

        public int getTotalPoints() {
            return totalpoints;
        }

        public int getWins() {
            return wins;
        }

        public void setTotalpoints(int totalpoints) {
            this.totalpoints = totalpoints;
        }

        public void setWins(int wins) {
            this.wins = wins;
        }

        public void setPoints(int points) {
            this.points = points;
        }

        public PlayerRecord playerWin(int addpoints) {
            setTotalpoints(getTotalPoints() + addpoints);
            setPoints(getPoints() + addpoints);
            setWins(getWins() + 1);
            return this;
        }

        @Override
        public String toString() {
            return "Score{" +
                    "id=" + id +
                    ", totalpoints=" + totalpoints +
                    ", points=" + points +
                    ", wins=" + wins +
                    '}';
        }
    }
}
