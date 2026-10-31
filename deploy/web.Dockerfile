# The website: Caddy, the built pages and the Caddyfile. No RUN step, for the same reason as server.Dockerfile.
# Built from the repository's root.
FROM caddy:2-alpine
LABEL org.opencontainers.image.source="https://github.com/ThomasWCode/Decrastination"
COPY web/dist/ /srv/
COPY deploy/Caddyfile /etc/caddy/Caddyfile
