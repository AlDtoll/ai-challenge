#!/bin/bash
# E2E test against LIVE gateway on :8100. Requires gateway running (run.sh first).
set -u
BASE="http://127.0.0.1:8100"
OUT="/tmp/e2e_results.txt"
: > "$OUT"

echo "=== 1. /health ===" | tee -a "$OUT"
curl -s "$BASE/health" | tee -a "$OUT"; echo | tee -a "$OUT"

echo "=== 2. Block: AWS key in prompt (expect 403) ===" | tee -a "$OUT"
STATUS=$(curl -s -o /tmp/e2e_r.json -w "%{http_code}" "$BASE/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"key: AKIAIOSFODNN7EXAMPLE test"}]}')
echo "status=$STATUS" | tee -a "$OUT"; cat /tmp/e2e_r.json | tee -a "$OUT"; echo | tee -a "$OUT"

echo "=== 3. Block: OpenAI key (expect 403) ===" | tee -a "$OUT"
STATUS=$(curl -s -o /tmp/e2e_r.json -w "%{http_code}" "$BASE/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"sk-proj-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}]}')
echo "status=$STATUS" | tee -a "$OUT"; cat /tmp/e2e_r.json | tee -a "$OUT"; echo | tee -a "$OUT"

echo "=== 4. Clean prompt (expect 200, live DeepSeek response) ===" | tee -a "$OUT"
STATUS=$(curl -s -o /tmp/e2e_r.json -w "%{http_code}" "$BASE/v1/chat/completions" \
    -H "Content-Type: application/json" \
    -d '{"model":"deepseek-chat","messages":[{"role":"user","content":"Say the word BEACON and nothing else."}],"max_tokens":10}')
echo "status=$STATUS" | tee -a "$OUT"; cat /tmp/e2e_r.json | tee -a "$OUT"; echo | tee -a "$OUT"

echo "=== 5. Rate-limit smoke (5 rapid clean pings) ===" | tee -a "$OUT"
for i in 1 2 3 4 5; do
    STATUS=$(curl -s -o /dev/null -w "%{http_code}" "$BASE/health")
    echo "ping $i: $STATUS" | tee -a "$OUT"
done

echo "=== 6. /audit/log?n=10 ===" | tee -a "$OUT"
curl -s "$BASE/audit/log?n=10" | tee -a "$OUT"; echo | tee -a "$OUT"

echo "=== 7. /stats/cost ===" | tee -a "$OUT"
curl -s "$BASE/stats/cost" | tee -a "$OUT"; echo | tee -a "$OUT"

echo "e2e complete. Results in $OUT"
