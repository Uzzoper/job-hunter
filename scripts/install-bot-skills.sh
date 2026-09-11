#!/usr/bin/env bash
# install-bot-skills.sh
#
# Installs Hermes Agent bot skills from the repository into the bot profile
# directory (~/.hermes/profiles/jobhunter-bot/skills/<skill-name>/), and renders
# the versioned standing-instructions template (bot-profile/SOUL.md) into
# ~/.hermes/profiles/jobhunter-bot/SOUL.md on first install only — a live
# SOUL.md is never overwritten.
#
# Usage:
#   bash scripts/install-bot-skills.sh
#
# Safe to re-run: overwrites existing skill files.
# New skills are picked up automatically: every subdirectory of skills/
# containing a SKILL.md is installed under its own name.
#
# Optional env vars for SOUL.md rendering (default: placeholder stays visible):
#   SOUL_USER_NAME / SOUL_USER_EMAIL / SOUL_RESUME_FILENAME

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

HERMES_BASE="${HERMES_BASE:-$HOME/.hermes}"
BOT_PROFILE="${HERMES_BASE}/profiles/jobhunter-bot"
SKILLS_DEST="${BOT_PROFILE}/skills"
MEMORY_DIR="${BOT_PROFILE}/memails"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILLS_SRC="${REPO_ROOT}/skills"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

echo "=== Installing bot skills ==="
echo ""

# Verify source directory exists and holds at least one skill
if [ ! -d "${SKILLS_SRC}" ]; then
    echo "ERROR: skills source directory not found at ${SKILLS_SRC}/"
    exit 1
fi

# ---------------------------------------------------------------------------
# Create target directories (profile may not exist yet)
# ---------------------------------------------------------------------------

echo "Creating directories ..."
mkdir -p "${SKILLS_DEST}"
mkdir -p "${MEMORY_DIR}"
echo "  Skills dir:  ${SKILLS_DEST}"
echo "  Memory dir:  ${MEMORY_DIR}"
# Seed empty USER.md (Hermes convention: user profile vs agent learnings).
# Never overwrite an existing file — the bot owns its content.
USER_MD="${BOT_PROFILE}/memories/USER.md"
if [ ! -f "${USER_MD}" ]; then
    mkdir -p "$(dirname "${USER_MD}")"
    : > "${USER_MD}"
    echo "  USER.md seeded (empty) at ${USER_MD}"
else
    echo "  USER.md exists — leaving untouched"
fi
echo ""

# ---------------------------------------------------------------------------
# Install each skill (per-skill subdirectory layout)
# ---------------------------------------------------------------------------

installed=0
for skill_dir in "${SKILLS_SRC}"/*/; do
    [ -d "${skill_dir}" ] || continue
    skill_name="$(basename "${skill_dir}")"
    if [ ! -f "${skill_dir}/SKILL.md" ]; then
        echo "  Skipping ${skill_name}/ (no SKILL.md)"
        continue
    fi

    dest="${SKILLS_DEST}/${skill_name}"
    echo "Installing ${skill_name} ..."
    mkdir -p "${dest}"
    cp "${skill_dir}/SKILL.md" "${dest}/SKILL.md"
    echo "  SKILL.md -> ${dest}/SKILL.md"

    # Copy optional helper scripts (e.g. scraper.py) and make them executable.
    # Test files (*_test.py) are never installed to the bot profile.
    for helper in "${skill_dir}"/*.py "${skill_dir}"/*.sh; do
        [ -e "${helper}" ] || continue
        case "$(basename "${helper}")" in
            *_test.py) echo "  skip test file: $(basename "${helper}")"; continue ;;
        esac
        cp "${helper}" "${dest}/"
        chmod +x "${dest}/$(basename "${helper}")"
        echo "  $(basename "${helper}") -> ${dest}/ (chmod +x)"
    done

    # Sync subdirectories needed at runtime (e.g. portals/, helpers/).
    # Test files (*_test.py) and caches are never installed to the bot profile.
    for subdir in "${skill_dir}"/*/; do
        [ -d "${subdir}" ] || continue
        subname="$(basename "${subdir}")"
        [ "${subname}" = "__pycache__" ] && continue
        mkdir -p "${dest}/${subname}"
        for subfile in "${subdir}"*; do
            [ -f "${subfile}" ] || continue
            case "$(basename "${subfile}")" in
                *_test.py) echo "  skip test file: ${subname}/$(basename "${subfile}")"; continue ;;
            esac
            cp "${subfile}" "${dest}/${subname}/"
            case "${subfile}" in
                *.py|*.sh) chmod +x "${dest}/${subname}/$(basename "${subfile}")" ;;
            esac
            echo "  ${subname}/$(basename "${subfile}") -> ${dest}/${subname}/"
        done
    done
    installed=$((installed + 1))
done

if [ "${installed}" -eq 0 ]; then
    echo "ERROR: no skills with SKILL.md found under ${SKILLS_SRC}/"
    exit 1
fi
echo ""
echo "Installed ${installed} skill(s)."
echo ""

# ---------------------------------------------------------------------------
# Verify installation
# ---------------------------------------------------------------------------

echo "=== Verification ==="
echo ""
echo "Installed skills:"
ls -la "${SKILLS_DEST}/"
echo ""

