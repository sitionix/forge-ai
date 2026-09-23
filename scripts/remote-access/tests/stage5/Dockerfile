FROM eclipse-temurin:21-jre-noble
ENV container=docker
RUN apt-get update && apt-get install -y --no-install-recommends systemd systemd-sysv dbus openssh-server openssh-client python3 passwd \
    && rm -rf /var/lib/apt/lists/*
COPY --chown=0:0 scripts/remote-access /opt/forge-remote-package
RUN chmod -R go-w /opt/forge-remote-package
STOPSIGNAL SIGRTMIN+3
CMD ["/sbin/init"]
