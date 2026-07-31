#!/usr/bin/env bash
# ============================================================
#  sapient-harness-java — Linux/macOS one-shot build script
# ============================================================
#  Companion to build.bat. Same modes, same behaviour.
#
#  Usage:
#    ./build.sh             clean + install + test (default)
#    ./build.sh fast        skip tests
#    ./build.sh package     clean + package (no install)
#    ./build.sh run-cli     build then launch CLI help
#    ./build.sh run-ui      build then launch the JavaFX UI
#    ./build.sh wipe        nuke target/ + local .m2 install
# ============================================================

set -euo pipefail

# --- Change to script's own directory ---
cd "$(dirname "${BASH_SOURCE[0]}")"

printf '\n=== sapient-harness-java build script ===\n'
printf 'Working directory: %s\n\n' "$(pwd)"

# --- Sanity: Java 17+ ---
if ! command -v java >/dev/null 2>&1; then
    echo "[ERROR] java not found on PATH."
    echo "        Install a JDK 17+ (e.g. 'apt install openjdk-17-jdk' or brew install openjdk@17)"
    exit 1
fi

# --- Sanity: Maven 3.9+ ---
if ! command -v mvn >/dev/null 2>&1; then
    echo "[ERROR] mvn not found on PATH."
    echo "        Install Maven (e.g. 'apt install maven' or 'brew install maven')"
    exit 1
fi

echo "-- java version --"; java -version; echo
echo "-- mvn version --"; mvn -v; echo

MODE="${1:-install}"

report() {
    echo
    echo "============================================================"
    echo "  BUILD SUCCESS"
    echo "============================================================"
    echo
    echo "Runnable artifacts:"
    if [ -f sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar ]; then
        size=$(stat -c%s sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar 2>/dev/null || stat -f%z sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar)
        echo "  CLI:  $(pwd)/sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar   (${size} bytes)"
    fi
    if [ -f sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar ]; then
        size=$(stat -c%s sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar 2>/dev/null || stat -f%z sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar)
        echo "  UI:   $(pwd)/sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar   (${size} bytes)"
    fi
    echo
    echo "Quick-launch:"
    echo "  java -jar sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar receive --port 12000"
    echo "  java -jar sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar send --host 127.0.0.1 --port 12000"
    echo "  java -jar sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar"
    echo
}

case "$MODE" in
    install)
        echo "=== Running: mvn clean install ==="; echo
        mvn clean install
        report
        ;;
    fast)
        echo "=== Running: mvn clean install -DskipTests ==="; echo
        mvn clean install -DskipTests
        report
        ;;
    package)
        echo "=== Running: mvn clean package ==="; echo
        mvn clean package
        report
        ;;
    run-cli)
        echo "=== Building then launching CLI ==="; echo
        mvn -q clean install -DskipTests
        echo
        java -jar sapient-cli/target/sapient-cli-0.1.0-SNAPSHOT.jar
        ;;
    run-ui)
        echo "=== Building then launching JavaFX UI ==="; echo
        mvn -q clean install -DskipTests
        echo
        java -jar sapient-ui/target/sapient-ui-0.1.0-SNAPSHOT.jar &
        echo "UI launched in background (PID $!)"
        ;;
    wipe)
        echo "=== Wiping target/ folders + local .m2 install ==="
        for d in sapient-core sapient-net sapient-cli sapient-ui; do
            if [ -d "$d/target" ]; then
                echo "  rm -rf $d/target"
                rm -rf "$d/target"
            fi
        done
        if [ -d "$HOME/.m2/repository/com/mdudel/sapient" ]; then
            echo "  rm -rf $HOME/.m2/repository/com/mdudel/sapient"
            rm -rf "$HOME/.m2/repository/com/mdudel/sapient"
        fi
        echo
        echo "Wipe complete. Run './build.sh' to rebuild from scratch."
        ;;
    help|-h|--help)
        cat <<'USAGE'

Usage:
   ./build.sh              clean + install + test (default)
   ./build.sh fast         skip tests
   ./build.sh package      clean + package (no local install)
   ./build.sh run-cli      build then launch CLI (prints help)
   ./build.sh run-ui       build then launch the JavaFX UI
   ./build.sh wipe         nuke target/ folders + local .m2 install
   ./build.sh help         this message

USAGE
        ;;
    *)
        echo "[ERROR] Unknown mode: $MODE"
        echo "Run './build.sh help' for options."
        exit 2
        ;;
esac