# Quick syntax check on any installed Python helpers
if command -v python3 &>/dev/null; then
    echo "Python syntax check:"
    for py in "${SKILLS_DEST}"/*/*.py; do
        [ -e "${py}" ] || continue
        if python3 -m py_compile "${py}"; then
            echo "  ${py}: OK"
        else
            echo "  ${py}: SYNTAX ERROR"
        fi
    done
else
    echo "  python3 not found — skipping syntax check"
fi
echo ""

echo "Memory directory:"
ls -la "${MEMORY_DIR}/" 2>/dev/null || echo "  (empty)"
echo ""

# ---------------------------------------------------------------------------
# Install SOUL.md (versioned standing instructions — never overwrite a live file)
# ---------------------------------------------------------------------------

echo "=== Installing SOUL.md ==="
echo ""
SOUL_SRC="${REPO_ROOT}/bot-profile/SOUL.md"
SOUL_DEST="${BOT_PROFILE}/SOUL.md"

if [ ! -f "${SOUL_SRC}" ]; then
    echo "SKIP: template not found at ${SOUL_SRC} (nothing to install)"
elif [ -f "${SOUL_DEST}" ]; then
    echo "EXISTS: ${SOUL_DEST} already present — leaving untouched (live file may hold learned content)"
    if command -v diff &>/dev/null; then
        if diff -q "${SOUL_DEST}" "${SOUL_SRC}" &>/dev/null; then
            echo "  (no differences vs the template)"
        else
            echo "  To review differences: diff ${SOUL_DEST} ${SOUL_SRC}"
        fi
    fi
else
    # NOTE: bash mis-parses nested braces in the default word
    # (${VAR:-{{X}}} appends "}}"), so placeholders are passed via variables.
    SOUL_PH_NAME="{{USER_NAME}}"
    SOUL_PH_EMAIL="{{USER_EMAIL}}"
    SOUL_PH_RESUME="{{RESUME_FILENAME}}"
    SOUL_USER_NAME="${SOUL_USER_NAME:-${SOUL_PH_NAME}}"
    SOUL_USER_EMAIL="${SOUL_USER_EMAIL:-${SOUL_PH_EMAIL}}"
    SOUL_RESUME_FILENAME="${SOUL_RESUME_FILENAME:-${SOUL_PH_RESUME}}"
    # Escape '&' for sed replacement and use '|' as the delimiter (paths/emails
    # contain '/'; '&' would otherwise be re-expanded by sed).
    sed -e "s|{{USER_NAME}}|${SOUL_USER_NAME//&/\\&}|g" \
        -e "s|{{USER_EMAIL}}|${SOUL_USER_EMAIL//&/\\&}|g" \
        -e "s|{{RESUME_FILENAME}}|${SOUL_RESUME_FILENAME//&/\\&}|g" \
        "${SOUL_SRC}" > "${SOUL_DEST}"
    echo "RENDERED: ${SOUL_DEST} (from ${SOUL_SRC})"
fi
echo ""

# Verify SOUL.md presence and warn on unsubstituted placeholders
echo "=== SOUL.md verification ==="
if [ -f "${SOUL_DEST}" ]; then
    echo "OK: SOUL.md present at ${SOUL_DEST}"
    unresolved="$(grep -o '{{[A-Z_]*}}' "${SOUL_DEST}" || true)"
    if [ -n "${unresolved}" ]; then
        echo "WARNING: FILL-ME — unsubstituted placeholders remain (set SOUL_USER_NAME, SOUL_USER_EMAIL, SOUL_RESUME_FILENAME and re-run, or edit by hand):"
        printf '  %s\n' ${unresolved} | sort -u
    else
        echo "OK: no unsubstituted placeholders"
    fi
else
    echo "WARNING: SOUL.md not found at ${SOUL_DEST}"
fi
echo ""

# Install CDP daemon systemd unit (opt-in service — copied, never auto-enabled)
echo "=== CDP daemon unit ==="
SYSTEMD_USER_DIR="${SYSTEMD_USER_DIR:-$HOME/.config/systemd/user}"
DAEMON_UNIT_SRC="${SKILLS_SRC}/cdp-daemon/jobhunter-cdp-daemon.service"
DAEMON_UNIT_DEST="${SYSTEMD_USER_DIR}/jobhunter-cdp-daemon.service"
if [ -f "${DAEMON_UNIT_SRC}" ]; then
    mkdir -p "${SYSTEMD_USER_DIR}"
    cp "${DAEMON_UNIT_SRC}" "${DAEMON_UNIT_DEST}"
    echo "  unit -> ${DAEMON_UNIT_DEST}"
    if command -v systemctl &>/dev/null; then
        if systemctl --user daemon-reload 2>/dev/null; then
            echo "  daemon-reload: OK"
        else
            echo "  daemon-reload: skipped (no user systemd running)"
        fi
    else
        echo "  systemctl not found — skipping daemon-reload"
    fi
    echo "  enable when needed (opt-in, NOT automatic):"
    echo "    systemctl --user enable --now jobhunter-cdp-daemon.service"
else
    echo "  (no daemon unit in repo — skipping)"
fi
echo ""

echo "=== Done ==="
echo "Skills installed under: ${SKILLS_DEST}/<skill-name>/ (per-skill layout)"
echo "Memory files will be written to: ${MEMORY_DIR}/"
echo "Standing instructions installed at: ${SOUL_DEST}"
echo "CDP daemon unit installed at: ${SYSTEMD_USER_DIR}/jobhunter-cdp-daemon.service (enable with: systemctl --user enable --now jobhunter-cdp-daemon.service)"
