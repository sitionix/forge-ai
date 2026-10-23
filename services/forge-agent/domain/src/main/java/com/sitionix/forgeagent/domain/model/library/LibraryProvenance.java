package com.sitionix.forgeagent.domain.model.library;

/** Native identity stays internal; API mappers explicitly omit it. */
public record LibraryProvenance(String source, String nativeIdentity) {}
