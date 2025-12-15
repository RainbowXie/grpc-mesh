//! Calculator Service - gRPC-Mesh Demo
//!
//! This service demonstrates how to use the gRPC-Mesh framework to expose
//! an internal service through a reverse tunnel, allowing external clients
//! to invoke it without requiring inbound port forwarding.

use std::env;
use std::fs;
use std::time::Duration;

use grpc_mesh::rpc::InvokeService;
use grpc_mesh::tunnel::{ConnectorConfig, Handshake, TunnelConnector};
use grpc_mesh::{MethodRegistry, RpcResult};
use prost::Message;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use tokio::sync::watch;
use tonic::{transport::Server, Request, Response, Status};
use tracing::{error, info, warn};

// 引入生成的 protobuf 代码
pub mod calculator {
    tonic::include_proto!("calculator.v1");
}

use calculator::{CalcRequest, CalcResponse, HealthRequest, HealthResponse};
use calculator::calculator_server::Calculator;

/// 配置文件结构
#[derive(Debug, Deserialize, Serialize)]
struct Config {
    server: ServerConfig,
    node: NodeConfig,
}

#[derive(Debug, Deserialize, Serialize)]
struct ServerConfig {
    address: String,
    #[serde(default)]
    tls: TlsConfig,
}

#[derive(Debug, Deserialize, Serialize, Default)]
struct TlsConfig {
    #[serde(default = "default_server_name")]
    server_name: String,
}

#[derive(Debug, Deserialize, Serialize)]
struct NodeConfig {
    id: String,
    token: String,
}

fn default_server_name() -> String {
    "localhost".to_string()
}

/// 加载配置（优先从配置文件，其次从环境变量）
fn load_config() -> Result<Config, Box<dyn std::error::Error>> {
    // 1. 尝试从配置文件加载
    let config_path = env::var("CONFIG_PATH").unwrap_or_else(|_| "config/config.json".to_string());

    if let Ok(config_content) = fs::read_to_string(&config_path) {
        info!("📄 Loading configuration from: {}", config_path);
        let config: Config = serde_json::from_str(&config_content)?;
        info!("✅ Configuration loaded from file");
        return Ok(config);
    }

    // 2. 从环境变量构建配置（向后兼容）
    info!("📄 Configuration file not found, using environment variables");

    let tunnel_server = env::var("TUNNEL_SERVER")
        .or_else(|_| env::var("SERVER_ADDRESS"))
        .unwrap_or_else(|_| "localhost:8443".to_string());

    let token = env::var("TUNNEL_TOKEN")
        .or_else(|_| env::var("WA_NODE_TOKEN"))
        .map_err(|_| {
            "❌ Token not found. Set TUNNEL_TOKEN environment variable or provide config.json"
        })?;

    let node_id = env::var("NODE_ID")
        .or_else(|_| env::var("WA_NODE_ID"))
        .unwrap_or_else(|_| "calculator-service".to_string());

    info!("✅ Configuration loaded from environment variables");

    Ok(Config {
        server: ServerConfig {
            address: tunnel_server,
            tls: TlsConfig::default(),
        },
        node: NodeConfig { id: node_id, token },
    })
}

/// 计算器服务实现
pub struct CalculatorService;

#[tonic::async_trait]
impl Calculator for CalculatorService {
    /// 加法
    async fn add(&self, request: Request<CalcRequest>) -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();
        let result = req.a + req.b;

        info!("Add: {} + {} = {}", req.a, req.b, result);

