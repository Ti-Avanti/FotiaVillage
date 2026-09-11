package gg.fotia.fotiavillage.database;

import gg.fotia.fotiavillage.config.FotiaSettings.DatabaseSynchronous;
import org.sqlite.JDBC;

import java.io.File;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;
import java.util.Properties;

/** 仅由数据库队列线程访问的 JDBC 连接。 */
final class SqliteDatabase {
    private final File file;
    private Connection connection;

    SqliteDatabase(File file) {
        this.file = file;
    }

    void open(DatabaseSynchronous mode) {
        close();
        try {
            String url = "jdbc:sqlite:" + file.getAbsolutePath();
            connection = new JDBC().connect(url, new Properties());
            if (connection == null) throw new SQLException("SQLite JDBC library is unavailable");
            update("PRAGMA foreign_keys = ON");
            update("PRAGMA journal_mode = WAL");
            applySettings(mode);
            update("PRAGMA busy_timeout = 5000");
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    void applySettings(DatabaseSynchronous mode) {
        update("PRAGMA synchronous = " + mode.name());
    }

    void close() {
        if (connection == null) return;
        try {
            connection.close();
        } catch (SQLException ex) {
            throw failure(ex);
        } finally {
            connection = null;
        }
    }

    int update(String sql, Object... parameters) {
        try (PreparedStatement statement = prepare(sql, parameters)) {
            statement.execute();
            return statement.getUpdateCount();
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    <T> T query(String sql, RowReader<T> reader, Object... parameters) {
        try (PreparedStatement statement = prepare(sql, parameters);
             ResultSet result = statement.executeQuery()) {
            return reader.read(result);
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    void transaction(Runnable action) {
        try {
            if (!connection.getAutoCommit()) {
                action.run();
                return;
            }
            connection.setAutoCommit(false);
            try {
                action.run();
                connection.commit();
            } catch (RuntimeException | Error | SQLException ex) {
                try {
                    connection.rollback();
                } catch (SQLException rollback) {
                    ex.addSuppressed(rollback);
                }
                throw ex;
            } finally {
                connection.setAutoCommit(true);
            }
        } catch (SQLException ex) {
            throw failure(ex);
        }
    }

    private PreparedStatement prepare(String sql, Object... parameters) throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < parameters.length; i++) {
                Object value = parameters[i];
                statement.setObject(i + 1, value instanceof UUID ? value.toString() : value);
            }
            return statement;
        } catch (SQLException ex) {
            statement.close();
            throw ex;
        }
    }

    private IllegalStateException failure(SQLException ex) {
        return new IllegalStateException("SQLite operation failed", ex);
    }

    @FunctionalInterface
    interface RowReader<T> {
        T read(ResultSet result) throws SQLException;
    }
}
