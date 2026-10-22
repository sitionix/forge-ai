package com.sitionix.forgeagent.domain.model;
import com.sitionix.forgeagent.domain.model.standalone.StandaloneSessionPolicy;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
class StandaloneSessionPolicyTest {
    @Test void turnCompletionKeepsConversationReusableAndUncertainOwnershipFenced() {
        var reply=StandaloneSessionPolicy.complete("SUCCEEDED","answer",false,false);
        assertEquals("READY",reply.sessionStatus());assertEquals("answer",reply.assistantText());assertFalse(reply.retainActiveTurn());
        var cancelled=StandaloneSessionPolicy.complete("SUCCEEDED","late answer",true,false);
        assertEquals("CANCELLED",cancelled.turnStatus());assertNull(cancelled.assistantText());assertEquals("READY",cancelled.sessionStatus());
        var uncertain=StandaloneSessionPolicy.complete("FAILED",null,true,true);
        assertEquals("RECOVERY_REQUIRED",uncertain.turnStatus());assertTrue(uncertain.retainActiveTurn());assertNull(uncertain.assistantText());
        assertThrows(IllegalArgumentException.class,()->StandaloneSessionPolicy.complete("RUNNING",null,false,false));
    }
    @Test void acceptsOrdinaryConversationAndUnicodeLimit() {
        assertDoesNotThrow(()->StandaloneSessionPolicy.message("hello"));
        assertDoesNotThrow(()->StandaloneSessionPolicy.message("😀".repeat(32768)));
        assertThrows(RuntimeException.class,()->StandaloneSessionPolicy.message("😀".repeat(32769)));
        assertThrows(RuntimeException.class,()->StandaloneSessionPolicy.message(" \n"));
    }
    @Test void recoverableTurnFailureDoesNotDestroyConversationButUncertainRecoveryBlocksSends() {
        assertDoesNotThrow(()->StandaloneSessionPolicy.send("READY",false,3,3));
        assertTrue(assertThrows(RuntimeException.class,()->StandaloneSessionPolicy.send("RECOVERY_REQUIRED",false,3,3)).getMessage().contains("recovery"));
        assertTrue(assertThrows(RuntimeException.class,()->StandaloneSessionPolicy.send("RUNNING",true,3,3)).getMessage().contains("active"));
        assertTrue(assertThrows(RuntimeException.class,()->StandaloneSessionPolicy.send("READY",false,3,2)).getMessage().contains("revision"));
    }
}
