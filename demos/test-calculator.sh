#!/bin/bash

# Calculator Demo 端到端测试脚本
# 测试 calculator-client 通过 grpc-mesh-server 调用 calculator-service

set -e

COLOR_GREEN='\033[0;32m'
COLOR_RED='\033[0;31m'
COLOR_YELLOW='\033[1;33m'
COLOR_BLUE='\033[0;34m'
COLOR_RESET='\033[0m'

echo -e "${COLOR_BLUE}╔════════════════════════════════════════════════════╗${COLOR_RESET}"
echo -e "${COLOR_BLUE}║  Calculator Demo - End-to-End Test                ║${COLOR_RESET}"
echo -e "${COLOR_BLUE}╚════════════════════════════════════════════════════╝${COLOR_RESET}"
echo ""

# 检查 calculator-client 是否运行
echo -e "${COLOR_YELLOW}[1/5] Checking calculator-client status...${COLOR_RESET}"
if ! curl -s http://localhost:8080/ > /dev/null 2>&1; then
    echo -e "${COLOR_RED}❌ calculator-client is not running on :8080${COLOR_RESET}"
    echo -e "${COLOR_YELLOW}Please start it first:${COLOR_RESET}"
    echo -e "  cd demos/calculator-client && ./calculator-client"
    exit 1
fi
echo -e "${COLOR_GREEN}✅ calculator-client is running${COLOR_RESET}"
echo ""

# 测试健康检查
echo -e "${COLOR_YELLOW}[2/5] Testing health check...${COLOR_RESET}"
HEALTH_RESPONSE=$(curl -s http://localhost:8080/health)
if echo "$HEALTH_RESPONSE" | grep -q '"status":"healthy"'; then
    echo -e "${COLOR_GREEN}✅ Health check passed${COLOR_RESET}"
    echo "   Response: $HEALTH_RESPONSE"
else
    echo -e "${COLOR_RED}❌ Health check failed${COLOR_RESET}"
    echo "   Response: $HEALTH_RESPONSE"
    echo -e "${COLOR_YELLOW}Possible issues:${COLOR_RESET}"
    echo "   - grpc-mesh-server not running on :50051"
    echo "   - calculator-service not connected"
    exit 1
fi
echo ""

# 测试加法
echo -e "${COLOR_YELLOW}[3/5] Testing addition (10 + 5)...${COLOR_RESET}"
ADD_RESULT=$(curl -s -X POST http://localhost:8080/calculate \
    -H 'Content-Type: application/json' \
    -d '{"operation": "add", "a": 10, "b": 5}')

if echo "$ADD_RESULT" | grep -q '"result":15'; then
    echo -e "${COLOR_GREEN}✅ Addition: 10 + 5 = 15${COLOR_RESET}"
else
    echo -e "${COLOR_RED}❌ Addition failed${COLOR_RESET}"
    echo "   Response: $ADD_RESULT"
    exit 1
fi

# 测试减法
echo -e "${COLOR_YELLOW}[4/5] Testing subtraction (10 - 5)...${COLOR_RESET}"
SUB_RESULT=$(curl -s -X POST http://localhost:8080/calculate \
    -H 'Content-Type: application/json' \
    -d '{"operation": "subtract", "a": 10, "b": 5}')

if echo "$SUB_RESULT" | grep -q '"result":5'; then
    echo -e "${COLOR_GREEN}✅ Subtraction: 10 - 5 = 5${COLOR_RESET}"
else
    echo -e "${COLOR_RED}❌ Subtraction failed${COLOR_RESET}"
    echo "   Response: $SUB_RESULT"
    exit 1
fi

# 测试乘法
echo -e "${COLOR_YELLOW}[5/5] Testing multiplication (10 * 5)...${COLOR_RESET}"
MUL_RESULT=$(curl -s -X POST http://localhost:8080/calculate \
    -H 'Content-Type: application/json' \
    -d '{"operation": "multiply", "a": 10, "b": 5}')

if echo "$MUL_RESULT" | grep -q '"result":50'; then
    echo -e "${COLOR_GREEN}✅ Multiplication: 10 * 5 = 50${COLOR_RESET}"
else
    echo -e "${COLOR_RED}❌ Multiplication failed${COLOR_RESET}"
    echo "   Response: $MUL_RESULT"
    exit 1
fi

# 测试除法
echo -e "${COLOR_YELLOW}[Bonus] Testing division (10 / 5)...${COLOR_RESET}"
DIV_RESULT=$(curl -s -X POST http://localhost:8080/calculate \
    -H 'Content-Type: application/json' \
    -d '{"operation": "divide", "a": 10, "b": 5}')

if echo "$DIV_RESULT" | grep -q '"result":2'; then
    echo -e "${COLOR_GREEN}✅ Division: 10 / 5 = 2${COLOR_RESET}"
else
    echo -e "${COLOR_RED}❌ Division failed${COLOR_RESET}"
    echo "   Response: $DIV_RESULT"
    exit 1
fi

# 测试除零错误
echo -e "${COLOR_YELLOW}[Error Test] Testing divide by zero...${COLOR_RESET}"
ZERO_RESULT=$(curl -s -X POST http://localhost:8080/calculate \
    -H 'Content-Type: application/json' \
    -d '{"operation": "divide", "a": 10, "b": 0}')

if echo "$ZERO_RESULT" | grep -q '"error"'; then
    echo -e "${COLOR_GREEN}✅ Divide by zero error handled correctly${COLOR_RESET}"
    echo "   Response: $ZERO_RESULT"
else
    echo -e "${COLOR_RED}❌ Divide by zero should return error${COLOR_RESET}"
    echo "   Response: $ZERO_RESULT"
fi

echo ""
echo -e "${COLOR_GREEN}╔════════════════════════════════════════════════════╗${COLOR_RESET}"
echo -e "${COLOR_GREEN}║  🎉 All Tests Passed!                              ║${COLOR_RESET}"
echo -e "${COLOR_GREEN}╚════════════════════════════════════════════════════╝${COLOR_RESET}"
echo ""
echo -e "${COLOR_BLUE}Architecture verified:${COLOR_RESET}"
echo -e "  ${COLOR_GREEN}✓${COLOR_RESET} calculator-client (HTTP :8080) → embedded mesh server (gRPC :50051)"
echo -e "  ${COLOR_GREEN}✓${COLOR_RESET} embedded mesh server → calculator-service (Yamux/TLS :8443)"
echo -e "  ${COLOR_GREEN}✓${COLOR_RESET} calculator-service → business logic execution"
echo ""
echo -e "${COLOR_YELLOW}Next steps:${COLOR_RESET}"
echo "  - View logs: journalctl -f"
echo "  - Monitor metrics: curl localhost:9090/metrics"
echo "  - Scale: start more calculator-service instances with different node IDs"
echo ""
