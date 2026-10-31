#!/usr/bin/env bash
# One-shot setup of a fresh Ubuntu 24.04 instance on Oracle Cloud for Decrastination's server.
# Idempotent: safe to run again. Run from a copy of this folder on the box:
#
#   sudo ./bootstrap.sh
#
# What it does:
#   1. Installs Docker from Docker's own repository.
#   2. Opens ports 80 and 443 in the instance firewall (Oracle's Ubuntu images ship iptables rules that
#      reject everything but SSH: the cloud security list is not enough on its own).
#   3. Installs /opt/decrastination: the compose file, Litestream's config, the update script, .env.
#   4. Makes MASTER_KEY once, if .env has none.
#   5. Turns on unattended security upgrades.
#   6. Once .env is filled: restores the database from R2 if the box has none and R2 has one, starts
#      everything and turns on the timer that pulls new images.
set -euo pipefail

if [ "$(id -u)" -ne 0 ]; then
  echo "run with sudo" >&2
  exit 1
fi

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_DIR=/opt/decrastination
OWNER_USER="${SUDO_USER:-ubuntu}"
# The containers' user: the data's owner, whoever logs in.
APP_UID=1000

echo "==> packages"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl gnupg coreutils util-linux openssl unattended-upgrades iptables-persistent

echo "==> docker"
if ! command -v docker >/dev/null 2>&1; then
  install -m 0755 -d /etc/apt/keyrings
  curl -fsSL https://download.docker.com/linux/ubuntu/gpg -o /etc/apt/keyrings/docker.asc
  chmod a+r /etc/apt/keyrings/docker.asc
  # shellcheck disable=SC1091
  . /etc/os-release
  echo "deb [arch=$(dpkg --print-architecture) signed-by=/etc/apt/keyrings/docker.asc] https://download.docker.com/linux/ubuntu ${VERSION_CODENAME} stable" \
    > /etc/apt/sources.list.d/docker.list
  apt-get update -qq
  apt-get install -y -qq docker-ce docker-ce-cli containerd.io docker-compose-plugin
fi
systemctl enable --now docker
usermod -aG docker "$OWNER_USER" || true

echo "==> firewall (instance iptables)"
open_port() {
  local port="$1"
  if ! iptables -C INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT 2>/dev/null; then
    # Before Oracle's catch-all REJECT, which is the last rule in INPUT.
    local reject
    reject="$(iptables -L INPUT --line-numbers -n | awk '/REJECT/ {print $1; exit}')"
    if [ -n "$reject" ]; then
      iptables -I INPUT "$reject" -p tcp --dport "$port" -m state --state NEW -j ACCEPT
    else
      iptables -A INPUT -p tcp --dport "$port" -m state --state NEW -j ACCEPT
    fi
  fi
}
open_port 80
open_port 443
if ! iptables -C INPUT -p udp --dport 443 -j ACCEPT 2>/dev/null; then
  reject="$(iptables -L INPUT --line-numbers -n | awk '/REJECT/ {print $1; exit}')"
  iptables -I INPUT "${reject:-1}" -p udp --dport 443 -j ACCEPT
fi
netfilter-persistent save >/dev/null

echo "==> ${APP_DIR}"
install -d -m 0750 -o "$OWNER_USER" -g "$OWNER_USER" "$APP_DIR"
install -m 0644 -o "$OWNER_USER" "$HERE/compose.yaml" "$APP_DIR/compose.yaml"
install -m 0644 -o "$OWNER_USER" "$HERE/litestream.yml" "$APP_DIR/litestream.yml"
install -m 0755 -o "$OWNER_USER" "$HERE/decrastination-update.sh" "$APP_DIR/decrastination-update.sh"
install -d -m 0700 -o "$APP_UID" -g "$APP_UID" "$APP_DIR/data"
install -d -m 0700 -o "$APP_UID" -g "$APP_UID" "$APP_DIR/caddy" "$APP_DIR/caddy/data" "$APP_DIR/caddy/config"
if [ ! -f "$APP_DIR/.env" ]; then
  install -m 0600 -o "$OWNER_USER" "$HERE/env.production.example" "$APP_DIR/.env"
  echo "    wrote ${APP_DIR}/.env: fill it in"
fi
if grep -q '^MASTER_KEY=$' "$APP_DIR/.env"; then
  key="$(openssl rand -base64 32)"
  sed -i "s|^MASTER_KEY=\$|MASTER_KEY=${key}|" "$APP_DIR/.env"
  unset key
  echo "    made MASTER_KEY: keep a copy off the box"
fi

echo "==> systemd timer"
install -m 0644 "$HERE/decrastination-update.service" /etc/systemd/system/decrastination-update.service
install -m 0644 "$HERE/decrastination-update.timer" /etc/systemd/system/decrastination-update.timer
systemctl daemon-reload

echo "==> unattended upgrades"
cat > /etc/apt/apt.conf.d/20auto-upgrades <<'CONF'
APT::Periodic::Update-Package-Lists "1";
APT::Periodic::Unattended-Upgrade "1";
APT::Periodic::AutocleanInterval "7";
CONF

# Anything unfilled stops here: the server can't start without them.
if grep -qE '^(GITHUB_CLIENT_ID|GITHUB_CLIENT_SECRET|ALLOWED_GITHUB_USER_ID|R2_ACCOUNT_ID|R2_ACCESS_KEY_ID|R2_SECRET_ACCESS_KEY)=$' "$APP_DIR/.env"; then
  echo
  echo "Fill in ${APP_DIR}/.env, then run this again."
  exit 0
fi

echo "==> starting"
cd "$APP_DIR"
docker compose pull --quiet
# A rebuilt box comes up with the state: before the server starts, or it makes an empty database of its own.
docker compose run --rm --no-deps litestream restore -config /etc/litestream.yml -if-db-not-exists -if-replica-exists /data/decrastination.db
docker compose up -d --wait --wait-timeout 120
systemctl enable --now decrastination-update.timer

echo
echo "done. Check: curl -s https://\$DOMAIN/api/health"
