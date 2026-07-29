#!/usr/bin/env bash
set -euo pipefail

script="$(dirname "$0")/nuecagram-deploy.sh"
valid_digest="raquezha/nuecagram@sha256:$(printf 'a%.0s' {1..64})"

if "$script" --mode deploy --image "bad'; id; #" >/dev/null 2>&1; then
  echo "unsafe image was accepted" >&2
  exit 1
fi
if "$script" --mode rollback --image latest >/dev/null 2>&1; then
  echo "mutable rollback image was accepted" >&2
  exit 1
fi
if "$script" --mode invalid --image "$valid_digest" >/dev/null 2>&1; then
  echo "invalid mode was accepted" >&2
  exit 1
fi

printf 'deploy input validation passed\n'
