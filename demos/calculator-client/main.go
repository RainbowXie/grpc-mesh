package main

import (
	"context"
	"fmt"
	"log"
	"net/http"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/gin-gonic/gin"
	"github.com/grpc-mesh/grpc-mesh-server/pkg/config"
	"github.com/grpc-mesh/grpc-mesh-server/pkg/registry"
	"github.com/grpc-mesh/grpc-mesh-server/pkg/rpc"
	"github.com/grpc-mesh/grpc-mesh-server/pkg/server"
	"github.com/spf13/viper"
	"google.golang.org/protobuf/proto"

	pb "calculator-client/proto"
)

// AppConfig 应用配置（扩展 mesh config）
type AppConfig struct {
	App    AppSettings `mapstructure:"app"`
	Config *config.Config
}

type AppSettings struct {
	ListenAddress string `mapstructure:"listen_address"`
	TargetNodeID  string `mapstructure:"target_node_id"`
}

// CalculateRequest HTTP 请求
type CalculateRequest struct {
	Operation string  `json:"operation" binding:"required"`
	A         float64 `json:"a" binding:"required"`
	B         float64 `json:"b" binding:"required"`
}

// CalculateResponse HTTP 响应
type CalculateResponse struct {
	Result float64 `json:"result"`
}

// ErrorResponse 错误响应
type ErrorResponse struct {
	Error string `json:"error"`
}

// CalculatorHandler 计算器处理器
type CalculatorHandler struct {
	meshServer *server.Server
	nodeID     string
}

// NewCalculatorHandler 创建处理器
func NewCalculatorHandler(meshServer *server.Server, nodeID string) *CalculatorHandler {
	return &CalculatorHandler{
		meshServer: meshServer,
		nodeID:     nodeID,
	}
}

// Calculate 处理计算请求
func (h *CalculatorHandler) Calculate(c *gin.Context) {
	var input CalculateRequest
	if err := c.ShouldBindJSON(&input); err != nil {
		c.JSON(http.StatusBadRequest, ErrorResponse{Error: err.Error()})
		return
	}

	// 映射操作到方法
	methodMap := map[string]string{
		"add":      "calculator.v1.Calculator/Add",
		"subtract": "calculator.v1.Calculator/Subtract",
		"multiply": "calculator.v1.Calculator/Multiply",
		"divide":   "calculator.v1.Calculator/Divide",
	}

	method, ok := methodMap[input.Operation]
	if !ok {
		c.JSON(http.StatusBadRequest, ErrorResponse{
			Error: fmt.Sprintf("unknown operation: %s", input.Operation),
		})
		return
	}

	// 构造 gRPC 请求
	calcReq := &pb.CalcRequest{
		A: input.A,
		B: input.B,
	}
	payload, err := proto.Marshal(calcReq)
	if err != nil {
		log.Printf("Failed to marshal request: %v", err)
		c.JSON(http.StatusInternalServerError, ErrorResponse{
			Error: "internal error",
		})
		return
	}

	// 通过内嵌的 mesh gateway 直接调用
	ctx, cancel := context.WithTimeout(c.Request.Context(), 5*time.Second)
	defer cancel()

	log.Printf("Calling %s with a=%f, b=%f via node %s", input.Operation, input.A, input.B, h.nodeID)

	invokeReq := &rpc.InvokeRequest{
		PeerId:    h.nodeID,
		Method:    method,
		Payload:   payload,
		TimeoutMs: 5000,
	}

	gateway := h.meshServer.ReverseGateway()
	resp, err := gateway.Invoke(ctx, registry.PeerID(h.nodeID), invokeReq)
	if err != nil {
		log.Printf("Failed to invoke: %v", err)
		c.JSON(http.StatusInternalServerError, ErrorResponse{
			Error: fmt.Sprintf("failed to call service: %v", err),
		})
		return
	}

	// 检查调用结果
	if !resp.Success {
		log.Printf("Service error: %s - %s", resp.Error.Code, resp.Error.Message)
		c.JSON(http.StatusBadRequest, ErrorResponse{
			Error: resp.Error.Message,
		})
		return
	}

	// 解析响应
	var calcResp pb.CalcResponse
	if err := proto.Unmarshal(resp.Result, &calcResp); err != nil {
		log.Printf("Failed to unmarshal response: %v", err)
		c.JSON(http.StatusInternalServerError, ErrorResponse{
			Error: "failed to parse response",
		})
		return
	}

	log.Printf("Result: %f", calcResp.Result)

	c.JSON(http.StatusOK, CalculateResponse{
		Result: calcResp.Result,
	})
}

