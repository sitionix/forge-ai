package com.sitionix.forgeai.infrastructure.githubcli.adapter;

import org.springframework.stereotype.Component;

@Component
class ApiGenerationArtifactNaming {

    String serviceCode(final String expectedArtifact, final String generationType) {
        final String normalized = this.normalize(expectedArtifact);
        final String prefix = "app-afesox-";
        final String suffix = "-" + generationType + "-stable";
        if (!normalized.startsWith(prefix) || !normalized.endsWith(suffix)) {
            throw new IllegalArgumentException("Unsupported API artifact format: " + expectedArtifact);
        }
        return normalized.substring(prefix.length(), normalized.length() - suffix.length());
    }

    boolean matchesExpected(final String expectedArtifact, final String actualArtifact) {
        final String expected = this.normalize(expectedArtifact);
        final String actual = this.normalize(actualArtifact);
        if (actual.equals(expected)) {
            return true;
        }
        final String stableSuffix = "-stable";
        return expected.endsWith(stableSuffix)
                && actual.startsWith(expected.substring(0, expected.length() - stableSuffix.length()) + "-");
    }

    boolean containsExpected(final String expectedArtifact, final String text) {
        final String expected = this.normalize(expectedArtifact);
        final String actual = this.normalize(text);
        if (expected.isBlank() || actual.isBlank()) {
            return false;
        }
        if (actual.contains(expected)) {
            return true;
        }
        final String stableSuffix = "-stable";
        return expected.endsWith(stableSuffix)
                && actual.contains(expected.substring(0, expected.length() - stableSuffix.length()) + "-");
    }

    String normalize(final String value) {
        return value == null ? "" : value.replace("@sitionix/", "").trim();
    }
}
