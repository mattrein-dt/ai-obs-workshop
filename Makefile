.PHONY: up down build status logs clean

CLUSTER_NAME := daystrom-mini
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
	@kubectl -n daystrom-mini get pods -o wide

## Tail logs for a service (usage: make logs SVC=model-router)
logs:
	@kubectl -n daystrom-mini logs -f deployment/$(SVC) --tail=50

# ─── Build targets ──────────────────────────────────────────────────

## Build all service images
build:
	@echo "Building all images..."
	@docker build -t $(REGISTRY)/daystrom-mini/request-orchestrator:latest -f services/java/request-orchestrator/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-mini/prompt-cache:latest -f services/java/prompt-cache/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-mini/inference-pool:latest -f services/java/inference-pool/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-mini/model-router:latest -f services/python/model-router/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-mini/safety-gateway:latest -f services/python/safety-gateway/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-mini/mock-server:latest -f services/python/mock-server/Dockerfile .
	@docker build -t $(REGISTRY)/daystrom-mini/web-app:latest -f services/typescript/web-app/Dockerfile .

## Build and push a single service (usage: make build-svc SVC=inference-pool DF=services/java/inference-pool/Dockerfile)
build-svc:
	@docker build -t $(REGISTRY)/daystrom-mini/$(SVC):latest -f $(DF) .
	@docker push $(REGISTRY)/daystrom-mini/$(SVC):latest
	@kubectl -n daystrom-mini rollout restart deployment/$(SVC)

# ─── Development helpers ────────────────────────────────────────────

## Restart a deployment (usage: make restart SVC=model-router)
restart:
	@kubectl -n daystrom-mini rollout restart deployment/$(SVC)

## Port-forward web-app locally
port-forward:
	@kubectl -n daystrom-mini port-forward svc/web-app 3000:3000

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
		sed "s|daystrom-mini/|$(REGISTRY)/daystrom-mini/|g" | \
		kubectl apply -f -