        Ok(Response::new(CalcResponse { result }))
    }

    /// 减法
    async fn subtract(
        &self,
        request: Request<CalcRequest>,
    ) -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();
        let result = req.a - req.b;

        info!("Subtract: {} - {} = {}", req.a, req.b, result);

        Ok(Response::new(CalcResponse { result }))
    }

    /// 乘法
    async fn multiply(
        &self,
        request: Request<CalcRequest>,
    ) -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();
        let result = req.a * req.b;

        info!("Multiply: {} * {} = {}", req.a, req.b, result);

        Ok(Response::new(CalcResponse { result }))
    }

    /// 除法
    async fn divide(
        &self,
        request: Request<CalcRequest>,
    ) -> Result<Response<CalcResponse>, Status> {
        let req = request.into_inner();

        if req.b == 0.0 {
            return Err(Status::invalid_argument("Division by zero"));
        }

        let result = req.a / req.b;

        info!("Divide: {} / {} = {}", req.a, req.b, result);

        Ok(Response::new(CalcResponse { result }))
    }

    /// 健康检查
    async fn health(
        &self,
        request: Request<HealthRequest>,
    ) -> Result<Response<HealthResponse>, Status> {
        let req = request.into_inner();
        info!("Health check: {}", req.message);

        Ok(Response::new(HealthResponse {
            status: "OK".to_string(),
            message: format!("Calculator Service is healthy. Echo: {}", req.message),
        }))
    }
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // 初始化日志
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    info!("╔════════════════════════════════════════════════════╗");
    info!("║  Calculator Service - gRPC-Mesh Demo              ║");
    info!("╚════════════════════════════════════════════════════╝");

    // 加载配置
    let config = load_config()?;

    // 加载 CA 证书（如果提供）
    let ca_cert_path = env::var("CA_CERT_PATH")
        .unwrap_or_else(|_| "../../grpc-mesh-server/config/tls/ca.crt".to_string());

    let mut ca_certs = Vec::new();
    if let Ok(ca_pem) = fs::read(&ca_cert_path) {
        ca_certs.push(ca_pem);
        info!("✅ Loaded CA certificate from: {}", ca_cert_path);
    } else {
        warn!("⚠️  CA certificate not found at: {}", ca_cert_path);
        warn!("⚠️  Will use system CA store (may fail with self-signed certs)");
    }

    info!("Configuration:");
    info!("  Tunnel Server: {}", config.server.address);
    info!("  Node ID: {}", config.node.id);
    info!(
        "  Token: {}...",
        &config.node.token.chars().take(10).collect::<String>()
    );

    // 创建隧道连接器配置
    let connector_config = ConnectorConfig {
        server_addr: config.server.address.clone(),
        ca_certs,
        sni: None,
        connect_timeout: Duration::from_secs(10),
        max_backoff: Duration::from_secs(30),
        heartbeat_interval: Duration::from_secs(15),
    };

    // 创建 shutdown 信号
    let (shutdown_tx, shutdown_rx) = watch::channel(false);

    // 构建隧道连接器
    let mut connector = TunnelConnector::new(connector_config, shutdown_rx)
        .map_err(|e| format!("Failed to create tunnel connector: {}", e))?;

    // 构建握手信息
    let handshake = Handshake::builder(&config.node.id, env!("CARGO_PKG_VERSION"))
        .token(config.node.token)
        .add_feature("grpc")
        .add_feature("calculator")
        .metadata_entry("service", "calculator")
        .metadata_entry("language", "rust")
        .build()
        .map_err(|e| format!("Failed to build handshake: {}", e))?;

    info!("📡 Connecting to gRPC-Mesh control plane...");

    // 连接到控制平面（带自动重连和心跳）
    // 框架会自动启动心跳，业务代码无需关心
    let incoming = match connector.connect_with_backoff(handshake).await {
        Ok(incoming) => {
            info!("✅ Tunnel established successfully");
            incoming
        }
        Err(e) => {
            error!("❌ Failed to establish tunnel: {:?}", e);
            error!("   This usually means:");
            error!("   1. Control plane is not running or not reachable");
            error!("   2. TLS certificate verification failed");
            error!("   3. Network connectivity issues");
            return Err(format!("Failed to establish tunnel: {}", e).into());
        }
    };

    info!("✅ Service registered with control plane");
    info!("🔒 TLS connection secured");
    info!("🌐 Yamux multiplexing enabled");
    info!("🚀 Calculator Service is now listening through the reverse tunnel");
    info!("💡 Note: No local port is exposed - all traffic comes through the tunnel");

    // 创建 MethodRegistry 并注册所有方法
    let registry = MethodRegistry::default();
    register_calculator_methods(&registry);
    info!("✅ Registered Calculator methods in MethodRegistry");

    // 创建 InvokeService（实现 InvokePlane gRPC 服务）
    let invoke_service = InvokeService::new(registry).into_server();

    // 设置 shutdown 信号处理
    let shutdown_handle = shutdown_tx.clone();
    tokio::spawn(async move {
        tokio::signal::ctrl_c()
            .await
            .expect("Failed to listen for ctrl-c");
        info!("📭 Shutdown signal received");
        let _ = shutdown_handle.send(true);
    });

    // 通过隧道提供服务
    info!("✨ Ready to handle requests");

    if let Err(e) = Server::builder()
        .add_service(invoke_service)
        .serve_with_incoming(incoming)
        .await
    {
        error!("Server error: {}", e);
        return Err(e.into());
    }

    info!("👋 Calculator Service shut down gracefully");
    Ok(())
}

