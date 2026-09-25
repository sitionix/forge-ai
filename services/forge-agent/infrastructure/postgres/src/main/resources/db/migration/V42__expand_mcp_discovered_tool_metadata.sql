ALTER TABLE mcp_discovered_tools
    ALTER COLUMN tool_name TYPE TEXT,
    ALTER COLUMN description TYPE TEXT,
    ALTER COLUMN description DROP NOT NULL;
