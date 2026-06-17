package com.sitionix.forgeai.it.infra;

import com.sitionix.forgeai.domain.model.generation.ApiArtifactGenerationRequest;
import com.sitionix.forgeai.domain.model.generation.GeneratedApiArtifact;
import com.sitionix.forgeai.domain.port.ApiArtifactGenerationPort;
import java.util.List;
import org.springframework.context.annotation.Primary;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

@Component
@Primary
@Profile("it")
public class ItApiArtifactGenerationPortStub implements ApiArtifactGenerationPort {

    @Override
    public GeneratedApiArtifact generate(final ApiArtifactGenerationRequest request) {
        return new GeneratedApiArtifact(
                "IT " + request.expectedArtifact(),
                request.scope(),
                this.dependency(request),
                1L,
                "https://github.com/sitionix/app-afesox/actions/runs/1",
                List.of("IT generation stub")
        );
    }

    private String dependency(final ApiArtifactGenerationRequest request) {
        if (request.expectedArtifact().startsWith("@")) {
            return request.expectedArtifact().replace("-stable", "-sitionix-it-unstable") + "@0.0.1";
        }
        return """
                <dependency>
                  <groupId>com.afesox</groupId>
                  <artifactId>%s</artifactId>
                  <version>0.0.1</version>
                </dependency>
                """.formatted(request.expectedArtifact().replace("-stable", "-sitionix-it-unstable")).trim();
    }
}
