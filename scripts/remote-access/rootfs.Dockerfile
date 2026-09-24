FROM eclipse-temurin:21-jre-noble@sha256:7739f0ffce786528961eea6bf46d9610ee968ac6127c9b2e93494757bdecce9f

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
    bash coreutils curl findutils git grep make maven nodejs npm python3 sed \
    && rm -rf /var/lib/apt/lists/* /root/.cache

# This image is exported as a non-networked, read-only systemd RootDirectory.
# No host configuration, Docker socket, Forge state, or credentials are copied in.
