FROM python:3.11-slim

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    protobuf-compiler gcc python3-dev && rm -rf /var/lib/apt/lists/*

COPY services/python/safety-gateway/requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt

# Copy proto files and generate stubs
COPY proto/ /proto/
RUN python -m grpc_tools.protoc \
    -I/proto \
    --python_out=/app \
    --grpc_python_out=/app \
    /proto/safety.proto

COPY services/python/safety-gateway/app/ ./app/

EXPOSE 50053 9090
CMD ["python", "-m", "app.main"]
