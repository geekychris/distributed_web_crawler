# NodePort Configuration Summary

This document summarizes the Kubernetes NodePort configuration for external access to the distributed crawler services.

## Service Port Mappings

### Kafka
- **Internal Service**: `kafka:9092`
- **NodePort Service**: `kafka-nodeport:30092`
- **Host Access**: `localhost:30092`
- **Configuration**: `k8s/kafka/kafka.yaml`

### Zookeeper
- **Internal Service**: `zookeeper:2181`
- **NodePort Service**: `zookeeper-nodeport:32181`  
- **Host Access**: `localhost:32181`
- **Configuration**: `k8s/kafka/zookeeper.yaml`

### Cassandra
- **Internal Service**: `cassandra:9042`
- **NodePort Service**: `cassandra-nodeport:30042`
- **Host Access**: `localhost:30042`
- **Configuration**: `k8s/cassandra/cassandra.yaml`

### MinIO
- **API Internal Service**: `minio:9000`
- **Console Internal Service**: `minio:9001`
- **API NodePort Service**: `minio-nodeport:30000`
- **Console NodePort Service**: `minio-nodeport:30001`
- **Host API Access**: `localhost:30000`
- **Host Console Access**: `localhost:30001`
- **Configuration**: `k8s/minio/minio.yaml`

## Application Configuration

The `src/main/resources/application.yml` has been updated to use the NodePort addresses:

```yaml
kafka:
  bootstrap-servers: localhost:30092

cassandra:
  contact-points: localhost:30042

s3:
  endpoint: http://localhost:30000
```

## Usage

1. **Deploy Kubernetes services**:
   ```bash
   kubectl apply -f k8s/namespace.yaml
   kubectl apply -f k8s/storage-class.yaml
   kubectl apply -f k8s/kafka/
   kubectl apply -f k8s/cassandra/
   kubectl apply -f k8s/minio/
   ```

2. **Run crawler locally**: The crawler application can now be run locally (via IDE debugger or `mvn spring-boot:run`) and will connect to the Kubernetes services via NodePort.

3. **Access services from host**:
   - Kafka: `localhost:30092`
   - Zookeeper: `localhost:32181`
   - Cassandra CQL: `localhost:30042`
   - MinIO API: `localhost:30000`
   - MinIO Console: `http://localhost:30001`

## Service Status Check

You can verify the NodePort services are running:

```bash
# Check all services
kubectl get services -n crawler

# Check specific NodePort services
kubectl get services -n crawler --field-selector spec.type=NodePort

# Test connectivity
telnet localhost 30092  # Kafka
telnet localhost 30042  # Cassandra
curl http://localhost:30000  # MinIO API
```

## ✅ Validation Results

**All NodePort services are accessible from the host machine:**

- **✅ MinIO API (port 30000)**: Responding with HTTP/1.1 400 Bad Request (normal for root endpoint)
- **✅ MinIO Console (port 30001)**: Responding with HTTP/1.1 200 OK  
- **✅ Kafka (port 30092)**: Connection successful
- **✅ Cassandra (port 30042)**: Connection successful
- **✅ Zookeeper (port 32181)**: Connection successful

Your crawler application running locally via debugger will now be able to connect to:
- Kafka at `localhost:30092`
- Cassandra at `localhost:30042`  
- MinIO at `localhost:30000`

## Configuration Fixed

The following issues were resolved:
1. **Storage Class**: Updated from AWS EBS to local-path provisioner for local development
2. **StatefulSets**: Updated to use `local-standard` storage class
3. **NodePort Services**: All properly configured and accessible
