#!/usr/bin/env bash
#
# release.sh — manual, CI-free release pipeline for the AllStak Java SDK.
#
# Builds every module (core, spring-boot-starter, BOM) at the requested
# version, signs with GPG, uploads to Sonatype Central, tags the commit,
# bumps to the next -SNAPSHOT, and prints the runbook for the post-release
# verification step.
#
# Designed to run from a developer workstation: no CI, no GitHub Actions,
# no Jenkins. The only external dependencies are local `mvn`, `gpg`, and a
# Sonatype Central account configured in `~/.m2/settings.xml` under the
# server id `central` (see docs/RELEASE.md for first-time setup).
#
# Usage:
#   scripts/release.sh <new-version>           # publish <new-version>
#   scripts/release.sh --dry-run <new-version> # set versions + verify, no deploy
#
# Required env vars:
#   GPG_KEY_ID         The GPG key fingerprint or short id used to sign
#                      artifacts. Must be on the local keyring.
#   GPG_PASSPHRASE     Passphrase for that key. Used non-interactively via
#                      gpg --pinentry-mode loopback.
#
# Optional env vars:
#   SKIP_TESTS=1       Skip the `mvn test` pre-flight (not recommended).
#   NEXT_DEV_VERSION   Override the auto-computed next dev version
#                      (default: <new-version> + patch + "-SNAPSHOT").
#   ALLSTAK_RELEASE_REMOTE  Remote name to push tag + version commits.
#                           Default: origin.

set -euo pipefail

# ── argument parsing ─────────────────────────────────────────────────────
DRY_RUN=0
NEW_VERSION=""
for arg in "$@"; do
  case "$arg" in
    --dry-run) DRY_RUN=1 ;;
    -h|--help)
      sed -n '2,32p' "$0"
      exit 0
      ;;
    *)
      if [[ -z "$NEW_VERSION" ]]; then
        NEW_VERSION="$arg"
      else
        echo "ERROR: unexpected argument '$arg'" >&2
        exit 2
      fi
      ;;
  esac
done

if [[ -z "$NEW_VERSION" ]]; then
  echo "ERROR: missing <new-version> argument. Run with --help for usage." >&2
  exit 2
fi

