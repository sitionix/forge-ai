from __future__ import annotations

from collections.abc import Sequence


class SecurityError(ValueError):
    """Raised when an allowlisted command violates local safety policy."""


BLOCKED_EXECUTABLES = {
    "apt",
    "apt-get",
    "chmod",
    "chown",
    "dd",
    "dpkg",
    "mkfs",
    "mount",
    "mv",
    "rm",
    "rmdir",
    "shutdown",
    "sudo",
    "systemctl",
    "umount",
}

DESTRUCTIVE_SHELL_TOKENS = (
    " rm ",
    " rm -",
    "rm -",
    " rmdir ",
    "mkfs",
    "dd if=",
    "shutdown",
    "reboot",
    "apt install",
    "apt-get install",
    "dpkg -i",
    "sudo ",
    "chmod ",
    "chown ",
    " > ",
    ">>",
)


def validate_command(command: Sequence[str]) -> None:
    if not command:
        raise SecurityError("Command must not be empty")
    if not all(isinstance(part, str) and part for part in command):
        raise SecurityError("Command parts must be non-empty strings")

    executable = command[0]
    if executable in BLOCKED_EXECUTABLES:
        raise SecurityError(f"Executable is blocked: {executable}")

    if executable in {"bash", "sh"}:
        if len(command) < 3 or command[1] not in {"-lc", "-c"}:
            raise SecurityError("Shell commands must use an explicit command string")
        script = f" {command[2].lower()} "
        for token in DESTRUCTIVE_SHELL_TOKENS:
            if token in script:
                raise SecurityError("Shell command contains a blocked operation")


def assert_no_user_text_in_command(command: Sequence[str], user_text: str) -> None:
    normalized = user_text.strip()
    if not normalized:
        return
    for part in command:
        if normalized and normalized in part:
            raise SecurityError("Raw user text must not be passed to commands")
