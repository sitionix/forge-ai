ALTER TABLE ssh_connections
    ADD COLUMN auth_type VARCHAR(20) NOT NULL DEFAULT 'PRIVATE_KEY',
    ADD COLUMN password TEXT,
    ALTER COLUMN private_key_path DROP NOT NULL;

ALTER TABLE ssh_connections
    ALTER COLUMN auth_type DROP DEFAULT,
    ADD CONSTRAINT ssh_connection_authentication CHECK (
        (auth_type = 'PRIVATE_KEY' AND private_key_path IS NOT NULL AND password IS NULL)
        OR
        (auth_type = 'PASSWORD' AND private_key_path IS NULL AND password IS NOT NULL)
    );
