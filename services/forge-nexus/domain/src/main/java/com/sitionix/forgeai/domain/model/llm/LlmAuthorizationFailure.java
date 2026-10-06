package com.sitionix.forgeai.domain.model.llm;

import java.util.Set;

/** Only fixed, safe codes cross the Agent boundary. No upstream messages or causes are retained. */
public final class LlmAuthorizationFailure extends RuntimeException {
    private static final Set<String> CODES=Set.of("LOGIN_NOT_FOUND","LOGIN_IN_PROGRESS","INVALID_BROWSER_BINDING",
            "CODEX_AUTH_REQUIRED","CODEX_AUTH_UNAVAILABLE","CODEX_AUTH_PROVIDER_ERROR","CODEX_AUTH_VERIFICATION_FAILED",
            "CODEX_LOGIN_FAILED","CODEX_LOGIN_NOT_CONFIRMED","CODEX_LOGIN_CANCEL_FAILED","CODEX_LOGOUT_FAILED",
            "CODEX_LOGOUT_IN_PROGRESS","CODEX_LOGOUT_REQUIRED","CODEX_AUTH_CLEANUP_FAILED");
    public LlmAuthorizationFailure(String code){super(safe(code) && code!=null ? code : "CODEX_AUTH_UNAVAILABLE");}
    public String code(){return getMessage();}
    public static boolean safe(String code){return code==null || CODES.contains(code);}
}
