"""Disposable evidence: a repository fsmonitor hook inherits a Git parent's env.

Uses only a synthetic environment and an automatically removed temporary repo.
Does not access personal Git configuration, production credentials or network.
This reproduces the unsafe baseline, not proof of the proposed boundary.
"""
import pathlib
import subprocess
import tempfile


def main():
    with tempfile.TemporaryDirectory(prefix="forge-mcp-git-boundary-") as directory:
        root = pathlib.Path(directory)
        environment = {
            "PATH": "/usr/bin:/bin",
            "HOME": directory,
            "GIT_CONFIG_NOSYSTEM": "1",
            "GIT_CONFIG_GLOBAL": "/dev/null",
            "FORGE_SYNTHETIC_DB_CREDENTIAL": "synthetic-task2-canary",
        }

        def git(*arguments):
            return subprocess.run(
                ["/usr/bin/git", "-C", directory, *arguments],
                env=environment, check=True, capture_output=True, text=True,
            )

        git("init")
        hook = root / "fsmonitor-fixture"
        hook.write_text(
            '#!/bin/sh\n'
            'if [ "$FORGE_SYNTHETIC_DB_CREDENTIAL" = "synthetic-task2-canary" ]; then\n'
            '  printf observed > observed\n'
            'fi\n'
            'printf "fixture-token\\000"\n'
        )
        hook.chmod(0o700)
        git("config", "core.fsmonitor", str(hook))
        git("status", "--porcelain=v2", "--branch", "--untracked-files=normal")
        observed = (root / "observed").exists()
        print("REPOSITORY_HOOK_OBSERVED_PARENT_SYNTHETIC_CREDENTIAL=" + str(observed))
        if not observed:
            raise SystemExit("Baseline reproduction failed; do not infer a secure boundary.")


if __name__ == "__main__":
    main()
