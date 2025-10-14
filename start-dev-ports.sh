#!/bin/bash

echo "=== Starting port forwarding for local development ==="

# Function to start port forwarding in background
start_port_forward() {
    local service=$1
    local port=$2
    local pod=$3
    local namespace=$4
    
    echo "Setting up port forwarding: localhost:$port -> $pod:$port"
    kubectl port-forward pod/$pod $port:$port -n $namespace > /dev/null 2>&1 &
    local pid=$!
    echo "Port forward PID: $pid for $service"
    echo $pid >> /tmp/crawler_port_forwards.pids
}

# Clean up any existing port forwards
if [ -f /tmp/crawler_port_forwards.pids ]; then
    echo "Cleaning up existing port forwards..."
    while read pid; do
        kill $pid 2>/dev/null || true
    done < /tmp/crawler_port_forwards.pids
    rm /tmp/crawler_port_forwards.pids
fi

# Create new PID file
touch /tmp/crawler_port_forwards.pids

# Wait for pods to be ready
echo "Waiting for pods to be ready..."
kubectl wait --for=condition=ready pod/cassandra-0 -n crawler --timeout=60s
kubectl wait --for=condition=ready pod/kafka-0 -n crawler --timeout=60s  
kubectl wait --for=condition=ready pod/minio-0 -n crawler --timeout=60s
kubectl wait --for=condition=ready pod/zookeeper-0 -n crawler --timeout=60s

# Start port forwarding for services (Cassandra uses NodePort)
start_port_forward "Kafka" 9092 "kafka-0" "crawler"
start_port_forward "MinIO API" 9000 "minio-0" "crawler"
start_port_forward "MinIO Console" 9001 "minio-0" "crawler"
start_port_forward "Zookeeper" 2181 "zookeeper-0" "crawler"

# Give port forwards time to establish
sleep 3

echo ""
echo "=== Port forwarding setup complete! ==="
echo "Your services are now accessible at:"
echo "  - Kafka:           localhost:9092    (port forward)"
echo "  - Cassandra:       localhost:30042   (NodePort)"
echo "  - MinIO API:       localhost:9000    (port forward)"
echo "  - MinIO Console:   http://localhost:9001 (port forward)"
echo "  - Zookeeper:       localhost:2181    (port forward)"
echo ""
echo "Application configuration uses:"
echo "  - kafka.bootstrap-servers: localhost:9092   (port forward)"
echo "  - cassandra.contact-points: localhost:30042 (NodePort)"  
echo "  - s3.endpoint: http://localhost:9000        (port forward)"
echo ""
echo "To stop all port forwards, run: ./stop-dev-ports.sh"
echo "To check status, run: ./check-dev-ports.sh"
echo ""
echo "Ready to run your crawler: mvn spring-boot:run"