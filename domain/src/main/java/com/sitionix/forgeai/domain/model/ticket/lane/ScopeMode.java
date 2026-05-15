package com.sitionix.forgeai.domain.model.ticket.lane;

import java.util.Arrays;
import java.util.List;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

@Getter
@RequiredArgsConstructor
public enum ScopeMode {
    PER_SCOPE("per_scope") {
        @Override
        public List<String> laneScopes(final List<String> selectedScopes) {
            return selectedScopes;
        }

        @Override
        public List<String> dependencyScopes(
                final List<String> selectedScopes,
                final String currentScope
        ) {
            if (GLOBAL_SCOPE.equals(currentScope)) {
                return selectedScopes;
            }
            return List.of(currentScope);
        }
    },
    GLOBAL("global") {
        @Override
        public List<String> laneScopes(final List<String> selectedScopes) {
            return List.of(GLOBAL_SCOPE);
        }

        @Override
        public List<String> dependencyScopes(
                final List<String> selectedScopes,
                final String currentScope
        ) {
            return List.of(GLOBAL_SCOPE);
        }
    };

    private final String id;
    public static final String GLOBAL_SCOPE = "GLOBAL";

    public static ScopeMode byId(final String id) {
        return Arrays.stream(values())
                .filter(value -> value.id.equals(id))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Unknown scope mode id: " + id));
    }

    public abstract List<String> laneScopes(List<String> selectedScopes);

    public abstract List<String> dependencyScopes(
            List<String> selectedScopes,
            String currentScope
    );
}
