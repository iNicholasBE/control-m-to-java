package io.example.nightlyclose.chain;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import javax.sql.DataSource;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Your code: the Control-M mutex (exclusive control resource) on the positions dataset. A Postgres session-level
 * advisory lock held on a dedicated connection for the life of the job execution. If the JVM dies, the
 * connection dies and Postgres releases the lock: no stale lock to clean up by hand.
 */
@Component
public class DatasetMutex {

    private static final Logger log = LoggerFactory.getLogger(DatasetMutex.class);
    private static final long LOCK_KEY = 0x4E43_4C53L; // "NCLS": the nightly-close positions dataset

    private final DataSource dataSource;
    private final Map<Long, Connection> held = new ConcurrentHashMap<>();

    public DatasetMutex(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public void acquire(long jobExecutionId) {
        try {
            Connection connection = dataSource.getConnection();
            boolean logged = false;
            while (!tryLock(connection)) {
                if (!logged) {
                    log.info("Job execution {} waiting for the positions dataset mutex", jobExecutionId);
                    logged = true;
                }
                Thread.sleep(1000);
            }
            held.put(jobExecutionId, connection);
        } catch (SQLException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    public void release(long jobExecutionId) {
        Connection connection = held.remove(jobExecutionId);
        if (connection == null) {
            return;
        }
        try (connection; PreparedStatement unlock = connection.prepareStatement("SELECT pg_advisory_unlock(?)")) {
            unlock.setLong(1, LOCK_KEY);
            unlock.execute();
        } catch (SQLException e) {
            log.warn("Could not release dataset mutex cleanly; closing the connection releases it", e);
        }
    }

    private boolean tryLock(Connection connection) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT pg_try_advisory_lock(?)")) {
            statement.setLong(1, LOCK_KEY);
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                return rs.getBoolean(1);
            }
        }
    }
}
