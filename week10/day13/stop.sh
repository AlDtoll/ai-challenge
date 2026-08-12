#!/bin/bash
PID=$(cat /tmp/llm-gateway.pid 2>/dev/null)
if [ -n "$PID" ] && kill -0 "$PID" 2>/dev/null; then
    kill "$PID"
    echo "Stopped PID $PID"
else
    echo "No live gateway (PID file: ${PID:-empty})"
fi
rm -f /tmp/llm-gateway.pid
