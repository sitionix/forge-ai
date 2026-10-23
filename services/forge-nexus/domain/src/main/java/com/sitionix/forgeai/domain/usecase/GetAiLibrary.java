package com.sitionix.forgeai.domain.usecase;
import java.util.UUID;
import com.sitionix.forgeai.domain.model.library.LibrarySnapshot;
public interface GetAiLibrary { LibrarySnapshot execute(UUID projectId); }
