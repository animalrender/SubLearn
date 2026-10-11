#!/usr/bin/env bash
# ==========================================================================
#  SubLearn Lexicon Builder — one-click build for macOS/Linux
#  Run: bash run_build_lexicon.sh
#  It will:
#    1. Check Python 3.10+
#    2. Ask for the path to fastdic_plain.sqlite
#    3. Build sublearn-lexicon-en-fa-v2.sqlite + build_report.json
# ==========================================================================
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"

echo ""
echo " ========================================"
echo "  SubLearn Lexicon Builder"
echo " ========================================"
echo ""

# Check Python
if ! command -v python3 &>/dev/null; then
    echo "ERROR: python3 not found. Please install Python 3.10+."
    exit 1
fi

PY_VERSION=$(python3 -c 'import sys; print(f"{sys.version_info.major}.{sys.version_info.minor}")')
echo "Python version: $PY_VERSION"

# Ask for input file
echo ""
echo "Please enter the full path to your fastdic_plain.sqlite file:"
echo "(or sample.sqlite from dictionaryproject/docs/ for testing)"
read -r -p "Path: " INPUT_PATH

if [ ! -f "$INPUT_PATH" ]; then
    echo "ERROR: File not found: $INPUT_PATH"
    exit 1
fi

echo ""
echo "Building lexicon..."
echo ""

python3 "$SCRIPT_DIR/build_lexicon.py" --input "$INPUT_PATH" --output "$SCRIPT_DIR/output"

echo ""
echo "Done! Output files are in: $SCRIPT_DIR/output/"
echo ""
