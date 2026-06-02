.PHONY: up down build status logs clean

CLUSTER_NAME := daystrom
REGISTRY := localhost:5111

# ─── Primary targets ────────────────────────────────────────────────

## Create k3d cluster and deploy all services
up:
	@bash scripts/setup.sh

## Tear down cluster and registry
down:
	@bash scripts/teardown.sh

## Show pod status
status:
	@kubectl -n daystrom-ai get pods -o wide

## Tail logs for a service (usage: make logs SVC=model-router)
logs:
	@kubectl -n daystrom-ai logs -f deployment/$(SVC) --tail=50

# ─── Build targets ──────────────────────────────────────────────────

## Build all service images
build:
	@echo "Building all images..."
	@docker build -t $(REGISTRY)/daystrom-ai/request-orchestrator:latest -f services/java/request-orchestrator/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-ai/prompt-cache:latest -f services/java/prompt-cache/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-ai/inference-pool:latest -f services/java/inference-pool/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-ai/model-router:latest -f services/python/model-router/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-ai/safety-gateway:latest -f services/python/safety-gateway/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-ai/mock-server:latest -f services/python/mock-server/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-ai/web-app:latest -f services/typescript/web-app/Dockerfile .

## Build and push a single service (usage: make build-svc SVC=model-router LANG=python)
build-svc:
	@docker build -t $(REGISTRY)/daystrom-ai/$(SVC):latest services/$(LANG)/$(SVC)
	@docker push $(REGISTRY)/daystrom-ai/$(SVC):latest
	@kubectl -n daystrom-ai rollout restart deployment/$(SVC)

# ─── Development helpers ────────────────────────────────────────────

## Restart a deployment (usage: make restart SVC=model-router)
restart:
	@kubectl -n daystrom-ai rollout restart deployment/$(SVC)

## Port-forward web-app locally
port-forward:
	@kubectl -n daystrom-ai port-forward svc/web-app 3000:3000

## Run a quick smoke test
test:
	@echo "Sending test request..."
	@curl -s -X POST http://localhost:3000/api/chat \
		-H "Content-Type: application/json" \
		-d '{"message": "Hello, what is observability?", "model": "large"}' | head -20

## Clean up docker images
clean:
	@docker image prune -f --filter "label=workshop"

# ─── K8s apply ──────────────────────────────────────────────────────

## Apply k8s manifests (after rebuild)
apply:
	@kubectl apply -k k8s/base/ --dry-run=client -o yaml | \
		sed "s|daystrom-ai/|$(REGISTRY)/daystrom-ai/|g" | \
		kubectl apply -f -
