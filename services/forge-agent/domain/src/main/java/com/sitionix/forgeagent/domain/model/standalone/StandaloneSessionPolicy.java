package com.sitionix.forgeagent.domain.model.standalone;
import com.sitionix.forgeagent.domain.exception.*;
public final class StandaloneSessionPolicy {
    public static final int MAX_MESSAGE_CHARACTERS=32768;
    private StandaloneSessionPolicy() { }
    public static void message(String text) {
        if(text==null||text.isBlank()||text.codePointCount(0,text.length())>MAX_MESSAGE_CHARACTERS)
            throw new ValidationException("INVALID_SESSION_MESSAGE","Message must contain 1 to 32768 characters.");
    }
    public static void send(String status,boolean active,long revision,long expected) {
        if(revision!=expected)throw new ConflictException("STALE_SESSION_REVISION","Session revision changed.");
        if(active)throw new ConflictException("SESSION_BUSY","Session has an active turn.");
        if(!"READY".equals(status)&&!"CREATED".equals(status)&&!"AWAITING_USER".equals(status))
            throw new ConflictException("SESSION_RECOVERY_REQUIRED","Session requires recovery before another message.");
    }
    public record TurnCompletion(String turnStatus,String sessionStatus,String assistantText,boolean retainActiveTurn) { }
    /** A terminal task does not end its conversation. Uncertain execution retains ownership. */
    public static TurnCompletion complete(String outcome,String text,boolean cancelRequested,boolean uncertain) {
        if(!java.util.Set.of("SUCCEEDED","FAILED","CANCELLED").contains(outcome))throw new IllegalArgumentException("Invalid terminal turn outcome");
        if(uncertain)return new TurnCompletion("RECOVERY_REQUIRED","RECOVERY_REQUIRED",null,true);
        if(cancelRequested)return new TurnCompletion("CANCELLED","READY",null,false);
        if("SUCCEEDED".equals(outcome)&&(text==null||text.isBlank()))throw new IllegalArgumentException("Successful turn requires an assistant reply");
        return new TurnCompletion(outcome,"READY","SUCCEEDED".equals(outcome)?text:null,false);
    }
}
