CREATE TABLE remote_access_switch (
    singleton BOOLEAN PRIMARY KEY CHECK (singleton),
    status VARCHAR(16) NOT NULL CHECK (status IN ('DISABLED', 'ENABLED', 'DISABLING')),
    version BIGINT NOT NULL CHECK (version >= 0)
);

INSERT INTO remote_access_switch (singleton, status, version) VALUES (TRUE, 'DISABLED', 0);
