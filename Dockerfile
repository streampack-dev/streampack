FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /opt/streampack

RUN mkdir -p /opt/streampack/generative

# The executable jar is staged by the justfile so .dockerignore can keep the context small.
COPY target/docker/server-streampack.jar /opt/streampack/server-streampack.jar

EXPOSE 8080

VOLUME ["/opt/streampack/generative"]

ENTRYPOINT ["java", "-jar", "/opt/streampack/server-streampack.jar"]
