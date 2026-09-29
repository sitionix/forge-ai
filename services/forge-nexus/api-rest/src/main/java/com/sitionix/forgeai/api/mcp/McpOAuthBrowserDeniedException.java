package com.sitionix.forgeai.api.mcp;

final class McpOAuthBrowserDeniedException extends RuntimeException {
    McpOAuthBrowserDeniedException(){super("OAuth browser request was rejected.");}
}
