# The server: a JRE and the fat JAR CI built. No RUN step, so one COPY gives an image for arm64 and amd64
# with nothing compiled under emulation (docs/plan_web/hosting.md §Deploy). Built from the repository's root.
FROM eclipse-temurin:21-jre
ARG APP_VERSION=dev
ENV APP_VERSION=$APP_VERSION
LABEL org.opencontainers.image.source="https://github.com/ThomasWCode/Decrastination"
COPY --chown=1000:1000 server/build/libs/server-all.jar /app/server.jar
USER 1000:1000
EXPOSE 8080
# The image has no curl: bash's own TCP is enough to ask for the health page.
HEALTHCHECK --interval=30s --timeout=5s --start-period=30s --retries=3 \
  CMD ["bash", "-c", "exec 3<>/dev/tcp/127.0.0.1/8080 && printf 'GET /api/health HTTP/1.0\\r\\n\\r\\n' >&3 && grep -q 'HTTP/1.[01] 200' <&3"]
ENTRYPOINT ["java", "-Xmx1g", "-XX:+UseSerialGC", "-XX:+ExitOnOutOfMemoryError", "-jar", "/app/server.jar"]
