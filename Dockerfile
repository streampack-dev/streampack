FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /opt/streampack

RUN mkdir -p /opt/streampack/generative /opt/streampack/media

# The executable jar is staged by the justfile so .dockerignore can keep the context small.
COPY target/docker/server-streampack.jar /opt/streampack/server-streampack.jar

EXPOSE 8080

VOLUME ["/opt/streampack/generative"]
# Uploaded media (#196). Not turned on here: set STREAMPACK_MEDIA_DIR=/opt/streampack/media in the
# deployment, with a volume mounted on it, to turn it on.
VOLUME ["/opt/streampack/media"]

ENTRYPOINT ["java", "-jar", "/opt/streampack/server-streampack.jar"]
