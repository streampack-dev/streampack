FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /opt/streampack

RUN microdnf install -y curl-minimal && microdnf clean all

# The executable jar is expected to be built on the host before docker compose runs.
COPY app/target/*-exec.jar /opt/streampack/app.jar

EXPOSE 8080

ENTRYPOINT ["java", "-jar", "/opt/streampack/app.jar"]
