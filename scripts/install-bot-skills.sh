#!/usr/bin/env bash
# install-bot-skills.sh
#
# Installs Hermes Agent bot skills from the repository into the bot profile
# directory (~/.hermes/profiles/jobhunter-bot/skills/).
#
# Usage:
#   bash scripts/install-bot-skills.sh
#
# Safe to re-run: overwrites existing skill files.

set -euo pipefail

# ---------------------------------------------------------------------------
# Configuration
# ---------------------------------------------------------------------------

HERMES_BASE="${HERMES_BASE:-$HOME/.hermes}"
BOT_PROFILE="${HERMES_BASE}/profiles/jobhunter-bot"
SKILLS_DEST="${BOT_PROFILE}/skills"
MEMORY_DIR="${BOT_PROFILE}/memails"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
SKILL_SRC="${REPO_ROOT}/skills/company-scraper"

# ---------------------------------------------------------------------------
# Preflight
# ---------------------------------------------------------------------------

echo "=== Installing company-scraper skill ==="
echo ""

# Verify source exists
if [ ! -f "${SKILL_SRC}/SKILL.md" ] || [ ! -f "${SKILL_SRC}/scraper.py" ]; then
    echo "ERROR: source skill files not found at ${SKILL_SRC}/"
    echo "  Expected: SKILL.md and scraper.py"
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
# Copy skill files
# ---------------------------------------------------------------------------

echo "Copying skill files ..."
cp "${SKILL_SRC}/SKILL.md" "${SKILLS_DEST}/SKILL.md"
cp "${SKILL_SRC}/scraper.py" "${SKILLS_DEST}/scraper.py"
chmod +x "${SKILLS_DEST}/scraper.py"
echo "  SKILL.md   -> ${SKILLS_DEST}/SKILL.md"
echo "  scraper.py -> ${SKILLS_DEST}/scraper.py (chmod +x)"
echo ""

# ---------------------------------------------------------------------------
# Verify installation
# ---------------------------------------------------------------------------

echo "=== Verification ==="
echo ""
echo "Installed files:"
ls -la "${SKILLS_DEST}/"
echo ""

# Quick syntax check on the script
if command -v python3 &>/dev/null; then
    echo "Python syntax check:"
    python3 -m py_compile "${SKILLS_DEST}/scraper.py" && echo "  scraper.py: OK" || echo "  scraper.py: SYNTAX ERROR"
else
    echo "  python3 not found — skipping syntax check"
fi
echo ""

echo "Memory directory:"
ls -la "${MEMORY_DIR}/" 2>/dev/null || echo "  (empty)"
echo ""

echo "=== Done ==="
echo "Skill installed at: ${SKILLS_DEST}/company-scraper/ (flat layout)"
echo "Memory files will be written to: ${MEMORY_DIR}/"
