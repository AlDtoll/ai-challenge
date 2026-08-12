#!/bin/bash
set -e
source /home/claudeuser/.claude/env/secrets.env
export DEEPSEEK_API_KEY
cd /home/claudeuser/tools/llm-gateway
# Завершаем предыдущий инстанс если есть
if [ -f /tmp/llm-gateway.pid ]; then
    OLDPID=$(cat /tmp/llm-gateway.pid)
    kill "$OLDPID" 2>/dev/null || true
    sleep 1
fi
nohup uvicorn gateway:app --host 127.0.0.1 --port 8100 --workers 1 > /tmp/llm-gateway.log 2>&1 &
echo $! > /tmp/llm-gateway.pid
echo "Gateway started on http://127.0.0.1:8100 (PID $!)"
sleep 2
curl -s http://127.0.0.1:8100/health && echo ""
