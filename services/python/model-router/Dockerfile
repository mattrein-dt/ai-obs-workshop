FROM python:3.11-slim

WORKDIR /app

# Install protobuf compiler
RUN apt-get update && apt-get install -y --no-install-recommends \
    protobuf-compiler && rm -rf /var/lib/apt/lists/*

COPY services/python/model-router/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy proto files and generate stubs
COPY proto/ /proto/
RUN python -m grpc_tools.protoc \
    -I/proto \
    --python_out=/app \
    --grpc_python_out=/app \
    /proto/model_router.proto /proto/inference.proto

COPY services/python/model-router/app/ ./app/

EXPOSE 50052
CMD ["python", "-m", "app.main"]
