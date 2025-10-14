#!/bin/bash

# Deployment script for distributed crawler infrastructure
# This script ensures proper deployment order and handles cleanup when needed

set -e

NAMESPACE="crawler"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

# Colors for output
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

log_info() {
    echo -e "${GREEN}[INFO]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

wait_for_pods() {
    local app_name=$1
    local expected_ready=$2
    log_info "Waiting for $app_name pods to be ready..."
    
    local timeout=300
    local elapsed=0
    
    while [ $elapsed -lt $timeout ]; do
        local ready_pods=$(kubectl get pods -n $NAMESPACE -l app=$app_name --no-headers 2>/dev/null | awk '$2 ~ /^[0-9]+\/[0-9]+$/ && $3 == "Running" {split($2,a,"/"); if(a[1]==a[2]) count++} END {print count+0}')
        
        if [ "$ready_pods" -ge "$expected_ready" ]; then
            log_info "$app_name is ready ($ready_pods/$expected_ready pods)"
            return 0
        fi
        
        log_info "Waiting for $app_name... ($ready_pods/$expected_ready ready)"
        sleep 5
        elapsed=$((elapsed + 5))
    done
    
    log_error "Timeout waiting for $app_name to be ready"
    return 1
}

clean_kafka_if_needed() {
    log_info "Checking Kafka status..."
    
    # Check if Kafka pods exist and are in error state
    local kafka_pods=$(kubectl get pods -n $NAMESPACE -l app=kafka --no-headers 2>/dev/null | wc -l || echo "0")
    
    if [ "$kafka_pods" -gt 0 ]; then
        local failed_pods=$(kubectl get pods -n $NAMESPACE -l app=kafka --no-headers 2>/dev/null | grep -E "(CrashLoopBackOff|Error|ImagePullBackOff)" | wc -l || echo "0")
        
        if [ "$failed_pods" -gt 0 ]; then
            log_warn "Found Kafka pods in failed state. Cleaning up for fresh deployment..."
            
            # Delete StatefulSet and PVCs
            kubectl delete statefulset kafka -n $NAMESPACE --ignore-not-found=true
            kubectl delete pvc -l app=kafka -n $NAMESPACE --ignore-not-found=true
            
            # Wait for cleanup
            log_info "Waiting for cleanup to complete..."
            sleep 10
        fi
    fi
}

deploy_infrastructure() {
    log_info "Deploying distributed crawler infrastructure..."
    
    # Create namespace and storage class
    log_info "Creating namespace and storage class..."
    kubectl apply -f k8s/namespace.yaml
    kubectl apply -f k8s/storage-class.yaml
    
    # Deploy ZooKeeper first (Kafka dependency)
    log_info "Deploying ZooKeeper..."
    kubectl apply -f k8s/kafka/zookeeper.yaml
    wait_for_pods "zookeeper" 1
    
    # Clean Kafka if needed before deploying
    clean_kafka_if_needed
    
    # Deploy Kafka
    log_info "Deploying Kafka..."
    kubectl apply -f k8s/kafka/kafka.yaml
    wait_for_pods "kafka" 1
    
    # Deploy Cassandra
    log_info "Deploying Cassandra..."
    kubectl apply -f k8s/cassandra/
    wait_for_pods "cassandra" 1
    
    # Deploy MinIO
    log_info "Deploying MinIO..."
    kubectl apply -f k8s/minio/
    wait_for_pods "minio" 1
    
    log_info "✅ Infrastructure deployment completed successfully!"
    
    # Show service status
    echo
    log_info "Service Status:"
    kubectl get pods -n $NAMESPACE
    
    echo
    log_info "To start port forwarding for local development, run:"
    echo "  ./start-dev-ports.sh"
}

clean_all() {
    log_warn "Cleaning all infrastructure..."
    
    # Delete in reverse order
    kubectl delete -f k8s/crawler/ --ignore-not-found=true
    kubectl delete -f k8s/minio/ --ignore-not-found=true
    kubectl delete -f k8s/cassandra/ --ignore-not-found=true
    kubectl delete -f k8s/kafka/ --ignore-not-found=true
    
    # Delete PVCs to ensure clean state
    kubectl delete pvc --all -n $NAMESPACE --ignore-not-found=true
    
    log_info "Infrastructure cleaned successfully!"
}

show_help() {
    echo "Usage: $0 [COMMAND]"
    echo
    echo "Commands:"
    echo "  deploy    Deploy all infrastructure services (default)"
    echo "  clean     Remove all infrastructure services and data"
    echo "  help      Show this help message"
    echo
    echo "Examples:"
    echo "  $0                # Deploy infrastructure"
    echo "  $0 deploy         # Deploy infrastructure"
    echo "  $0 clean          # Clean all infrastructure"
}

# Main execution
case "${1:-deploy}" in
    "deploy")
        deploy_infrastructure
        ;;
    "clean")
        clean_all
        ;;
    "help"|"-h"|"--help")
        show_help
        ;;
    *)
        log_error "Unknown command: $1"
        show_help
        exit 1
        ;;
esac