# Deployment Fixes for Kafka Cluster ID Issues

This document explains the fixes made to prevent the Kafka `InconsistentClusterIdException` that was causing connection issues.

## Problem Summary

The crawler service couldn't connect to Kafka because Kafka was in `CrashLoopBackOff` due to:
- Cluster ID mismatch between stored metadata and current ZooKeeper cluster
- Duplicate environment variable configuration
- Missing health checks and proper initialization ordering

## Fixes Applied

### 1. Kafka Configuration Improvements (`k8s/kafka/kafka.yaml`)

**Fixed Issues:**
- ✅ Removed duplicate `KAFKA_INTER_BROKER_LISTENER_NAME` environment variable
- ✅ Updated `KAFKA_ADVERTISED_LISTENERS` to use proper Kubernetes DNS name
- ✅ Added additional Kafka configuration for better stability
- ✅ Added health checks (liveness and readiness probes)
- ✅ Added proper PVC labels for cleanup management

**New Configuration:**
```yaml
env:
- name: KAFKA_ADVERTISED_LISTENERS
  value: "PLAINTEXT://kafka-0.kafka.crawler.svc.cluster.local:9092"
- name: KAFKA_AUTO_CREATE_TOPICS_ENABLE
  value: "true"
- name: KAFKA_LOG_RETENTION_HOURS
  value: "168"
- name: KAFKA_LOG_RETENTION_BYTES
  value: "1073741824"
- name: KAFKA_NUM_PARTITIONS
  value: "3"
- name: KAFKA_DEFAULT_REPLICATION_FACTOR
  value: "1"
```

### 2. ZooKeeper Configuration Improvements (`k8s/kafka/zookeeper.yaml`)

**Added:**
- ✅ `ZOOKEEPER_SERVER_ID` for proper cluster identification
- ✅ Additional ZooKeeper tuning parameters
- ✅ Health checks (liveness and readiness probes)
- ✅ Proper cleanup and retention settings

### 3. New Deployment Script (`deploy-infrastructure.sh`)

**Features:**
- ✅ **Proper deployment order**: ZooKeeper → Kafka → Cassandra → MinIO
- ✅ **Automatic cleanup detection**: Detects failed Kafka pods and cleans up automatically
- ✅ **Wait for readiness**: Ensures each service is ready before deploying the next
- ✅ **Clean deployment option**: `./deploy-infrastructure.sh clean` removes everything
- ✅ **Health monitoring**: Waits for pods to be fully ready before proceeding

## Usage

### Deploy Infrastructure (Recommended)
```bash
# Deploy all infrastructure services with proper ordering
./deploy-infrastructure.sh

# Or explicitly:
./deploy-infrastructure.sh deploy
```

### Clean Infrastructure (if needed)
```bash
# Remove all services and persistent data
./deploy-infrastructure.sh clean
```

### Old Method (Still Works)
```bash
# Manual deployment (less reliable)
kubectl apply -f k8s/namespace.yaml
kubectl apply -f k8s/storage-class.yaml
kubectl apply -f k8s/kafka/
kubectl apply -f k8s/cassandra/
kubectl apply -f k8s/minio/
```

## Key Improvements

1. **Prevents Cluster ID Conflicts**: The script automatically detects and cleans up failed Kafka deployments
2. **Ensures Proper Startup Order**: ZooKeeper starts before Kafka to prevent timing issues
3. **Better Health Monitoring**: Readiness probes ensure services are fully operational
4. **Easier Troubleshooting**: Clear logging and status reporting
5. **Consistent Configuration**: Proper DNS names and environment variables

## Development Workflow

1. **Deploy Infrastructure**: `./deploy-infrastructure.sh`
2. **Start Port Forwarding**: `./start-dev-ports.sh`
3. **Run Crawler**: `mvn spring-boot:run`
4. **Check Status**: `./check-dev-ports.sh`

## Troubleshooting

If you encounter issues:

1. **Check pod status**: `kubectl get pods -n crawler`
2. **View logs**: `kubectl logs <pod-name> -n crawler`
3. **Clean and redeploy**: 
   ```bash
   ./deploy-infrastructure.sh clean
   ./deploy-infrastructure.sh deploy
   ```

The new configuration should prevent the cluster ID mismatch issues that were causing Kafka connection problems.