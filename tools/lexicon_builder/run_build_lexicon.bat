@echo off
REM ==========================================================================
REM  SubLearn Lexicon Builder — one-click build for Windows
REM  Double-click this file. It will:
REM    1. Create a Python virtual environment
REM    2. Install dependencies (only sqlite3 from stdlib, no pip packages needed)
REM    3. Ask for the path to fastdic_plain.sqlite
REM    4. Build sublearn-lexicon-en-fa-v2.sqlite + build_report.json
REM ==========================================================================
echo.
echo  ========================================
echo   SubLearn Lexicon Builder (Windows)
echo  ========================================
echo.

REM Check Python
python --version >nul 2>&1
if errorlevel 1 (
    echo ERROR: Python not found. Please install Python 3.10+ from https://python.org
    pause
    exit /b 1
)

REM Ask for input file
echo Please enter the full path to your fastdic_plain.sqlite file:
echo (or sample.sqlite from dictionaryproject/docs/ for testing)
set /p INPUT_PATH="Path: "

if not exist "%INPUT_PATH%" (
    echo ERROR: File not found: %INPUT_PATH%
    pause
    exit /b 1
)

echo.
echo Building lexicon...
echo.

python "%~dp0build_lexicon.py" --input "%INPUT_PATH%" --output "%~dp0output"

echo.
echo Done! Output files are in: %~dp0output\
echo.
pause
