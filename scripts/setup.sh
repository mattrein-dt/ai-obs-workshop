#!/bin/bash
# setup.sh — Bootstrap the AI Observability Workshop environment.
# Creates a k3d cluster, builds service images, and deploys everything.
set -euo pipefail

CLUSTER_NAME="daystrom-mini"
REGISTRY_NAME="daystrom-mini-registry"
REGISTRY_PORT=5111

echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       AI Observability Workshop — Cluster Setup             ║"
echo "╚══════════════════════════════════════════════════════════════╝"

# ─── Preflight checks ───────────────────────────────────────────────
for cmd in k3d kubectl docker kustomize; do
  if ! command -v "$cmd" &>/dev/null; then
    echo "ERROR: $cmd is required but not found. Run post-create.sh first."
    exit 1
  fi
done

# ─── Dynatrace credentials ──────────────────────────────────────────
if [[ -z "${DT_ENDPOINT:-}" || -z "${DT_API_TOKEN:-}" ]]; then
  echo ""
  echo "⚠  DT_ENDPOINT and/or DT_API_TOKEN not set."
  echo "   Traces will go to the OTel collector but NOT be exported to Dynatrace."
  echo "   Set these as Codespace secrets for full observability."
  echo ""
  DT_ENDPOINT="${DT_ENDPOINT:-http://localhost:4318}"
  DT_API_TOKEN="${DT_API_TOKEN:-none}"
fi

# ─── Create local registry ──────────────────────────────────────────
if ! k3d registry list | grep -q "$REGISTRY_NAME"; then
  echo "Creating local registry..."
  k3d registry create "$REGISTRY_NAME" --port "$REGISTRY_PORT"
fi

# ─── Create k3d cluster ─────────────────────────────────────────────
if k3d cluster list | grep -q "$CLUSTER_NAME"; then
  echo "Cluster '$CLUSTER_NAME' already exists. Skipping creation."
else
  echo "Creating k3d cluster '$CLUSTER_NAME'..."
  k3d cluster create "$CLUSTER_NAME" \
    --registry-use "k3d-${REGISTRY_NAME}:${REGISTRY_PORT}" \
    --agents 1 \
    --port "3000:30000@server:0" \
    --port "8080:30080@server:0" \
    --k3s-arg "--disable=traefik@server:0" \
    --wait
fi

echo "Cluster ready. Context:"
kubectl cluster-info

# ─── Build and push service images ──────────────────────────────────
# localhost for host-side docker push; k3d hostname for in-cluster pulls
HOST_REGISTRY="localhost:${REGISTRY_PORT}"
CLUSTER_REGISTRY="k3d-${REGISTRY_NAME}:${REGISTRY_PORT}"

echo ""
echo "Building service images..."

build_and_push() {
  local svc="$1"
  local dockerfile="$2"
  
  echo "  Building $svc..."
  docker build -t "${HOST_REGISTRY}/daystrom-mini/${svc}:latest" \
    -f "${dockerfile}" . --quiet
  docker push "${HOST_REGISTRY}/daystrom-mini/${svc}:latest"
}

# Java services (Spring Boot)
build_and_push "request-orchestrator" "services/java/request-orchestrator/Dockerfile"
build_and_push "prompt-cache" "services/java/prompt-cache/Dockerfile"
build_and_push "inference-pool" "services/java/inference-pool/Dockerfile"

# Python services
build_and_push "model-router" "services/python/model-router/Dockerfile"
build_and_push "safety-gateway" "services/python/safety-gateway/Dockerfile"
build_and_push "mock-server" "services/python/mock-server/Dockerfile"

# Web app (TypeScript)
build_and_push "web-app" "services/typescript/web-app/Dockerfile"

# ─── Update image references in kustomization ───────────────────────
echo ""
echo "Deploying to cluster..."

# Patch the DT secret with actual values
kubectl create namespace daystrom-mini --dry-run=client -o yaml | kubectl apply -f -

kubectl create secret generic dt-credentials \
  --namespace daystrom-mini \
  --from-literal="DT_ENDPOINT=${DT_ENDPOINT}" \
  --from-literal="DT_API_TOKEN=${DT_API_TOKEN}" \
  --dry-run=client -o yaml | kubectl apply -f -

# Apply with image override to local registry (use cluster-side hostname)
kubectl apply -k k8s/base/ \
  --dry-run=client -o yaml | \
  sed "s|daystrom-mini/|${CLUSTER_REGISTRY}/daystrom-mini/|g" | \
  kubectl apply -f -

# ─── Wait for rollout ───────────────────────────────────────────────
echo ""
echo "Waiting for deployments..."
kubectl -n daystrom-mini rollout status deployment/redis --timeout=60s
kubectl -n daystrom-mini rollout status deployment/postgres --timeout=60s
kubectl -n daystrom-mini rollout status deployment/otel-collector --timeout=60s
kubectl -n daystrom-mini rollout status deployment/mock-safety --timeout=60s
kubectl -n daystrom-mini rollout status deployment/llama-cpp --timeout=180s
kubectl -n daystrom-mini rollout status deployment/request-orchestrator --timeout=120s
kubectl -n daystrom-mini rollout status deployment/prompt-cache --timeout=120s
kubectl -n daystrom-mini rollout status deployment/safety-gateway --timeout=120s
kubectl -n daystrom-mini rollout status deployment/model-router --timeout=120s
kubectl -n daystrom-mini rollout status deployment/inference-pool --timeout=120s
kubectl -n daystrom-mini rollout status deployment/web-app --timeout=120s

echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║  ✓ All services deployed!                                   ║"
echo "║                                                            ║"
echo "║  Web UI:  http://localhost:3000                            ║"
echo "║  API:     http://localhost:8080                            ║"
echo "║                                                            ║"
echo "║  kubectl -n daystrom-mini get pods                           ║"
echo "╚══════════════════════════════════════════════════════════════╝"
