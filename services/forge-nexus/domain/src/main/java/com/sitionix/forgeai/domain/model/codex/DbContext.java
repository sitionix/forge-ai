package com.sitionix.forgeai.domain.model.codex;

import lombok.Builder;
import lombok.Data;

@Data
@Builder
public class DbContext {

    private Boolean required;
    private String type;
    private String mode;
    private String key;
}
