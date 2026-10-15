FROM ghcr.io/graalvm/jdk-community:25

WORKDIR /opt/streampack

RUN mkdir -p /opt/streampack/generative /opt/streampack/media /opt/streampack/ext

# The executable jar is staged by the justfile so .dockerignore can keep the context small.
COPY target/docker/server-streampack.jar /opt/streampack/server-streampack.jar

EXPOSE 8080

VOLUME ["/opt/streampack/generative"]
# Uploaded media (#196). Not turned on here: set STREAMPACK_MEDIA_DIR=/opt/streampack/media in the
# deployment, with a volume mounted on it, to turn it on.
VOLUME ["/opt/streampack/media"]

# Extensions (#229): Spring Boot's PropertiesLauncher puts STREAMPACK_EXT_DIR's jars, and the
# directory itself, on the classpath ahead of the server's own, so a jar there adds operations and a
# banner.txt there replaces the banner. An empty or missing directory changes nothing. exec keeps
# java as PID 1, so it gets the container's stop signal.
ENTRYPOINT ["sh", "-c", "exec java -Dloader.path=\"${STREAMPACK_EXT_DIR:-/opt/streampack/ext}\" -cp /opt/streampack/server-streampack.jar org.springframework.boot.loader.launch.PropertiesLauncher \"$@\"", "streampack"]
