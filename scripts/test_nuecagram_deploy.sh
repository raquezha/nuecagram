#!/usr/bin/env bash
set -euo pipefail

script="$(dirname "$0")/nuecagram-deploy.sh"
valid_digest="raquezha/nuecagram@sha256:$(printf 'a%.0s' {1..64})"
valid_sha_tag="raquezha/nuecagram:sha-1234567890ab"
valid_semver="raquezha/nuecagram:v0.11.0"

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

tmp_dir="$(mktemp -d)"
trap 'rm -rf "$tmp_dir"' EXIT

mock_env="$tmp_dir/.env"
mock_compose="$tmp_dir/compose.yaml"
mock_state="$tmp_dir/previous-image"

cat > "$mock_env" <<'EOF'
NUECAGRAM_PUBLIC_URL=http://localhost
NUECAGRAM_IMAGE=raquezha/nuecagram:old
EOF
chmod 600 "$mock_env"
touch "$mock_compose"

TEST_SKIP_ROOT_CHECK=1 \
TEST_SKIP_DOCKER_CHECK=1 \
TEST_DRY_RUN=1 \
ENV_FILE="$mock_env" \
COMPOSE_FILE="$mock_compose" \
STATE_FILE="$mock_state" \
  "$script" --mode deploy --image "$valid_sha_tag" >/dev/null

if ! grep -q "^NUECAGRAM_IMAGE=$valid_sha_tag$" "$mock_env"; then
  echo "env file was not updated with the new image reference" >&2
  exit 1
fi

printf 'deploy input validation and env-update tests passed\n'