/// 注册所有 Calculator 方法到 MethodRegistry
fn register_calculator_methods(registry: &MethodRegistry) {
    // calculator.v1.Calculator/Add
    registry.register(
        "calculator.v1.Calculator/Add",
        Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
            let req = CalcRequest::decode(&payload[..])
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("decode error: {}", e)))?;
            let result = req.a + req.b;
            info!("Add: {} + {} = {}", req.a, req.b, result);
            let resp = CalcResponse { result };
            let mut buf = Vec::new();
            resp.encode(&mut buf)
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("encode error: {}", e)))?;
            Ok(buf)
        }),
    );

    // calculator.v1.Calculator/Subtract
    registry.register(
        "calculator.v1.Calculator/Subtract",
        Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
            let req = CalcRequest::decode(&payload[..])
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("decode error: {}", e)))?;
            let result = req.a - req.b;
            info!("Subtract: {} - {} = {}", req.a, req.b, result);
            let resp = CalcResponse { result };
            let mut buf = Vec::new();
            resp.encode(&mut buf)
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("encode error: {}", e)))?;
            Ok(buf)
        }),
    );

    // calculator.v1.Calculator/Multiply
    registry.register(
        "calculator.v1.Calculator/Multiply",
        Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
            let req = CalcRequest::decode(&payload[..])
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("decode error: {}", e)))?;
            let result = req.a * req.b;
            info!("Multiply: {} * {} = {}", req.a, req.b, result);
            let resp = CalcResponse { result };
            let mut buf = Vec::new();
            resp.encode(&mut buf)
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("encode error: {}", e)))?;
            Ok(buf)
        }),
    );

    // calculator.v1.Calculator/Divide
    registry.register(
        "calculator.v1.Calculator/Divide",
        Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
            let req = CalcRequest::decode(&payload[..])
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("decode error: {}", e)))?;
            if req.b == 0.0 {
                return Err(grpc_mesh::RpcError::Internal("Division by zero".to_string()));
            }
            let result = req.a / req.b;
            info!("Divide: {} / {} = {}", req.a, req.b, result);
            let resp = CalcResponse { result };
            let mut buf = Vec::new();
            resp.encode(&mut buf)
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("encode error: {}", e)))?;
            Ok(buf)
        }),
    );

    // calculator.v1.Calculator/Health
    registry.register(
        "calculator.v1.Calculator/Health",
        Arc::new(|payload: Vec<u8>| -> RpcResult<Vec<u8>> {
            let req = HealthRequest::decode(&payload[..])
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("decode error: {}", e)))?;
            info!("Health check: {}", req.message);
            let resp = HealthResponse {
                status: "OK".to_string(),
                message: format!("Calculator Service is healthy. Echo: {}", req.message),
            };
            let mut buf = Vec::new();
            resp.encode(&mut buf)
                .map_err(|e| grpc_mesh::RpcError::Internal(format!("encode error: {}", e)))?;
            Ok(buf)
        }),
    );
}
