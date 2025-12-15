.PHONY: all build run clean proto test help

# 变量定义
BINARY_NAME=calculator-client
PROTO_DIR=proto
PROTO_FILE=$(PROTO_DIR)/calculator.proto
GO_OUT_DIR=$(PROTO_DIR)

# 默认目标
all: proto build

# 生成 protobuf 代码
proto:
	@echo "Generating protobuf code..."
	@mkdir -p $(GO_OUT_DIR)
	protoc --go_out=$(GO_OUT_DIR) --go_opt=paths=source_relative \
		--go-grpc_out=$(GO_OUT_DIR) --go-grpc_opt=paths=source_relative \
		$(PROTO_FILE)
	@if [ -d "$(GO_OUT_DIR)/proto" ]; then \
		mv $(GO_OUT_DIR)/proto/*.pb.go $(GO_OUT_DIR)/ && rmdir $(GO_OUT_DIR)/proto; \
	fi
	@echo "✅ Protobuf code generated"

# 编译
build:
	@echo "Building $(BINARY_NAME)..."
	go build -o $(BINARY_NAME) .
	@echo "✅ Build complete: ./$(BINARY_NAME)"

# 开发构建（带竞态检测）
build-dev:
	@echo "Building $(BINARY_NAME) with race detector..."
	go build -race -o $(BINARY_NAME) .
	@echo "✅ Development build complete"

# 生产构建（优化）
build-release:
	@echo "Building $(BINARY_NAME) for release..."
	CGO_ENABLED=0 go build -ldflags="-s -w" -o $(BINARY_NAME) .
	@echo "✅ Release build complete"

# 运行
run: build
	@echo "Starting $(BINARY_NAME)..."
	./$(BINARY_NAME)

# 运行（指定配置）
run-with-config:
	@echo "Starting $(BINARY_NAME) with config..."
	CONFIG_PATH=config.json ./$(BINARY_NAME)

# 测试
test:
	@echo "Running tests..."
	go test -v ./...

# 测试（带覆盖率）
test-coverage:
	@echo "Running tests with coverage..."
	go test -v -coverprofile=coverage.out ./...
	go tool cover -html=coverage.out -o coverage.html
	@echo "✅ Coverage report: coverage.html"

# 格式化代码
fmt:
	@echo "Formatting code..."
	go fmt ./...
	@echo "✅ Code formatted"

# 代码检查
lint:
	@echo "Running linter..."
	golangci-lint run
	@echo "✅ Lint complete"

# 依赖管理
deps:
	@echo "Downloading dependencies..."
	go mod download
	@echo "✅ Dependencies downloaded"

# 更新依赖
deps-update:
	@echo "Updating dependencies..."
	go get -u ./...
	go mod tidy
	@echo "✅ Dependencies updated"

# 清理
clean:
	@echo "Cleaning..."
	rm -f $(BINARY_NAME)
	rm -f coverage.out coverage.html
	@echo "✅ Clean complete"

# 完全清理（包括生成的 proto 代码）
clean-all: clean
	@echo "Removing generated files..."
	rm -f $(PROTO_DIR)/*.pb.go
	@echo "✅ All clean"

# Docker 构建
docker-build:
	@echo "Building Docker image..."
	docker build -t calculator-client:latest .
	@echo "✅ Docker image built"

# Docker 运行
docker-run:
	@echo "Running Docker container..."
	docker run -d \
		-p 8080:8080 \
		-e TUNNEL_SERVER=host.docker.internal:50051 \
		--name calculator-client \
		calculator-client:latest
	@echo "✅ Docker container started"

# Docker 停止
docker-stop:
	@echo "Stopping Docker container..."
	docker stop calculator-client
	docker rm calculator-client
	@echo "✅ Docker container stopped"

# 安装工具
install-tools:
	@echo "Installing tools..."
	go install google.golang.org/protobuf/cmd/protoc-gen-go@latest
	go install google.golang.org/grpc/cmd/protoc-gen-go-grpc@latest
	go install github.com/golangci/golangci-lint/cmd/golangci-lint@latest
	@echo "✅ Tools installed"

# 帮助
help:
	@echo "Calculator Client Makefile"
	@echo ""
	@echo "Usage:"
	@echo "  make [target]"
	@echo ""
	@echo "Targets:"
	@echo "  all              - Generate proto and build (default)"
	@echo "  proto            - Generate protobuf code"
	@echo "  build            - Build the binary"
	@echo "  build-dev        - Build with race detector"
	@echo "  build-release    - Build optimized binary for production"
	@echo "  run              - Build and run"
	@echo "  run-with-config  - Run with config file"
	@echo "  test             - Run tests"
	@echo "  test-coverage    - Run tests with coverage"
	@echo "  fmt              - Format code"
	@echo "  lint             - Run linter"
	@echo "  deps             - Download dependencies"
	@echo "  deps-update      - Update dependencies"
	@echo "  clean            - Remove binary and test artifacts"
	@echo "  clean-all        - Remove all generated files"
	@echo "  docker-build     - Build Docker image"
	@echo "  docker-run       - Run Docker container"
	@echo "  docker-stop      - Stop Docker container"
	@echo "  install-tools    - Install development tools"
	@echo "  help             - Show this help message"
	@echo ""
	@echo "Environment Variables:"
	@echo "  CONFIG_PATH      - Path to config file (default: config.json)"
	@echo "  TUNNEL_SERVER    - Tunnel server address (default: localhost:50051)"
	@echo "  NODE_ID          - Target node ID (default: calculator-service)"
	@echo "  LISTEN_ADDRESS   - HTTP listen address (default: :8080)"
