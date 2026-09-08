#!/usr/bin/env bash
# setup-bot-access.sh
#
# One-shot bootstrap for bot API access (issue #47): generates (or reuses)
# the service secret, saves it to the bot profile, prints eval-able exports
# for the backend, and verifies the live endpoint.
#
# Only export lines go to STDOUT (safe for eval); everything else goes to
# STDERR. The backend restart stays HUMAN (operator territory): apply the
# printed exports, restart the backend, then re-run with --verify-only.
#
# Usage:
#   bash scripts/setup-bot-access.sh [--owner-id N] [--api-base-url URL]
#   eval "$(bash scripts/setup-bot-access.sh --owner-id N)"  # exports into your shell
#   bash scripts/setup-bot-access.sh --verify-only            # probe only
#   bash scripts/setup-bot-access.sh --rotate                 # new secret (restart + re-verify after)
#   bash scripts/setup-bot-access.sh --compose-dir DIR --recreate-backend
#       # Docker setups: sync override env, recreate backend, wait healthy, verify
#
# Safe to re-run: reuses the existing api-token.txt unless --rotate.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

HERMES_BASE="${HERMES_BASE:-$HOME/.hermes}"
BOT_PROFILE="${HERMES_BASE}/profiles/jobhunter-bot"
TOKEN_FILE="${BOT_PROFILE}/api-token.txt"
API_BASE_URL="${API_BASE_URL:-http://localhost:8080}"
OWNER_ID=""
VERIFY_ONLY=0
ROTATE=0
SKIP_VERIFY=0
COMPOSE_DIR=""
RECREATE=0

say() { echo "$@" >&2; }

usage() {
    sed -n '2,/^$/p' "$0" | sed 's/^# \{0,1\}//'
}

while [ $# -gt 0 ]; do
    case "$1" in
        --owner-id) OWNER_ID="${2:?--owner-id needs a value}"; shift 2 ;;
        --api-base-url) API_BASE_URL="$2"; shift 2 ;;
        --verify-only) VERIFY_ONLY=1; shift ;;
        --rotate) ROTATE=1; shift ;;
        --skip-verify) SKIP_VERIFY=1; shift ;;
        --compose-dir) COMPOSE_DIR="$2"; shift 2 ;;
        --recreate-backend) RECREATE=1; shift ;;
        --help|-h) usage; exit 0 ;;
        *) say "ERROR: unknown flag: $1 (see --help)"; exit 2 ;;
    esac
done

# ---------------------------------------------------------------------------
# Secret: reuse unless --rotate
# ---------------------------------------------------------------------------

if [ "${VERIFY_ONLY}" -eq 0 ]; then
    mkdir -p "${BOT_PROFILE}"
    if [ -f "${TOKEN_FILE}" ] && [ "${ROTATE}" -eq 0 ]; then
        SECRET="$(cat "${TOKEN_FILE}")"
        say "Reusing existing secret at ${TOKEN_FILE}"
    else
        if command -v openssl &>/dev/null; then
            SECRET="$(openssl rand -hex 32)"
        else
            SECRET="$(python3 -c 'import secrets; print(secrets.token_hex(32))')"
        fi
        printf '%s' "${SECRET}" > "${TOKEN_FILE}"
        chmod 600 "${TOKEN_FILE}"
        say "New secret saved to ${TOKEN_FILE} (chmod 600)"
    fi
else
    if [ ! -f "${TOKEN_FILE}" ]; then
        say "ERROR: no token file at ${TOKEN_FILE} — run setup first (without --verify-only)."
        exit 2
    fi
    SECRET="$(cat "${TOKEN_FILE}")"
fi

# ---------------------------------------------------------------------------
# Owner id: explicit flag, else single-user auto-detect from local SQLite.
# Not needed for --verify-only (the probe uses only the stored token).
# ---------------------------------------------------------------------------

if [ -z "${OWNER_ID}" ] && [ "${VERIFY_ONLY}" -eq 0 ]; then
    IDS="$(command -v sqlite3 &>/dev/null && [ -f ./data/jobhunter.db ] \
        && sqlite3 ./data/jobhunter.db "SELECT id FROM users;" 2>/dev/null || true)"
    if [ "$(printf '%s\n' "${IDS}" | grep -c .)" -eq 1 ] \
        && printf '%s' "${IDS}" | grep -qE '^[0-9]+$'; then
        OWNER_ID="${IDS}"
        say "Auto-detected owner-user-id=${OWNER_ID} from ./data/jobhunter.db"
    else
        say "ERROR: owner id unknown — pass --owner-id N (your userId from POST /api/auth/login)."
        exit 2
    fi
fi

# ---------------------------------------------------------------------------
# Compose override sync (only with --compose-dir). Secrets are hex-only
# (openssl/python generators), so the sed replacement below is safe.
# ---------------------------------------------------------------------------

