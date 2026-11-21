# Builds and runs the bridge (not the Fabric mod).
#   docker build -t fluxer-bridge .
# The build uses -PbridgeOnly=true so Minecraft is never downloaded.

FROM eclipse-temurin:25-jdk AS build
WORKDIR /src
COPY gradlew settings.gradle build.gradle gradle.properties ./
COPY gradle gradle
COPY protocol protocol
COPY bridge bridge
RUN chmod +x gradlew && ./gradlew --no-daemon -PbridgeOnly=true :bridge:installDist

FROM eclipse-temurin:25-jre
RUN useradd --system --uid 10001 --no-create-home bridge
COPY --from=build /src/bridge/build/install/fluxer-bridge /opt/fluxer-bridge
USER bridge
ENV BRIDGE_CONFIG=/config/bridge.json
EXPOSE 8765
ENTRYPOINT ["/opt/fluxer-bridge/bin/fluxer-bridge"]
