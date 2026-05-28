# Release runbook — AllStak Java SDK

Manual, CI-free release. Every step runs from your workstation using
`scripts/release.sh`. No GitHub Actions, no Jenkins, no Sonatype OSSRH —
the SDK publishes through the **Sonatype Central** portal that replaced
OSSRH in mid-2024.

This file walks through the one-time setup (Sonatype account, GPG, Maven
settings) and the per-release runbook.

## 1. One-time setup

### 1.1 Sonatype Central account

1. Create an account at [https://central.sonatype.com](https://central.sonatype.com).
2. Verify the `sa.allstak` namespace under **Namespaces → Add namespace**.
   Sonatype will ask you to either:
   - prove ownership of `allstak.sa` via DNS TXT record, or
   - create a GitHub repo with a magic name under your verified org.
   DNS is faster; expect a few hours for verification.
3. Generate a **User token** under **View Account → Generate User Token**.
   You'll receive a `username` and a `password` — these are not your
   portal login; they are short-lived API credentials.

### 1.2 GPG key

Releases must be signed. Generate a key (4096-bit RSA, no expiry, or a
1-year expiry you renew):

```bash
gpg --full-generate-key
gpg --list-secret-keys --keyid-format=long
# Note the key id (the part after rsa4096/) — that's your GPG_KEY_ID.

# Publish the public half so Sonatype can verify:
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
gpg --keyserver keys.openpgp.org      --send-keys <KEY_ID>
```

### 1.3 `~/.m2/settings.xml`

Add a `<server>` entry with id `central` containing the Sonatype user
token. Example:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>SONATYPE_USER_TOKEN_USERNAME</username>
      <password>SONATYPE_USER_TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

Mode 0600. Never commit this file.

### 1.4 Local env vars

For each release shell:

```bash
export GPG_KEY_ID='YOUR_KEY_ID'
export GPG_PASSPHRASE='your gpg passphrase'
```

Putting them in `~/.zprofile` is fine if your machine is encrypted. Never
commit them.

## 2. Per-release runbook

The script handles everything. The interactive checks below are what the
script enforces — they're listed here so you know what to fix when one
fails.

```bash
cd /Volumes/M.2/MyProjects/AllStak-Projects/sdks/allstak-java-sdk
git checkout main
git pull --ff-only

# 1. Dry-run first to validate the release profile compiles + signs.
scripts/release.sh --dry-run 0.2.0

# 2. Real release. Sets version, runs tests, deploys, tags, bumps
#    to next -SNAPSHOT, pushes both.
scripts/release.sh 0.2.0
```

### What the script checks

1. `mvn`, `gpg`, `git` are on PATH.
2. `GPG_KEY_ID` is set and the key is on your local keyring.
3. `GPG_PASSPHRASE` is set.
4. Working tree is clean.
5. Current branch is `main` (override with `--dry-run`).
6. The new tag (`vX.Y.Z`) does not exist locally or on the remote.
7. `~/.m2/settings.xml` (or `MVN_SETTINGS_FILE`) has `<id>central</id>`.

### What the script does

1. `versions:set` to the requested version across every module.
2. Commits the version bump.
3. Runs `./mvnw test` (skip with `SKIP_TESTS=1` only when truly necessary).
4. `./mvnw -P release clean deploy`, which:
   - Builds source jars (`maven-source-plugin`).
   - Builds javadoc jars (`maven-javadoc-plugin`, doclint off).
   - Signs every artifact with GPG.
   - Uploads to Sonatype Central via `central-publishing-maven-plugin`
     with `autoPublish=true` and `waitUntil=published`, so the script
     blocks until Maven Central confirms publication.
5. Tags `vX.Y.Z`, pushes the tag and the release commit.
6. Bumps every pom to `X.Y.(Z+1)-SNAPSHOT`, commits, pushes.

### Verifying a release

Central's index updates ~10–30 minutes after publish-OK. To check:

```bash
curl -s https://repo1.maven.org/maven2/sa/allstak/allstak-java-core/0.2.0/ | head
curl -s https://repo1.maven.org/maven2/sa/allstak/allstak-spring-boot-starter/0.2.0/ | head
curl -s https://repo1.maven.org/maven2/sa/allstak/allstak-bom/0.2.0/ | head
```

Each should return an HTML directory listing with the `.jar`, `.pom`,
`.asc` (signature), and `.module` files.

A 404 immediately after the script returns is normal — Sonatype Central
streams the upload to Central, then Central rebuilds its index. The
plugin's `waitUntil=published` already blocks until Sonatype reports the
*upload* is published; the public CDN lag is separate.

## 3. Rolling back a botched release

Maven Central is **immutable**. Once a version is published, it cannot
be deleted, only superseded.

If you publish a broken `0.2.0`:

1. Don't try to delete or re-upload; that just creates a "tampered"
   security incident on the Central side.
2. Cut `0.2.1` immediately with the fix. The first thing in its release
   notes should be "supersedes 0.2.0 — do not use".
3. The Java SDK's own `AllStak.init` will log a warning if the runtime
   detects it was loaded from a yanked version (future enhancement).

The dry-run profile exists precisely to keep this scenario rare.

## 4. Special cases

### 4.1 Releasing from a release-candidate branch

For RC builds (`0.2.0-rc1`, `0.2.0-rc2`, …):

```bash
git checkout -b release-rc1   # only for the RC; never merge into main
scripts/release.sh 0.2.0-rc1
```

The script's `must release from main` gate kicks in for normal versions.
For RCs you can either temporarily disable it (edit the script) or run
with `--dry-run` and do the deploy step by hand. RCs are rare; the
default flow stays on main.

### 4.2 Releasing the BOM only

You only need to ship the BOM separately if the dependencyManagement
shape changes without a code change to the libraries. The standard flow
already publishes the BOM in the same `mvn deploy` invocation, so you
don't normally need this.

```bash
./mvnw -pl allstak-bom -P release clean deploy \
  -Dgpg.keyname="$GPG_KEY_ID" \
  -Dgpg.passphrase="$GPG_PASSPHRASE"
```

### 4.3 Re-signing the keyring after a rotated GPG key

If you rotate `GPG_KEY_ID`, publish the new public key (Section 1.2),
wait ~30 minutes for keyserver replication, and update the env var.
The script reads `GPG_KEY_ID` fresh on every run, so a new value just
works.

## 5. Why no CI?

The user owning the SDK release decision is currently the same person
holding the GPG private key and the Sonatype account credentials.
Putting those in a shared CI runner widens the blast radius without
buying speed (we release on the order of weeks, not minutes).

If/when we adopt CI, a GitHub Actions workflow can call this same
script. The script reads everything from env vars precisely so future
CI integration is a one-line change.
