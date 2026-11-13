#!/bin/bash
set -e

DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
cd "$DIR"

# Load .env if present
if [ -f "$DIR/.env" ]; then
    set -a
    source "$DIR/.env"
    set +a
fi

# Locate Java 8
if [ -n "$JAVA_HOME" ] && "$JAVA_HOME/bin/java" -version 2>&1 | grep -q '1\.8'; then
    JAVA_BIN="$JAVA_HOME/bin/java"
elif [ -x "/home/minjxnx/.jdks/azul-1.8.0_512/bin/java" ]; then
    export JAVA_HOME="/home/minjxnx/.jdks/azul-1.8.0_512"
    JAVA_BIN="$JAVA_HOME/bin/java"
elif command -v java >/dev/null 2>&1 && java -version 2>&1 | grep -q '1\.8'; then
    JAVA_BIN="$(command -v java)"
else
    echo "ERROR: Java 8 (1.8) is required to run Fineract. Please install or set JAVA_HOME to a Java 8 JDK."
    exit 1
fi

echo "Using Java: $($JAVA_BIN -version 2>&1 | head -n 1)"

# Check if build artifact exists; build if missing
if [ ! -f "$DIR/build/libs/fineract-provider.jar" ] || [ ! -d "$DIR/build/libs/lib" ]; then
    echo "Building fineract-provider jar and dependencies..."
    cd "$DIR/fineract-provider"
    ./gradlew jar copyDeps
    cd "$DIR"
fi

echo "Starting Fineract connected to $DS_HOST:$DS_PORT (DB: $DS_DATABASE)..."

exec "$JAVA_BIN" \
  -Dfineract.datasource.host="${DS_HOST:-vitess.qa.evokelabs.io}" \
  -Dfineract.datasource.port="${DS_PORT:-15306}" \
  -Dfineract.datasource.username="${DS_USERNAME:-minjanar}" \
  -Dfineract.datasource.password="${DS_PASSWORD:-bHvn5MEl4BNi}" \
  -Dfineract.datasource.db="${FINERACT_TENANTS_DB:-tenants}" \
  -Dfineract.tenantdb.fixup=false \
  -DEvokeDisableScheduler \
  -Dfineract.datasource.subprotocol=mysql \
  -Xms2g -Xmx4g \
  -cp "build/libs/fineract-provider.jar:build/libs/lib/*" \
  org.apache.fineract.ServerApplication "$@"
