#!/bin/bash

echo "=== Checking port forwarding status ==="

if [ -f /tmp/crawler_port_forwards.pids ]; then
    echo "Port forward processes:"
    while read pid; do
        if ps -p $pid > /dev/null 2>&1; then
            echo "✅ Process $pid is running"
        else
            echo "❌ Process $pid is not running"
        fi
    done < /tmp/crawler_port_forwards.pids
else
    echo "No port forward PID file found."
fi

echo ""
echo "Testing port connectivity:"

test_port() {
    local service=$1
    local port=$2
    if nc -z localhost $port 2>/dev/null; then
        echo "✅ $service (localhost:$port): Accessible"
    else
        echo "❌ $service (localhost:$port): Not accessible"
    fi
}

test_port "Kafka" 9092
test_port "Cassandra (NodePort)" 30042
test_port "MinIO API" 9000
test_port "MinIO Console" 9001
test_port "Zookeeper" 2181

echo ""
echo "Kubernetes pod status:"
kubectl get pods -n crawler