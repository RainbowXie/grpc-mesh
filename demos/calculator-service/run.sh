#!/bin/bash

# Calculator Service 启动脚本
# 自动检测配置文件或使用环境变量

set -e

COLOR_GREEN='\033[0;32m'
COLOR_RED='\033[0;31m'
COLOR_YELLOW='\033[1;33m'
COLOR_BLUE='\033[0;34m'
COLOR_RESET='\033[0m'

echo -e "${COLOR_BLUE}╔════════════════════════════════════════════════════╗${COLOR_RESET}"
echo -e "${COLOR_BLUE}║  Calculator Service Launcher                      ║${COLOR_RESET}"
echo -e "${COLOR_BLUE}╚════════════════════════════════════════════════════╝${COLOR_RESET}"
echo ""

# 检查配置文件
CONFIG_FILE="${CONFIG_PATH:-config/config.json}"

if [ -f "$CONFIG_FILE" ]; then
    echo -e "${COLOR_GREEN}✅ Configuration file found: ${CONFIG_FILE}${COLOR_RESET}"

    # 显示配置内容
    if command -v jq &> /dev/null; then
        echo -e "${COLOR_BLUE}Configuration:${COLOR_RESET}"
        echo -e "  Server: $(jq -r '.server.address' "$CONFIG_FILE")"
        echo -e "  Node ID: $(jq -r '.node.id' "$CONFIG_FILE")"
        TOKEN=$(jq -r '.node.token' "$CONFIG_FILE")
        echo -e "  Token: ${TOKEN:0:15}..."
    fi
else
    echo -e "${COLOR_YELLOW}⚠️  Configuration file not found: ${CONFIG_FILE}${COLOR_RESET}"
    echo -e "${COLOR_YELLOW}Checking environment variables...${COLOR_RESET}"
    echo ""

    # 检查必需的环境变量
    if [ -z "$TUNNEL_TOKEN" ] && [ -z "$WA_NODE_TOKEN" ]; then
        echo -e "${COLOR_RED}❌ Error: Token not configured${COLOR_RESET}"
        echo ""
        echo "Please either:"
        echo ""
        echo "1. Create a configuration file:"
        echo -e "${COLOR_YELLOW}   cat > config/config.json <<EOF"
        echo "   {"
        echo "     \"server\": {"
        echo "       \"address\": \"localhost:8443\","
        echo "       \"tls\": {"
        echo "         \"server_name\": \"localhost\""
        echo "       }"
        echo "     },"
        echo "     \"node\": {"
        echo "       \"id\": \"calculator-service\","
        echo "       \"token\": \"waemu_YOUR_TOKEN_HERE\""
        echo "     }"
        echo "   }"
        echo -e "   EOF${COLOR_RESET}"
        echo ""
        echo "2. Set environment variables:"
        echo -e "${COLOR_YELLOW}   export TUNNEL_TOKEN=\"waemu_YOUR_TOKEN_HERE\""
        echo -e "   export TUNNEL_SERVER=\"localhost:8443\"${COLOR_RESET}"
        echo ""
        echo "To generate a token:"
        echo -e "${COLOR_YELLOW}   cd ../../grpc-mesh-server && make token${COLOR_RESET}"
        echo ""
        exit 1
    fi

    echo -e "${COLOR_GREEN}✅ Environment variables configured${COLOR_RESET}"
    echo -e "${COLOR_BLUE}Configuration:${COLOR_RESET}"
    echo -e "  Server: ${TUNNEL_SERVER:-${SERVER_ADDRESS:-localhost:8443}}"
    echo -e "  Node ID: ${NODE_ID:-${WA_NODE_ID:-calculator-service}}"
    TOKEN="${TUNNEL_TOKEN:-$WA_NODE_TOKEN}"
    echo -e "  Token: ${TOKEN:0:15}..."
fi

echo ""

# 检查 CA 证书
CA_CERT="${CA_CERT_PATH:-../../grpc-mesh-server/config/tls/ca.crt}"
if [ -f "$CA_CERT" ]; then
    echo -e "${COLOR_GREEN}✅ CA certificate found: ${CA_CERT}${COLOR_RESET}"
else
    echo -e "${COLOR_YELLOW}⚠️  CA certificate not found: ${CA_CERT}${COLOR_RESET}"
    echo -e "${COLOR_YELLOW}   TLS verification may fail with self-signed certificates${COLOR_RESET}"
    echo ""
    echo "To generate certificates:"
    echo -e "${COLOR_YELLOW}   cd ../../grpc-mesh-server && make certs${COLOR_RESET}"
    echo ""
fi

echo ""
echo -e "${COLOR_BLUE}🚀 Starting Calculator Service...${COLOR_RESET}"
echo ""

# 检查是否需要构建
if [ ! -f "target/release/calculator-service" ]; then
    echo -e "${COLOR_YELLOW}⚙️  Binary not found, building...${COLOR_RESET}"
    cargo build --release
    echo ""
fi

# 启动服务
exec cargo run --release
