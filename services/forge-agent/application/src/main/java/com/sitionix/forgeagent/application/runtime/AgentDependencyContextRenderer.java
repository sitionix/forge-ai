package com.sitionix.forgeagent.application.runtime;

import java.util.List;
import java.util.StringJoiner;
import org.springframework.stereotype.Component;

@Component
public final class AgentDependencyContextRenderer {

    public String render(final List<NodeDependencyOutput> dependencies) {
        if (dependencies == null || dependencies.isEmpty()) {
            return "None.";
        }
        final StringJoiner rendered = new StringJoiner("\n");
        for (int index = 0; index < dependencies.size(); index++) {
            rendered.add(this.renderDependency(index + 1, dependencies.get(index)));
        }
        return rendered.toString();
    }

    private String renderDependency(final int index, final NodeDependencyOutput dependency) {
        return """
                <dependency index="%d" node_run_id="%s" agent_name="%s">
                %s
                </dependency>""".formatted(
                index,
                dependency.nodeRunId(),
                this.attributeValue(dependency.agentName()),
                this.value(dependency.output() == null ? null : dependency.output().jsonValue())
        );
    }

    private String value(final String value) {
        return value == null ? "" : value;
    }

    private String attributeValue(final String value) {
        return this.value(value)
                .replace("&", "&amp;")
                .replace("\"", "&quot;")
                .replace("<", "&lt;")
                .replace(">", "&gt;");
    }
}