// Health 健康检查
func (h *CalculatorHandler) Health(c *gin.Context) {
	// 检查节点是否在线
	reg := h.meshServer.Registry()
	_, nodeOnline := reg.Get(registry.PeerID(h.nodeID))

	if !nodeOnline {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"status": "unhealthy",
			"error":  fmt.Sprintf("node %s not connected", h.nodeID),
		})
		return
	}

	// 尝试调用节点的健康检查
	ctx, cancel := context.WithTimeout(c.Request.Context(), 2*time.Second)
	defer cancel()

	healthReq := &pb.HealthRequest{
		Message: "ping",
	}
	payload, _ := proto.Marshal(healthReq)

	invokeReq := &rpc.InvokeRequest{
		PeerId:    h.nodeID,
		Method:    "calculator.v1.Calculator/Health",
		Payload:   payload,
		TimeoutMs: 2000,
	}

	gateway := h.meshServer.ReverseGateway()
	resp, err := gateway.Invoke(ctx, registry.PeerID(h.nodeID), invokeReq)

	if err != nil || !resp.Success {
		c.JSON(http.StatusServiceUnavailable, gin.H{
			"status": "unhealthy",
			"error":  err.Error(),
		})
		return
	}

	var healthResp pb.HealthResponse
	proto.Unmarshal(resp.Result, &healthResp)

	c.JSON(http.StatusOK, gin.H{
		"status":  "healthy",
		"message": healthResp.Message,
	})
}

// ListNodes 列出所有已连接的节点
func (h *CalculatorHandler) ListNodes(c *gin.Context) {
	reg := h.meshServer.Registry()
	sessions := reg.List()

	nodes := make([]gin.H, 0, len(sessions))
	for _, sess := range sessions {
		nodes = append(nodes, gin.H{
			"node_id":        string(sess.ID),
			"version":        sess.Handshake.Version,
			"features":       sess.Features,
			"metadata":       sess.Metadata,
			"connected_at":   sess.ConnectedAt,
			"last_heartbeat": sess.LastHeartbeat,
		})
	}

	c.JSON(http.StatusOK, gin.H{
		"total": len(nodes),
		"nodes": nodes,
	})
}

// LoadConfig 加载 YAML 配置
func LoadConfig() (*AppConfig, error) {
	configPath := os.Getenv("CONFIG_PATH")
	if configPath == "" {
		configPath = "config.yaml"
	}

	// 使用 viper 加载配置（与 grpc-mesh-server 一致）
	v := viper.New()
	v.SetConfigFile(configPath)
	v.SetConfigType("yaml")

	if err := v.ReadInConfig(); err != nil {
		return nil, fmt.Errorf("failed to read config file %s: %w", configPath, err)
	}

	// 解析应用配置
	var appSettings AppSettings
	if err := v.UnmarshalKey("app", &appSettings); err != nil {
		return nil, fmt.Errorf("failed to parse app config: %w", err)
	}

	// 环境变量覆盖
	if addr := os.Getenv("LISTEN_ADDRESS"); addr != "" {
		appSettings.ListenAddress = addr
	}
	if nodeID := os.Getenv("TARGET_NODE_ID"); nodeID != "" {
		appSettings.TargetNodeID = nodeID
	}

	// 使用 config.Load 加载 mesh 配置
	meshConfig, err := config.Load(configPath)
	if err != nil {
		return nil, fmt.Errorf("failed to load mesh config: %w", err)
	}

	return &AppConfig{
		App:    appSettings,
		Config: meshConfig,
	}, nil
}

