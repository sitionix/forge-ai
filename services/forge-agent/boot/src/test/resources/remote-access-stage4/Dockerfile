FROM eclipse-temurin:21-jre-jammy
RUN apt-get update && apt-get install -y --no-install-recommends openssh-server openssh-client python3 systemd passwd \
    && rm -rf /var/lib/apt/lists/*
CMD ["python3", "/fixture/start.py"]
