#!/usr/bin/env bash
set -euo pipefail

cd "$(dirname "$0")/.."

if ! command -v dotnet >/dev/null 2>&1; then
  cat >&2 <<'EOF'
dotnet was not found.

Install the .NET SDK, then run this script again:
  brew install --cask dotnet-sdk

This project uses dotnet only to run ReportGenerator over JaCoCo's XML output.
EOF
  exit 127
fi

./mvnw verify
dotnet tool restore
dotnet tool run reportgenerator \
  -reports:target/site/jacoco/jacoco.xml \
  -targetdir:target/site/coverage \
  -sourcedirs:src/main/java \
  "-reporttypes:Html;HtmlSummary" \
  "-title:invoicegenerator coverage"

echo "ReportGenerator HTML: target/site/coverage/index.html"
