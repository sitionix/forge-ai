package com.sitionix.forgeai.application.usecase;

import com.sitionix.forgeai.domain.exception.JarvisGatewayException;
import com.sitionix.forgeai.domain.model.jarvis.JarvisActionsView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandRequest;
import com.sitionix.forgeai.domain.model.jarvis.JarvisCommandResultView;
import com.sitionix.forgeai.domain.model.jarvis.JarvisGatewayErrorCode;
import com.sitionix.forgeai.domain.model.jarvis.JarvisStatusView;
import com.sitionix.forgeai.domain.port.JarvisGateway;
import com.sitionix.forgeai.domain.usecase.ManageJarvisInfrastructure;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class ManageJarvisInfrastructureUseCase implements ManageJarvisInfrastructure {

    private final JarvisGateway jarvisGateway;

    @Override
    public JarvisStatusView status() {
        return this.jarvisGateway.status();
    }

    @Override
    public JarvisActionsView actions() {
        return this.jarvisGateway.actions();
    }

    @Override
    public JarvisCommandResultView command(final JarvisCommandRequest command) {
        if (command == null || command.text() == null || command.text().isBlank()) {
            throw new JarvisGatewayException(JarvisGatewayErrorCode.INVALID_COMMAND, "Command text must not be empty");
        }
        return this.jarvisGateway.command(command);
    }
}
