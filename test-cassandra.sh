#!/bin/bash

echo "=== Testing Cassandra Connectivity ==="
echo "1. Checking port accessibility:"
nc -z localhost 30042 && echo "✅ Port 30042 is accessible" || echo "❌ Port 30042 is not accessible"

echo ""
echo "2. Testing from within Kubernetes:"
kubectl exec -it cassandra-0 -n crawler -- cqlsh -e "SELECT keyspace_name FROM system_schema.keyspaces WHERE keyspace_name = 'crawler';" 2>/dev/null && echo "✅ Cassandra is responding to CQL queries" || echo "❌ Cassandra CQL queries failed"

echo ""
echo "3. Checking if Cassandra schema exists:"
kubectl exec -it cassandra-0 -n crawler -- cqlsh -e "USE crawler; SELECT COUNT(*) FROM system_schema.tables WHERE keyspace_name = 'crawler';" 2>/dev/null && echo "✅ Schema tables found" || echo "❌ Schema tables not found"

echo ""
echo "4. Current application.yml configuration:"
echo "Kafka: $(grep -A1 'bootstrap-servers:' src/main/resources/application.yml | grep -o 'localhost:[0-9]*')"
echo "Cassandra: $(grep -A1 'contact-points:' src/main/resources/application.yml | grep -o 'localhost:[0-9]*')" 
echo "MinIO: $(grep -A1 'endpoint:' src/main/resources/application.yml | grep -o 'http://localhost:[0-9]*')"

echo ""
echo "5. Service status:"
kubectl get services -n crawler | grep NodePort

echo ""
echo "=== Ready to test application ==="
echo "Try running: mvn spring-boot:run"