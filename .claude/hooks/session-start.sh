#!/bin/bash
# Installs jbang so the "quarkus-agent" MCP server declared in .mcp.json
# (command: jbang, args: ["quarkus-agent-mcp@quarkusio"]) can actually launch.
set -uo pipefail

if [ "${CLAUDE_CODE_REMOTE:-}" != "true" ]; then
  exit 0
fi

JBANG_DIR="${JBANG_DIR:-$HOME/.jbang}"

if command -v jbang >/dev/null 2>&1; then
  echo "jbang already on PATH: $(command -v jbang)"
  exit 0
fi

if [ -x "$JBANG_DIR/bin/jbang" ]; then
  echo "jbang already installed at $JBANG_DIR/bin/jbang"
  echo "export PATH=\"$JBANG_DIR/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
  exit 0
fi

echo "Installing jbang..."
if curl -Ls --max-time 30 https://sh.jbang.dev -o /tmp/jbang-install.sh \
  && bash /tmp/jbang-install.sh app setup >/tmp/jbang-install.log 2>&1; then
  echo "jbang installed via sh.jbang.dev"
else
  echo "WARN: jbang install via sh.jbang.dev failed (see /tmp/jbang-install.log)." \
       "The quarkus-agent MCP server in .mcp.json will not be available this session." >&2
  exit 0
fi

if [ -x "$JBANG_DIR/bin/jbang" ]; then
  echo "export PATH=\"$JBANG_DIR/bin:\$PATH\"" >> "$CLAUDE_ENV_FILE"
  echo "jbang ready: $("$JBANG_DIR/bin/jbang" --version 2>/dev/null || echo unknown version)"
else
  echo "WARN: jbang install script ran but $JBANG_DIR/bin/jbang was not found." >&2
fi
