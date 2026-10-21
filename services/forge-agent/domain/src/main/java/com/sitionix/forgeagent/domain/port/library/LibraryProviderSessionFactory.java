package com.sitionix.forgeagent.domain.port.library;

import com.sitionix.forgeagent.domain.model.library.LibraryContext;
public interface LibraryProviderSessionFactory {
 boolean supports(String providerId);
 LibraryProviderSession open(LibraryContext context);
}
