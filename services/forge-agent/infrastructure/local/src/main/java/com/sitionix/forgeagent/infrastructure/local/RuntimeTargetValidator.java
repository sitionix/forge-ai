package com.sitionix.forgeagent.infrastructure.local;

import com.sitionix.forgeagent.domain.exception.ValidationException;
import java.util.regex.Pattern;

final class RuntimeTargetValidator {
  private static final Pattern DOCKER_TARGET = Pattern.compile("[A-Za-z0-9][A-Za-z0-9_.-]{0,254}");
  private static final Pattern SYSTEMD_UNIT =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9:_.@\\-]{0,253}\\.[A-Za-z0-9_.@-]+");

  private RuntimeTargetValidator() {}

  static String docker(final String value, final String label) {
    if (value == null || !DOCKER_TARGET.matcher(value).matches()) {
      throw new ValidationException(label + " is invalid");
    }
    return value;
  }

  static String unit(final String value) {
    if (value == null || !SYSTEMD_UNIT.matcher(value).matches()) {
      throw new ValidationException("Systemd unit is invalid");
    }
    return value;
  }

  static String path(final String value, final String label) {
    if (value == null
        || value.isBlank()
        || value.indexOf('\0') >= 0
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new ValidationException(label + " is invalid");
    }
    return value;
  }
}
