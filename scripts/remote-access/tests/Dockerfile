FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server openssh-client systemd passwd python3 \
    && rm -rf /var/lib/apt/lists/*
COPY --chown=0:0 scripts/remote-access /opt/forge-remote-package
RUN chmod -R go-w /opt/forge-remote-package
CMD ["python3", "/opt/forge-remote-package/tests/live_ssh.py"]
