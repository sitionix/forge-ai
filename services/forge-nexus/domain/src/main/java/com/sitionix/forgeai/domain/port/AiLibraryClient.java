package com.sitionix.forgeai.domain.port;
import java.util.UUID;
import com.sitionix.forgeai.domain.model.library.*;
public interface AiLibraryClient { LibrarySnapshot query(UUID projectId,LibraryProviderContext provider); }