if [ -n "${COMPOSE_DIR}" ]; then
    if [ ! -f "${COMPOSE_DIR}/docker-compose.yml" ] && [ ! -f "${COMPOSE_DIR}/compose.yaml" ]; then
        say "ERROR: no docker-compose.yml/compose.yaml in ${COMPOSE_DIR}."
        exit 2
    fi
    OVERRIDE="${COMPOSE_DIR}/docker-compose.override.yaml"
    if [ ! -f "${OVERRIDE}" ]; then
        printf 'services:\n  backend:\n    environment:\n      BOT_SERVICE_API_KEY: "%s"\n      BOT_SERVICE_OWNER_USER_ID: "%s"\n' \
            "${SECRET}" "${OWNER_ID}" > "${OVERRIDE}"
        say "Created ${OVERRIDE} with the service env."
    elif grep -qE '^[[:space:]]*BOT_SERVICE_API_KEY:' "${OVERRIDE}"; then
        sed -i -E "s|^([[:space:]]*BOT_SERVICE_API_KEY:).*|\1 \"${SECRET}\"|" "${OVERRIDE}"
        sed -i -E "s|^([[:space:]]*BOT_SERVICE_OWNER_USER_ID:).*|\1 \"${OWNER_ID}\"|" "${OVERRIDE}"
        say "Synced service env into ${OVERRIDE}."
    else
        say "ERROR: ${OVERRIDE} has no BOT_SERVICE_* keys — add once under the backend service:"
        say '    BOT_SERVICE_API_KEY: "<secret>"'
        say '    BOT_SERVICE_OWNER_USER_ID: "<owner-id>"'
        exit 2
    fi
fi

# ---------------------------------------------------------------------------
# Backend exports (STDOUT ONLY — eval-safe) + human next steps (stderr)
# ---------------------------------------------------------------------------

if [ "${VERIFY_ONLY}" -eq 0 ]; then
    say ""
    say "--- backend setup (run these, then restart the backend) ---"
    echo "export BOT_SERVICE_API_KEY=\"${SECRET}\""
    echo "export BOT_SERVICE_OWNER_USER_ID=\"${OWNER_ID}\""
    say "--- then: restart backend, re-run: bash scripts/setup-bot-access.sh --verify-only ---"
    say ""
fi

# ---------------------------------------------------------------------------
# Docker recreate + wait (only with --recreate-backend)
# ---------------------------------------------------------------------------

wait_for_api() {
    local tries=0
    while [ "${tries}" -lt 36 ]; do
        CODE="$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
            "${API_BASE_URL}/api/jobs?hasEmail=true" \
            -H "X-Bot-Token: ${SECRET}" 2>/dev/null || true)"
        case "${CODE}" in
            200)
                say "OK (HTTP 200) — bot API access is working."
                return 0
                ;;
            401)
                say "FAIL (HTTP 401) — secret mismatch: container env differs from ${TOKEN_FILE}."
                return 1
                ;;
        esac
        tries=$((tries + 1))
        sleep 5
    done
    say "FAIL (timeout) — backend did not answer within ~180s; check docker logs."
    return 1
}

if [ "${RECREATE}" -eq 1 ]; then
    if [ -z "${COMPOSE_DIR}" ]; then
        say "ERROR: --recreate-backend needs --compose-dir DIR."
        exit 2
    fi
    ( cd "${COMPOSE_DIR}" && docker compose up -d --force-recreate backend ) || {
        say "ERROR: docker compose recreate failed — check the compose project."
        exit 1
    }
    say "Backend recreating — waiting for a healthy API (up to ~180s) ..."
    wait_for_api
    exit $?
fi

# ---------------------------------------------------------------------------
# Verify live endpoint (skipped only with --skip-verify on setup runs)
# ---------------------------------------------------------------------------

if [ "${SKIP_VERIFY}" -eq 1 ] && [ "${VERIFY_ONLY}" -eq 0 ]; then
    say "Skipping verification (--skip-verify). Restart backend + re-run with --verify-only."
    exit 0
fi

say "Probing ${API_BASE_URL}/api/jobs?hasEmail=true ..."
CODE="$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 \
    "${API_BASE_URL}/api/jobs?hasEmail=true" \
    -H "X-Bot-Token: ${SECRET}" 2>/dev/null || true)"

case "${CODE}" in
    200)
        say "OK (HTTP 200) — bot API access is working."
        exit 0
        ;;
    401)
        say "FAIL (HTTP 401) — secret mismatch or backend not restarted with the new env."
        say "  1. Confirm BOT_SERVICE_API_KEY matches ${TOKEN_FILE}"
        say "  2. Confirm BOT_SERVICE_OWNER_USER_ID=${OWNER_ID}"
        say "  3. Restart the backend and re-run with --verify-only."
        exit 1
        ;;
    000)
        say "FAIL (unreachable) — backend not running at ${API_BASE_URL}?"
        say "  Start the backend, then re-run with --verify-only."
        exit 1
        ;;
    *)
        say "FAIL (HTTP ${CODE}) — unexpected response; check backend logs."
        exit 1
        ;;
esac
