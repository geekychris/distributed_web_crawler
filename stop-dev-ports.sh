#!/bin/bash

echo "=== Stopping port forwarding ==="

if [ -f /tmp/crawler_port_forwards.pids ]; then
    echo "Stopping port forward processes..."
    while read pid; do
        if kill $pid 2>/dev/null; then
            echo "Stopped process $pid"
        else
            echo "Process $pid already stopped or not found"
        fi
    done < /tmp/crawler_port_forwards.pids
    rm /tmp/crawler_port_forwards.pids
    echo "All port forwards stopped."
else
    echo "No active port forwards found."
fi

# Also kill any kubectl port-forward processes just in case
pkill -f "kubectl port-forward" 2>/dev/null && echo "Cleaned up any remaining kubectl port-forward processes"

echo "Port forwarding stopped."