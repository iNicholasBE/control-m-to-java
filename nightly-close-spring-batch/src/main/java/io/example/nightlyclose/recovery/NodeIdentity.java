package io.example.nightlyclose.recovery;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Who am I, and am I alive? Spring Batch records no owner and no heartbeat for a running JobExecution, so after
 * a crash nobody can tell "still running on another node" from "died with the JVM". This table is that missing
 * piece. A node name is stable across restarts (host + port); the node id changes with every JVM start.
 */
@Component
public class NodeIdentity {

    private final JdbcTemplate jdbc;
    private final String nodeName;
    private final String nodeId = UUID.randomUUID().toString();

    public NodeIdentity(JdbcTemplate jdbc, @Value("${chain.node-name:${HOSTNAME:localhost}-${server.port:8080}}") String nodeName) {
        this.jdbc = jdbc;
        this.nodeName = nodeName;
        jdbc.update("""
                INSERT INTO chain_nodes (node_id, node_name, started_at, last_heartbeat)
                VALUES (?, ?, clock_timestamp(), clock_timestamp())""", nodeId, nodeName);
    }

    public String nodeName() {
        return nodeName;
    }

    public String nodeId() {
        return nodeId;
    }

    @Scheduled(fixedDelayString = "${chain.recovery.heartbeat:5s}")
    public void heartbeat() {
        jdbc.update("UPDATE chain_nodes SET last_heartbeat = clock_timestamp() WHERE node_id = ?", nodeId);
    }
}
