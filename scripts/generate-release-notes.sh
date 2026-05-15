#!/usr/bin/env bash
# generate-release-notes.sh — Automatic release notes generator
# Usage: ./scripts/generate-release-notes.sh <version-tag>
# Called by Jenkins prod pipeline

set -euo pipefail

VERSION="${1:-v1.0.0}"
DATE=$(date '+%Y-%m-%d')
DEPLOYER="${BUILD_USER:-Jenkins}"
OUTPUT_FILE="RELEASE_NOTES_${VERSION}.md"

echo "Generating release notes for ${VERSION}..."

# Get commits since last tag
LAST_TAG=$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null || echo "")
if [ -z "$LAST_TAG" ]; then
  COMMIT_LOG=$(git log --oneline -20)
else
  COMMIT_LOG=$(git log --oneline "${LAST_TAG}..HEAD")
fi

# Categorize commits
FEATURES=$(echo "$COMMIT_LOG"     | grep -E "^[a-f0-9]+ feat"     | sed 's/^[a-f0-9]* /- /' || echo "")
FIXES=$(echo "$COMMIT_LOG"        | grep -E "^[a-f0-9]+ fix"      | sed 's/^[a-f0-9]* /- /' || echo "")
BREAKING=$(echo "$COMMIT_LOG"     | grep -iE "BREAKING"            | sed 's/^[a-f0-9]* /- /' || echo "")
TESTS=$(echo "$COMMIT_LOG"        | grep -E "^[a-f0-9]+ test"     | sed 's/^[a-f0-9]* /- /' || echo "")
DOCS=$(echo "$COMMIT_LOG"         | grep -E "^[a-f0-9]+ docs"     | sed 's/^[a-f0-9]* /- /' || echo "")
OTHER=$(echo "$COMMIT_LOG"        | grep -Ev "^[a-f0-9]+ (feat|fix|test|docs|BREAKING|refactor)" | sed 's/^[a-f0-9]* /- /' || echo "")

# Image digests (attempt docker inspect; fallback to IMAGE_TAG)
TAG="${IMAGE_TAG:-latest}"
get_digest() {
  docker inspect --format='{{index .RepoDigests 0}}' "jrivera340/$1:${TAG}" 2>/dev/null || echo "jrivera340/$1:${TAG}"
}

AUTH_DIGEST=$(get_digest "circleguard-auth-service")
IDENTITY_DIGEST=$(get_digest "circleguard-identity-service")
FORM_DIGEST=$(get_digest "circleguard-form-service")
PROMOTION_DIGEST=$(get_digest "circleguard-promotion-service")
GATEWAY_DIGEST=$(get_digest "circleguard-gateway-service")
NOTIFICATION_DIGEST=$(get_digest "circleguard-notification-service")

cat > "$OUTPUT_FILE" << EOF
# CircleGuard Release ${VERSION}

**Date:** ${DATE}
**Deployed by:** ${DEPLOYER}
**Build:** ${BUILD_NUMBER:-N/A} | **Branch:** ${BRANCH_NAME:-main}

---

## Services Deployed

| Service | Image | Tag |
|---|---|---|
| auth-service | jrivera340/circleguard-auth-service | ${TAG} |
| identity-service | jrivera340/circleguard-identity-service | ${TAG} |
| form-service | jrivera340/circleguard-form-service | ${TAG} |
| promotion-service | jrivera340/circleguard-promotion-service | ${TAG} |
| gateway-service | jrivera340/circleguard-gateway-service | ${TAG} |
| notification-service | jrivera340/circleguard-notification-service | ${TAG} |

---

## Changes

### ✨ New Features
${FEATURES:-_None in this release._}

### 🐛 Bug Fixes
${FIXES:-_None in this release._}

### ⚠️ Breaking Changes
${BREAKING:-_None in this release._}

### 🧪 Tests
${TESTS:-_No test-specific commits._}

### 📚 Documentation
${DOCS:-_No documentation commits._}

### 🔧 Other Changes
${OTHER:-_None._}

---

## Test Results Summary

| Test Suite | Result |
|---|---|
| Unit Tests | See Jenkins build #${BUILD_NUMBER:-N/A} |
| Integration Tests | See Jenkins build #${BUILD_NUMBER:-N/A} |
| E2E Tests | See Jenkins build #${BUILD_NUMBER:-N/A} |
| Performance (Locust) | See locust-results.txt artifact |

---

## Rollback Instructions

To rollback this release, run the following commands:

\`\`\`bash
# Identify previous tag
PREV_TAG=\$(git describe --tags --abbrev=0 HEAD^ 2>/dev/null)

# Rollback all services to previous image
kubectl set image deployment/circleguard-auth-service \
    circleguard-auth-service=jrivera340/circleguard-auth-service:\${PREV_TAG} -n circleguard-prod
kubectl set image deployment/circleguard-identity-service \
    circleguard-identity-service=jrivera340/circleguard-identity-service:\${PREV_TAG} -n circleguard-prod
kubectl set image deployment/circleguard-form-service \
    circleguard-form-service=jrivera340/circleguard-form-service:\${PREV_TAG} -n circleguard-prod
kubectl set image deployment/circleguard-promotion-service \
    circleguard-promotion-service=jrivera340/circleguard-promotion-service:\${PREV_TAG} -n circleguard-prod
kubectl set image deployment/circleguard-gateway-service \
    circleguard-gateway-service=jrivera340/circleguard-gateway-service:\${PREV_TAG} -n circleguard-prod
kubectl set image deployment/circleguard-notification-service \
    circleguard-notification-service=jrivera340/circleguard-notification-service:\${PREV_TAG} -n circleguard-prod

# Verify rollback
kubectl rollout status deployment -n circleguard-prod
\`\`\`

**SLA:** Rollback completable en < 3 minutos.

---

_Generated automatically by Jenkins CI/CD. Do not edit manually._
EOF

echo "Release notes written to: ${OUTPUT_FILE}"
cat "$OUTPUT_FILE"