if [[ ! "$NEW_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[A-Za-z0-9.]+)?$ ]]; then
  echo "ERROR: <new-version> must look like 0.2.0 or 0.2.0-rc1, got '$NEW_VERSION'" >&2
  exit 2
fi

# ── helpers ──────────────────────────────────────────────────────────────
log()    { printf '\033[36m[release]\033[0m %s\n' "$*"; }
warn()   { printf '\033[33m[release]\033[0m %s\n' "$*"; }
die()    { printf '\033[31m[release]\033[0m %s\n' "$*" >&2; exit 1; }

REMOTE="${ALLSTAK_RELEASE_REMOTE:-origin}"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

# Current version is the parent pom's <version>. We grep instead of using
# `mvn help:evaluate` so this works before any plugin downloads complete.
CURRENT_VERSION=$(awk '/<version>/{print; exit}' pom.xml | sed -E 's|.*<version>([^<]+)</version>.*|\1|')
log "current version: $CURRENT_VERSION"
log "new version:     $NEW_VERSION"
[[ "$DRY_RUN" -eq 1 ]] && warn "dry-run mode — no deploy, no tag, no push"

# ── pre-flight ────────────────────────────────────────────────────────────
log "pre-flight checks…"

command -v mvn >/dev/null || die "mvn not on PATH"
command -v gpg >/dev/null || die "gpg not on PATH"
command -v git >/dev/null || die "git not on PATH"

[[ -n "${GPG_KEY_ID:-}" ]]     || die "GPG_KEY_ID env var is required"
[[ -n "${GPG_PASSPHRASE:-}" ]] || die "GPG_PASSPHRASE env var is required"

if ! gpg --list-secret-keys "$GPG_KEY_ID" >/dev/null 2>&1; then
  die "GPG secret key '$GPG_KEY_ID' not found on local keyring"
fi

if [[ -n "$(git status --porcelain)" ]]; then
  die "working tree is not clean — commit or stash changes first"
fi

CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$CURRENT_BRANCH" != "main" && "$DRY_RUN" -eq 0 ]]; then
  die "must release from 'main' (currently on '$CURRENT_BRANCH'). Use --dry-run to test elsewhere."
fi

TAG="v$NEW_VERSION"
if git rev-parse "$TAG" >/dev/null 2>&1; then
  die "tag $TAG already exists locally"
fi
if git ls-remote --tags "$REMOTE" "$TAG" 2>/dev/null | grep -q .; then
  die "tag $TAG already exists on remote $REMOTE"
fi

# Settings.xml must contain a <server> with id=central for the
# central-publishing-maven-plugin upload step.
SETTINGS_FILE="${MVN_SETTINGS_FILE:-$HOME/.m2/settings.xml}"
if [[ ! -f "$SETTINGS_FILE" ]] || ! grep -q '<id>central</id>' "$SETTINGS_FILE"; then
  die "no <server><id>central</id></server> in $SETTINGS_FILE (see docs/RELEASE.md)"
fi

log "pre-flight OK"

# ── version bump ──────────────────────────────────────────────────────────
log "setting version to $NEW_VERSION across modules…"
./mvnw -q versions:set -DnewVersion="$NEW_VERSION" -DgenerateBackupPoms=false
log "committing version bump (will be amended onto the release tag)"
git add -A
git commit -m "release: $NEW_VERSION" >/dev/null

# ── tests ─────────────────────────────────────────────────────────────────
if [[ "${SKIP_TESTS:-0}" != "1" ]]; then
  log "running full test suite…"
  ./mvnw -q test
else
  warn "SKIP_TESTS=1 — skipping tests (not recommended)"
fi

# ── deploy ────────────────────────────────────────────────────────────────
if [[ "$DRY_RUN" -eq 1 ]]; then
  log "dry-run: verifying release profile build (no upload)…"
  ./mvnw -q -P release clean verify \
    -Dgpg.keyname="$GPG_KEY_ID" \
    -Dgpg.passphrase="$GPG_PASSPHRASE"
  log "dry-run OK; reverting version commit"
  git reset --hard HEAD~1
  exit 0
fi

log "deploying to Sonatype Central (autoPublish=true)…"
./mvnw -P release clean deploy \
  -Dgpg.keyname="$GPG_KEY_ID" \
  -Dgpg.passphrase="$GPG_PASSPHRASE" \
  -s "$SETTINGS_FILE"

# ── tag + push ────────────────────────────────────────────────────────────
log "tagging $TAG and pushing to $REMOTE"
git tag -a "$TAG" -m "AllStak Java SDK $NEW_VERSION"
git push "$REMOTE" "$CURRENT_BRANCH"
git push "$REMOTE" "$TAG"

# ── bump to next -SNAPSHOT ────────────────────────────────────────────────
if [[ -z "${NEXT_DEV_VERSION:-}" ]]; then
  BASE="${NEW_VERSION%%-*}"
  IFS=. read -r MAJ MIN PATCH <<<"$BASE"
  NEXT_DEV_VERSION="${MAJ}.${MIN}.$((PATCH + 1))-SNAPSHOT"
fi
log "bumping to $NEXT_DEV_VERSION"
./mvnw -q versions:set -DnewVersion="$NEXT_DEV_VERSION" -DgenerateBackupPoms=false
git add -A
git commit -m "release: bump to $NEXT_DEV_VERSION" >/dev/null
git push "$REMOTE" "$CURRENT_BRANCH"

log "DONE."
log "Released to Maven Central: sa.allstak:allstak-java-core:$NEW_VERSION"
log "                            sa.allstak:allstak-spring-boot-starter:$NEW_VERSION"
log "                            sa.allstak:allstak-bom:$NEW_VERSION"
log "Tag: $TAG"
log "Dev: now on $NEXT_DEV_VERSION"
log ""
log "Verify it landed:"
log "  curl -s https://repo1.maven.org/maven2/sa/allstak/allstak-java-core/$NEW_VERSION/ | head"
log "(Central index may lag ~10-30 minutes after publish-OK)"
