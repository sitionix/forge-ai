package com.sitionix.forgeai.domain.usecase;

import java.util.UUID;

@FunctionalInterface
public interface CompleteReviewerTask {

    UUID complete(UUID ticketId);
}
