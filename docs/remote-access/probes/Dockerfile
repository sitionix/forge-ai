# Disposable Stage 0 SSH probe only. Never install this image as a Forge service.
FROM python:3.12-slim
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server openssh-client util-linux \
    && rm -rf /var/lib/apt/lists/*
COPY ssh_boundary.py /probe.py
CMD ["python3", "/probe.py"]
