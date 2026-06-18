package com.sitionix.forgeai.application.infrastructure.knowledge;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.LinkedHashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.experimental.Accessors;

@Getter
@Setter
@NoArgsConstructor
@Accessors(fluent = true)
public class KnowledgeAnalysisGraphMetaView {

    @JsonProperty("truncated")
    private Boolean truncated;
    @JsonProperty("totalNodeCount")
    private Integer totalNodeCount;
    @JsonProperty("totalEdgeCount")
    private Integer totalEdgeCount;
    @JsonProperty("returnedNodeCount")
    private Integer returnedNodeCount;
    @JsonProperty("returnedEdgeCount")
    private Integer returnedEdgeCount;
    @JsonProperty("skippedEdgeCount")
    private Integer skippedEdgeCount;
    @JsonProperty("skippedMissingEndpointCount")
    private Integer skippedMissingEndpointCount;
    @JsonProperty("skippedByLimitCount")
    private Integer skippedByLimitCount;
    @JsonProperty("truncationReason")
    private String truncationReason;
    @JsonProperty("maxNodeLimit")
    private Integer maxNodeLimit;
    @JsonProperty("maxEdgeLimit")
    private Integer maxEdgeLimit;
    @JsonProperty("hiddenIsolatedCount")
    private Integer hiddenIsolatedCount;
    @JsonProperty("connectedComponentCount")
    private Integer connectedComponentCount;
    @JsonProperty("largestComponentNodeCount")
    private Integer largestComponentNodeCount;
    @JsonProperty("largestComponentEdgeCount")
    private Integer largestComponentEdgeCount;
    @JsonProperty("overviewSelectionReason")
    private String overviewSelectionReason;
    @JsonIgnore
    @Getter(AccessLevel.NONE)
    @Setter(AccessLevel.NONE)
    private final Map<String, Object> additionalProperties = new LinkedHashMap<>();

    public KnowledgeAnalysisGraphMetaView(final Boolean truncated,
                                          final Integer totalNodeCount,
                                          final Integer totalEdgeCount,
                                          final Integer returnedNodeCount,
                                          final Integer returnedEdgeCount,
                                          final Integer maxNodeLimit,
                                          final Integer maxEdgeLimit) {
        this.truncated = truncated;
        this.totalNodeCount = totalNodeCount;
        this.totalEdgeCount = totalEdgeCount;
        this.returnedNodeCount = returnedNodeCount;
        this.returnedEdgeCount = returnedEdgeCount;
        this.maxNodeLimit = maxNodeLimit;
        this.maxEdgeLimit = maxEdgeLimit;
    }

    @JsonAnyGetter
    public Map<String, Object> additionalProperties() {
        return this.additionalProperties;
    }

    @JsonAnySetter
    public void setAdditionalProperty(final String name, final Object value) {
        this.additionalProperties.put(name, value);
    }
}
