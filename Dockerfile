# Temurin on Ubuntu 26.04, the base every Java image here shares (ui-pudl too). The release is
# pinned so the OS changes only by commit: GraalVM's moving tag went to Oracle Linux 10, which
# needs an x86-64-v3 processor (#238). This one needs only baseline x86-64, or arm64.
FROM eclipse-temurin:25-jre-resolute

# Turns off the JIT's hand-written Base64 code, which has a CVE; the plain Java code does the same
# work more slowly. The JVM reads this variable at every start and logs "Picked up
# JAVA_TOOL_OPTIONS". A deployment that sets its own JAVA_TOOL_OPTIONS replaces this value, so it
# must keep the flag. Remove it when a Temurin release with the fix is the base.
ENV JAVA_TOOL_OPTIONS="-XX:-UseBASE64Intrinsics"

WORKDIR /opt/streampack

RUN mkdir -p /opt/streampack/generative /opt/streampack/media /opt/streampack/ext /opt/streampack/logs

# The executable jar is staged by the justfile so .dockerignore can keep the context small.
COPY target/docker/server-streampack.jar /opt/streampack/server-streampack.jar

EXPOSE 8080

VOLUME ["/opt/streampack/generative"]
# Uploaded media (#196). Not turned on here: set STREAMPACK_MEDIA_DIR=/opt/streampack/media in the
# deployment, with a volume mounted on it, to turn it on.
VOLUME ["/opt/streampack/media"]
# The rolling log file: STREAMPACK_LOG_DIR, logs under the working directory by default. A volume,
# so the history can outlive the container; whether it is mounted is deploy's choice.
VOLUME ["/opt/streampack/logs"]

# Extensions (#229): Spring Boot's PropertiesLauncher puts STREAMPACK_EXT_DIR's jars, and the
# directory itself, on the classpath ahead of the server's own, so a jar there adds operations and a
# banner.txt there replaces the banner. An empty or missing directory changes nothing. exec keeps
# java as PID 1, so it gets the container's stop signal. GraalJS, which draws Mermaid diagrams
# (#252), loads native code and uses sun.misc.Unsafe; the two flags allow that without the JVM's
# warnings on the first diagram.
ENTRYPOINT ["sh", "-c", "exec java --enable-native-access=ALL-UNNAMED --sun-misc-unsafe-memory-access=allow -Dloader.path=\"${STREAMPACK_EXT_DIR:-/opt/streampack/ext}\" -cp /opt/streampack/server-streampack.jar org.springframework.boot.loader.launch.PropertiesLauncher \"$@\"", "streampack"]
