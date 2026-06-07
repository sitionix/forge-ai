package com.sitionix.forgeai.application.infrastructure.jarvis;

public interface JarvisGateway {

    JarvisStatusView status();

    JarvisActionsView actions();

    JarvisCommandResultView command(JarvisCommandRequest command);
}
