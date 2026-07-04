#!/usr/bin/env sh
# Installs the Penpot Skills dock layer into a running Penpot frontend
# container (prebuilt docker image), so the plugin renders as an integrated
# side panel instead of a floating window. Idempotent; rerun after the
# container is recreated.
set -eu

CONTAINER="${1:-penpot-penpot-frontend-1}"
DIR="$(cd "$(dirname "$0")/.." && pwd)"

VERSION="$(date +%s)"
docker cp "$DIR/dock/dock.js" "$CONTAINER:/var/www/app/dock.js"
docker exec "$CONTAINER" sh -c "
  sed -i 's#<script src=\"/dock.js[^\"]*\"></script>##' /var/www/app/index.html &&
  sed -i 's#</body>#<script src=\"/dock.js?v=$VERSION\"></script></body>#' /var/www/app/index.html"

echo "Dock layer installed in $CONTAINER — reload Penpot."
