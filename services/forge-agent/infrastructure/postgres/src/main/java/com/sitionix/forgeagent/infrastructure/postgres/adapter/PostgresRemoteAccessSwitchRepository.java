package com.sitionix.forgeagent.infrastructure.postgres.adapter;

import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchState;
import com.sitionix.forgeagent.domain.model.RemoteAccessSwitchStatus;
import com.sitionix.forgeagent.domain.port.RemoteAccessSwitchRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
@RequiredArgsConstructor
public class PostgresRemoteAccessSwitchRepository implements RemoteAccessSwitchRepository {
    private final JdbcTemplate jdbc;

    @Override public RemoteAccessSwitchState get() {
        return jdbc.queryForObject("SELECT status, version FROM remote_access_switch WHERE singleton=TRUE",
                (row, number) -> new RemoteAccessSwitchState(
                        RemoteAccessSwitchStatus.valueOf(row.getString("status")), row.getLong("version")));
    }

    @Override public boolean transition(RemoteAccessSwitchState before, RemoteAccessSwitchState after) {
        RemoteAccessSwitchState expected = switch (after.status()) {
            case ENABLED -> before.enable();
            case DISABLING -> before.beginDisable();
            case DISABLED -> before.finishDisable();
        };
        if (!expected.equals(after)) throw new IllegalArgumentException("Invalid switch transition");
        return jdbc.update("""
                UPDATE remote_access_switch SET status=?, version=?
                WHERE singleton=TRUE AND status=? AND version=?
                """, after.status().name(), after.version(), before.status().name(), before.version()) == 1;
    }
}
