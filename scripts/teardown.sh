#!/bin/bash
# teardown.sh — Remove the k3d cluster and registry.
set -euo pipefail

CLUSTER_NAME="daystrom-mini"
REGISTRY_NAME="daystrom-mini-registry"

echo "Tearing down workshop environment..."

if k3d cluster list 2>/dev/null | grep -q "$CLUSTER_NAME"; then
  k3d cluster delete "$CLUSTER_NAME"
  echo "Cluster '$CLUSTER_NAME' deleted."
else
  echo "Cluster '$CLUSTER_NAME' not found."
fi

if k3d registry list 2>/dev/null | grep -q "$REGISTRY_NAME"; then
  k3d registry delete "k3d-${REGISTRY_NAME}"
  echo "Registry deleted."
fi

echo "Done."