func main() {
	log.Println("╔════════════════════════════════════════════════════╗")
	log.Println("║  Calculator Client - Embedded gRPC Mesh           ║")
	log.Println("╚════════════════════════════════════════════════════╝")

	// 加载配置
	appCfg, err := LoadConfig()
	if err != nil {
		log.Fatalf("Failed to load config: %v", err)
	}

	log.Printf("Configuration:")
	log.Printf("  HTTP Listen Address: %s", appCfg.App.ListenAddress)
	log.Printf("  Target Node ID: %s", appCfg.App.TargetNodeID)
	log.Printf("  Mesh gRPC Address: %s", appCfg.Config.Server.GRPCAddress)
	log.Printf("  Mesh Tunnel Address: %s", appCfg.Config.Listener.Address)

	// 创建并启动内嵌的 mesh server
	log.Printf("📡 Starting embedded gRPC Mesh Server...")
	meshServer, err := server.New(appCfg.Config)
	if err != nil {
		log.Fatalf("Failed to create mesh server: %v", err)
	}

	if err := meshServer.Start(); err != nil {
		log.Fatalf("Failed to start mesh server: %v", err)
	}
	log.Printf("✅ Mesh server started")
	log.Printf("   - Tunnel listener: %s", appCfg.Config.Listener.Address)
	log.Printf("   - InvokePlane gRPC: %s", appCfg.Config.Server.GRPCAddress)
	log.Printf("   - Metrics: %s", appCfg.Config.Server.MetricsAddress)

	// 创建处理器
	handler := NewCalculatorHandler(meshServer, appCfg.App.TargetNodeID)

	// 创建 Gin 路由
	gin.SetMode(gin.ReleaseMode)
	r := gin.Default()

	// 健康检查
	r.GET("/health", handler.Health)

	// 计算接口
	r.POST("/calculate", handler.Calculate)

	// 节点列表
	r.GET("/nodes", handler.ListNodes)

	// API 文档
	r.GET("/", func(c *gin.Context) {
		c.JSON(http.StatusOK, gin.H{
			"name":    "Calculator Client API (Embedded Mesh)",
			"version": "1.0.0",
			"endpoints": gin.H{
				"POST /calculate": gin.H{
					"description": "Perform calculation",
					"body": gin.H{
						"operation": "add|subtract|multiply|divide",
						"a":         "number",
						"b":         "number",
					},
					"example": gin.H{
						"operation": "add",
						"a":         10,
						"b":         5,
					},
				},
				"GET /health": gin.H{
					"description": "Health check",
				},
				"GET /nodes": gin.H{
					"description": "List connected nodes",
				},
			},
		})
	})

	// 启动 HTTP 服务器
	srv := &http.Server{
		Addr:    appCfg.App.ListenAddress,
		Handler: r,
	}

	go func() {
		log.Printf("🚀 Calculator Client listening on %s", appCfg.App.ListenAddress)
		log.Printf("📖 API Documentation: http://localhost%s/", appCfg.App.ListenAddress)
		log.Printf("")
		log.Printf("💡 Example:")
		log.Printf("   curl -X POST http://localhost%s/calculate \\", appCfg.App.ListenAddress)
		log.Printf("     -H 'Content-Type: application/json' \\")
		log.Printf("     -d '{\"operation\": \"add\", \"a\": 10, \"b\": 5}'")
		log.Printf("")

		if err := srv.ListenAndServe(); err != nil && err != http.ErrServerClosed {
			log.Fatalf("Failed to start HTTP server: %v", err)
		}
	}()

	// 等待中断信号
	quit := make(chan os.Signal, 1)
	signal.Notify(quit, syscall.SIGINT, syscall.SIGTERM)
	<-quit

	log.Println("🛑 Shutting down server...")

	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()

	if err := srv.Shutdown(ctx); err != nil {
		log.Printf("HTTP server forced to shutdown: %v", err)
	}

	meshServer.Stop()

	log.Println("✅ Server stopped gracefully")
}
