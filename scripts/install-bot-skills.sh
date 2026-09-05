#!/usr/bin/env bash
# install-bot-skills.sh
#
# Installs Hermes Agent bot skills from the repository into the bot profile
# directory (~/.hermes/profiles/jobhunter-bot/skills/<skill-name>/).
#
# Usage:
#   bash scripts/install-bot-skills.sh
#
# Safe to re-run: overwrites existing skill files.
# New skills are picked up automatically: every subdirectory of skills/
# containing a SKILL.md is installed under its own name.

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

echo "=== Done ==="
echo "Skills installed under: ${SKILLS_DEST}/<skill-name>/ (per-skill layout)"
echo "Memory files will be written to: ${MEMORY_DIR}/"
