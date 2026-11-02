//! 代理：SOCKS5 / HTTP / HTTPS（TLS 到代理）。
//!
//! 只做一件事：**建立一条"到目标"的隧道**，把 `Read + Write` 交回去。
//! 之后的 HTTP 帧、TLS、响应解析都不在这里——`transport.rs` 那条路一行不用改。
//!
//! 四种类型（用户 2026-11-02 定的）：
//!
//! | 类型 | 到代理 | 到目标 |
//! |---|---|---|
//! | SOCKS5 | 明文 TCP + 握手（可选用户名/密码） | `CONNECT host:port` |
//! | HTTP | 明文 TCP | `http://` 用绝对 URI；`https://` 用 `CONNECT` |
//! | HTTPS | **TLS 到代理**（内置根） | 同上 |
//! | HTTPS（本地凭据） | TLS 到代理，验**本机**信任库 | 同上 |
//!
//! 「本地凭据」这一档在 Android 上要平台 TrustManager + `network_security_config`，
//! 这一版**明确报不支持**（`Unsupported`），不假装能验——见 `docs/proxy.md` §1。

use std::io::{Read, Write};
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, TcpStream, ToSocketAddrs};
use std::time::Duration;

use crate::transport::{TransportError, Url};

/// 代理类型。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ProxyKind {
    Socks5,
    Http,
    Https,
}

impl ProxyKind {
    pub fn as_str(self) -> &'static str {
        match self {
            ProxyKind::Socks5 => "socks5",
            ProxyKind::Http => "http",
            ProxyKind::Https => "https",
        }
    }

    pub fn parse(text: &str) -> Option<Self> {
        match text.trim().to_ascii_lowercase().as_str() {
            "socks5" | "socks" => Some(ProxyKind::Socks5),
            "http" => Some(ProxyKind::Http),
            "https" => Some(ProxyKind::Https),
            _ => None,
        }
    }
}

/// 验代理证书用哪一份根。
#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum ProxyTrust {
    /// 内置的 Mozilla 根（`webpki-roots`）。三端一致，不信任用户装的证书。
    #[default]
    Bundle,
    /// 本机信任库（系统根 + 用户安装的 CA）。更宽松，只在用户明确选它时生效。
    Local,
}

/// 一份代理配置。字段与设置页一一对应。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ProxyConfig {
    pub kind: ProxyKind,
    pub host: String,
    pub port: u16,
    pub username: Option<String>,
    pub password: Option<String>,
    /// 绕过列表：`localhost,10.0.0.0/8,*.corp.example`。
    pub bypass: Vec<String>,
    pub trust: ProxyTrust,
}

impl ProxyConfig {
    /// 把界面那一行拆成条目：逗号 / 换行 / 分号分隔，去空白，丢空项。
    pub fn parse_bypass(text: &str) -> Vec<String> {
        text.split([',', '\n', ';'])
            .map(str::trim)
            .filter(|item| !item.is_empty())
            .map(str::to_string)
            .collect()
    }

    /// 这个目标主机要不要绕过代理。
    ///
    /// 只看**主机名**，不看解析后的 IP——`localhost` 命中就该直连，不该先做一次 DNS。
    pub fn bypassed(&self, host: &str) -> bool {
        let host = host.trim().trim_end_matches('.').to_ascii_lowercase();
        self.bypass.iter().any(|rule| matches_rule(rule, &host))
    }
}

fn matches_rule(rule: &str, host: &str) -> bool {
    let rule = rule.trim();
    if rule.is_empty() {
        return false;
    }
    if let Some((network, bits)) = rule.split_once('/') {
        if let (Ok(network), Ok(bits)) = (network.parse::<IpAddr>(), bits.parse::<u8>()) {
            if let Ok(host) = host.parse::<IpAddr>() {
                return cidr_contains(network, bits, host);
            }
            return false;
        }
        // `*.example.com/24` 这种写法没有意义，按不匹配处理。
        return false;
    }
    if let Some(suffix) = rule.strip_prefix("*.") {
        let suffix = suffix.to_ascii_lowercase();
        return host == suffix || host.ends_with(&format!(".{suffix}"));
    }
    let rule = rule.trim_end_matches('.').to_ascii_lowercase();
    host == rule
}

fn cidr_contains(network: IpAddr, bits: u8, host: IpAddr) -> bool {
    match (network, host) {
        (IpAddr::V4(network), IpAddr::V4(host)) => {
            if bits > 32 {
                return false;
            }
            let mask = if bits == 0 {
                0
            } else {
                u32::MAX << (32 - bits)
            };
            (u32::from(network) & mask) == (u32::from(host) & mask)
        }
        (IpAddr::V6(network), IpAddr::V6(host)) => {
            if bits > 128 {
                return false;
            }
            let mask = if bits == 0 {
                0
            } else {
                u128::MAX << (128 - bits)
            };
            (u128::from(network) & mask) == (u128::from(host) & mask)
        }
        _ => false,
    }
}

/// 一条已经打通到目标的隧道。`Box` 是因为 HTTPS 代理那条路上它可能已经套了一层 TLS。
pub trait ReadWrite: Read + Write {}
impl<T: Read + Write> ReadWrite for T {}

/// 隧道 + 这条隧道要不要用"绝对 URI"发请求。
///
/// 明文 HTTP 走 HTTP 代理时，请求行必须是 `GET http://host/path HTTP/1.1`；
/// 走 SOCKS5 / CONNECT 时仍然是 `GET /path HTTP/1.1`。这个差别只有代理知道。
pub struct Tunnel {
    pub stream: Box<dyn ReadWrite + Send>,
    pub absolute_form: bool,
}

impl Tunnel {
    fn plain(stream: TcpStream) -> Self {
        Self {
            stream: Box::new(stream),
            absolute_form: false,
        }
    }
}

/// 建立隧道。`read_timeout` 是隧道打通之后套在 socket 上的读超时。
pub fn connect(
    proxy: &ProxyConfig,
    target: &Url,
    connect_timeout: Duration,
    read_timeout: Option<Duration>,
) -> Result<Tunnel, TransportError> {
    if proxy.bypassed(&target.host) {
        return Err(TransportError::Unsupported(
            "这个目标在绕过列表里，不该走代理".into(),
        ));
    }

    let raw = connect_tcp(&proxy.host, proxy.port, connect_timeout)?;
    raw.set_read_timeout(Some(connect_timeout))
        .and_then(|_| raw.set_write_timeout(Some(connect_timeout)))
        .map_err(|error| TransportError::Io(format!("设置代理握手超时: {error}")))?;

    match proxy.kind {
        ProxyKind::Socks5 => {
            let mut stream = raw;
            socks5_handshake(&mut stream, proxy, &target.host, target.port)?;
            set_timeouts(&stream, read_timeout)?;
            Ok(Tunnel::plain(stream))
        }
        ProxyKind::Http => {
            if target.scheme == "http" {
                // 不 CONNECT：明文 HTTP 用绝对 URI 直接交给代理，兼容性最好
                // （很多代理默认拒绝 CONNECT 到 80 端口）。
                set_timeouts(&raw, read_timeout)?;
                Ok(Tunnel {
                    stream: Box::new(raw),
                    absolute_form: true,
                })
            } else {
                let mut stream = raw;
                http_connect(&mut stream, proxy, &target.host, target.port)?;
                set_timeouts(&stream, read_timeout)?;
                Ok(Tunnel::plain(stream))
            }
        }
        ProxyKind::Https => {
            #[cfg(feature = "tls")]
            {
                let mut tls = wrap_proxy_tls(raw, proxy)?;
                http_connect(&mut tls, proxy, &target.host, target.port)?;
                // 隧道套在 TLS 里，但底层还是那个 socket：从 `get_ref()` 拿回来设超时。
                set_timeouts(tls.get_ref(), read_timeout)?;
                Ok(Tunnel {
                    stream: Box::new(tls),
                    absolute_form: false,
                })
            }
            #[cfg(not(feature = "tls"))]
            {
                Err(TransportError::Unsupported(
                    "这个构建没开 tls，HTTPS 代理用不了".into(),
                ))
            }
        }
    }
}

fn set_timeouts(stream: &TcpStream, read_timeout: Option<Duration>) -> Result<(), TransportError> {
    stream
        .set_read_timeout(read_timeout)
        .and_then(|_| stream.set_write_timeout(read_timeout))
        .map_err(|error| TransportError::Io(format!("设置读超时: {error}")))
}

fn connect_tcp(host: &str, port: u16, timeout: Duration) -> Result<TcpStream, TransportError> {
    let addresses = (host, port)
        .to_socket_addrs()
        .map_err(|error| TransportError::Connect {
            host: format!("{host}:{port}"),
            message: format!("代理域名解析失败: {error}"),
        })?;
    let mut last_error = None;
    for address in addresses {
        match TcpStream::connect_timeout(&address, timeout) {
            Ok(stream) => {
                let _ = stream.set_nodelay(true);
                return Ok(stream);
            }
            Err(error) => last_error = Some(error),
        }
    }
    Err(TransportError::Connect {
        host: format!("{host}:{port}"),
        message: match last_error {
            Some(error) => format!("连不上代理: {error}"),
            None => "代理没有可用的地址".to_string(),
        },
    })
}

fn write_all(stream: &mut impl Write, bytes: &[u8], what: &str) -> Result<(), TransportError> {
    stream
        .write_all(bytes)
        .and_then(|_| stream.flush())
        .map_err(|error| TransportError::Io(format!("{what}: {error}")))
}

fn read_exact(stream: &mut impl Read, buf: &mut [u8], what: &str) -> Result<(), TransportError> {
    stream
        .read_exact(buf)
        .map_err(|error| TransportError::Io(format!("{what}: {error}")))
}

fn socks5_handshake(
    stream: &mut TcpStream,
    proxy: &ProxyConfig,
    host: &str,
    port: u16,
) -> Result<(), TransportError> {
    let has_auth = proxy.username.is_some();
    let mut greeting = vec![0x05u8];
    if has_auth {
        greeting.extend_from_slice(&[0x02, 0x00, 0x02]);
    } else {
        greeting.extend_from_slice(&[0x01, 0x00]);
    }
    write_all(stream, &greeting, "SOCKS5 问候")?;

    let mut choice = [0u8; 2];
    read_exact(stream, &mut choice, "SOCKS5 选择方法")?;
    if choice[0] != 0x05 {
        return Err(TransportError::Protocol(format!(
            "代理不是 SOCKS5（版本字节 {}）",
            choice[0]
        )));
    }
    match choice[1] {
        0x00 => {}
        0x02 => {
            let user = proxy.username.clone().unwrap_or_default();
            let pass = proxy.password.clone().unwrap_or_default();
            if user.len() > 255 || pass.len() > 255 {
                return Err(TransportError::Unsupported(
                    "SOCKS5 用户名/密码超过 255 字节".into(),
                ));
            }
            let mut auth = vec![0x01u8, user.len() as u8];
            auth.extend_from_slice(user.as_bytes());
            auth.push(pass.len() as u8);
            auth.extend_from_slice(pass.as_bytes());
            write_all(stream, &auth, "SOCKS5 认证")?;
            let mut reply = [0u8; 2];
            read_exact(stream, &mut reply, "SOCKS5 认证结果")?;
            if reply[1] != 0x00 {
                return Err(TransportError::Connect {
                    host: format!("{}:{}", proxy.host, proxy.port),
                    message: "SOCKS5 认证被拒（用户名或密码不对）".into(),
                });
            }
        }
        0xff => {
            return Err(TransportError::Connect {
                host: format!("{}:{}", proxy.host, proxy.port),
                message: "SOCKS5 不接受我们提供的方法（需要认证？）".into(),
            })
        }
        other => {
            return Err(TransportError::Protocol(format!(
                "SOCKS5 选了不认识的方法 {other:#04x}"
            )))
        }
    }

    // CONNECT：域名优先（让代理那边解析，避免本地 DNS 泄漏/失败）。
    let mut request = vec![0x05u8, 0x01, 0x00];
    match host.parse::<Ipv4Addr>() {
        Ok(ip) => {
            request.push(0x01);
            request.extend_from_slice(&ip.octets());
        }
        Err(_) => match host.parse::<Ipv6Addr>() {
            Ok(ip) => {
                request.push(0x04);
                request.extend_from_slice(&ip.octets());
            }
            Err(_) => {
                if host.len() > 255 {
                    return Err(TransportError::Unsupported("主机名超过 255 字节".into()));
                }
                request.push(0x03);
                request.push(host.len() as u8);
                request.extend_from_slice(host.as_bytes());
            }
        },
    }
    request.extend_from_slice(&port.to_be_bytes());
    write_all(stream, &request, "SOCKS5 CONNECT")?;

    let mut head = [0u8; 4];
    read_exact(stream, &mut head, "SOCKS5 CONNECT 回复")?;
    if head[0] != 0x05 {
        return Err(TransportError::Protocol(format!(
            "SOCKS5 回复版本不对：{}",
            head[0]
        )));
    }
    if head[1] != 0x00 {
        let reason = match head[1] {
            0x01 => "代理内部错误",
            0x02 => "代理不允许这次连接",
            0x03 => "网络不可达",
            0x04 => "目标主机不可达",
            0x05 => "目标拒绝连接",
            0x06 => "TTL 过期",
            0x07 => "代理不支持 CONNECT",
            0x08 => "代理不支持这种地址类型",
            _ => "未知原因",
        };
        return Err(TransportError::Connect {
            host: format!("{host}:{port}"),
            message: format!("SOCKS5 拒绝了 CONNECT：{reason}（{:#04x}）", head[1]),
        });
    }
    // 把绑定地址读掉：IPv4 4+2、IPv6 16+2、域名 1+len+2。
    let skip = match head[3] {
        0x01 => 4 + 2,
        0x04 => 16 + 2,
        0x03 => {
            let mut len = [0u8; 1];
            read_exact(stream, &mut len, "SOCKS5 绑定地址长度")?;
            len[0] as usize + 2
        }
        other => {
            return Err(TransportError::Protocol(format!(
                "SOCKS5 回复里的地址类型不认识：{other:#04x}"
            )))
        }
    };
    let mut discard = vec![0u8; skip];
    read_exact(stream, &mut discard, "SOCKS5 绑定地址")?;
    Ok(())
}

fn http_connect(
    stream: &mut impl ReadWrite,
    proxy: &ProxyConfig,
    host: &str,
    port: u16,
) -> Result<(), TransportError> {
    let authority = if host.contains(':') {
        format!("[{host}]:{port}")
    } else {
        format!("{host}:{port}")
    };
    let mut head = format!(
        "CONNECT {authority} HTTP/1.1\r\nhost: {authority}\r\nproxy-connection: keep-alive\r\n"
    );
    if let Some(user) = proxy.username.as_deref() {
        let pass = proxy.password.as_deref().unwrap_or("");
        let raw = format!("{user}:{pass}");
        head.push_str(&format!(
            "proxy-authorization: Basic {}\r\n",
            crate::base64::encode(raw.as_bytes())
        ));
    }
    head.push_str("\r\n");
    write_all(stream, head.as_bytes(), "HTTP 代理 CONNECT")?;

    // 逐字节读到空行为止：多读一个字节就会把隧道里的数据吃掉。
    let mut line = Vec::new();
    let mut status = None;
    loop {
        let mut byte = [0u8; 1];
        read_exact(stream, &mut byte, "读代理回复")?;
        line.push(byte[0]);
        if line.ends_with(b"\r\n") {
            let text = String::from_utf8_lossy(&line).trim().to_string();
            if status.is_none() {
                status = Some(text.clone());
                if !text.starts_with("HTTP/1.") {
                    return Err(TransportError::Protocol(format!(
                        "代理回的不是 HTTP：{text}"
                    )));
                }
            }
            if line == b"\r\n" {
                break;
            }
            line.clear();
        }
        if line.len() > 8 * 1024 {
            return Err(TransportError::Protocol("代理回复头超过 8 KiB".into()));
        }
    }

    let status = status.unwrap_or_default();
    let code: u16 = status
        .split_whitespace()
        .nth(1)
        .and_then(|code| code.parse().ok())
        .ok_or_else(|| TransportError::Protocol(format!("代理状态行看不懂：{status}")))?;
    match code {
        200..=299 => Ok(()),
        407 => Err(TransportError::Connect {
            host: format!("{}:{}", proxy.host, proxy.port),
            message: "代理要求认证（407），检查用户名/密码".into(),
        }),
        _ => Err(TransportError::Connect {
            host: format!("{}:{}", proxy.host, proxy.port),
            message: format!("代理拒绝了 CONNECT：{status}"),
        }),
    }
}

#[cfg(feature = "tls")]
fn wrap_proxy_tls(
    raw: TcpStream,
    proxy: &ProxyConfig,
) -> Result<rustls::StreamOwned<rustls::ClientConnection, TcpStream>, TransportError> {
    use std::sync::Arc;

    if proxy.trust == ProxyTrust::Local {
        return Err(TransportError::Unsupported(
            "HTTPS（本地凭据）还没接：Android 上要走平台 TrustManager + network_security_config（0.15ar-d）"
                .into(),
        ));
    }

    let provider = Arc::new(rustls::crypto::ring::default_provider());
    let mut roots = rustls::RootCertStore::empty();
    roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
    let config = rustls::ClientConfig::builder_with_provider(provider)
        .with_safe_default_protocol_versions()
        .map_err(|error| TransportError::Protocol(format!("TLS 协议版本: {error}")))?
        .with_root_certificates(roots)
        .with_no_client_auth();

    let server_name =
        rustls::pki_types::ServerName::try_from(proxy.host.clone()).map_err(|error| {
            TransportError::Connect {
                host: proxy.host.clone(),
                message: format!("代理主机名不能用作 TLS 名称: {error}"),
            }
        })?;
    let connection =
        rustls::ClientConnection::new(Arc::new(config), server_name).map_err(|error| {
            TransportError::Connect {
                host: proxy.host.clone(),
                message: format!("到代理的 TLS 初始化失败: {error}"),
            }
        })?;
    Ok(rustls::StreamOwned::new(connection, raw))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::{SocketAddr, TcpListener};
    use std::thread;

    fn cfg(kind: ProxyKind, port: u16) -> ProxyConfig {
        ProxyConfig {
            kind,
            host: "127.0.0.1".into(),
            port,
            username: None,
            password: None,
            bypass: Vec::new(),
            trust: ProxyTrust::Bundle,
        }
    }

    fn target(scheme: &str, host: &str, port: u16) -> Url {
        Url {
            scheme: scheme.into(),
            host: host.into(),
            port,
            path: "/v1/x".into(),
        }
    }

    #[test]
    fn bypass_matches_exact_suffix_and_cidr() {
        let mut proxy = cfg(ProxyKind::Socks5, 1080);
        proxy.bypass = ProxyConfig::parse_bypass(
            "localhost, 127.0.0.1, 10.0.0.0/8, 172.16.0.0/12, 192.168.0.0/16, ::1, *.corp.example",
        );
        assert!(proxy.bypassed("localhost"));
        assert!(proxy.bypassed("LOCALHOST")); // 大小写不敏感
        assert!(proxy.bypassed("127.0.0.1"));
        assert!(proxy.bypassed("10.4.5.6"));
        assert!(!proxy.bypassed("11.4.5.6"));
        assert!(proxy.bypassed("172.20.1.1"));
        assert!(proxy.bypassed("192.168.1.10"));
        assert!(proxy.bypassed("::1"));
        assert!(proxy.bypassed("api.corp.example"));
        assert!(proxy.bypassed("corp.example"));
        assert!(!proxy.bypassed("evilcorp.example"));
        assert!(!proxy.bypassed("example.com"));
    }

    #[test]
    fn bypass_ignores_broken_rules_instead_of_panicking() {
        let mut proxy = cfg(ProxyKind::Socks5, 1080);
        proxy.bypass = ProxyConfig::parse_bypass("10.0.0.0/99,*.corp.example/8,,  ");
        assert!(!proxy.bypassed("10.0.0.1"));
        assert!(!proxy.bypassed("a.corp.example"));
        assert_eq!(proxy.bypass.len(), 2, "空项要丢掉");
    }

    /// 假 SOCKS5：握手 → 回 200 → 之后原样回声。
    fn spawn_socks5(user: Option<(&'static str, &'static str)>) -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut head = [0u8; 2];
            stream.read_exact(&mut head).unwrap();
            let mut methods = vec![0u8; head[1] as usize];
            stream.read_exact(&mut methods).unwrap();
            if let Some((want_user, want_pass)) = user {
                stream.write_all(&[0x05, 0x02]).unwrap();
                let mut auth = [0u8; 2];
                stream.read_exact(&mut auth).unwrap();
                let mut name = vec![0u8; auth[1] as usize];
                stream.read_exact(&mut name).unwrap();
                let mut plen = [0u8; 1];
                stream.read_exact(&mut plen).unwrap();
                let mut pass = vec![0u8; plen[0] as usize];
                stream.read_exact(&mut pass).unwrap();
                let ok = name == want_user.as_bytes() && pass == want_pass.as_bytes();
                stream.write_all(&[0x01, if ok { 0 } else { 1 }]).unwrap();
                if !ok {
                    return;
                }
            } else {
                stream.write_all(&[0x05, 0x00]).unwrap();
            }
            // CONNECT 请求：版本/命令/保留/类型
            let mut request = [0u8; 4];
            stream.read_exact(&mut request).unwrap();
            let skip = match request[3] {
                0x01 => 4 + 2,
                0x04 => 16 + 2,
                0x03 => {
                    let mut len = [0u8; 1];
                    stream.read_exact(&mut len).unwrap();
                    len[0] as usize + 2
                }
                _ => 0,
            };
            let mut discard = vec![0u8; skip];
            stream.read_exact(&mut discard).unwrap();
            // 成功：版本/状态/保留/IPv4 0.0.0.0:0
            stream
                .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
                .unwrap();
            echo(stream);
        });
        addr
    }

    /// 假 HTTP 代理：读 CONNECT 头 → 回 200 → 之后原样回声。
    fn spawn_http_proxy(status: &'static str) -> SocketAddr {
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            let (mut stream, _) = listener.accept().unwrap();
            let mut seen = Vec::new();
            let mut byte = [0u8; 1];
            while !seen.ends_with(b"\r\n\r\n") {
                if stream.read(&mut byte).unwrap_or(0) == 0 {
                    return;
                }
                seen.push(byte[0]);
            }
            stream
                .write_all(format!("HTTP/1.1 {status}\r\n\r\n").as_bytes())
                .unwrap();
            if status.starts_with("200") {
                echo(stream);
            }
        });
        addr
    }

    fn echo(mut stream: TcpStream) {
        let mut buf = [0u8; 64];
        loop {
            match stream.read(&mut buf) {
                Ok(0) | Err(_) => return,
                Ok(read) => {
                    if stream.write_all(&buf[..read]).is_err() {
                        return;
                    }
                }
            }
        }
    }

    #[test]
    fn socks5_without_auth_tunnels_bytes() {
        let addr = spawn_socks5(None);
        let proxy = cfg(ProxyKind::Socks5, addr.port());
        let tunnel = connect(
            &proxy,
            &target("https", "example.com", 443),
            Duration::from_secs(2),
            Some(Duration::from_secs(2)),
        )
        .expect("握手应当成功");
        let mut stream = tunnel.stream;
        stream.write_all(b"ping").unwrap();
        let mut back = [0u8; 4];
        stream.read_exact(&mut back).unwrap();
        assert_eq!(&back, b"ping");
    }

    #[test]
    fn socks5_with_auth_sends_the_credentials() {
        let addr = spawn_socks5(Some(("AEE", "s3cret")));
        let mut proxy = cfg(ProxyKind::Socks5, addr.port());
        proxy.username = Some("AEE".into());
        proxy.password = Some("s3cret".into());
        let tunnel = connect(
            &proxy,
            &target("https", "example.com", 443),
            Duration::from_secs(2),
            Some(Duration::from_secs(2)),
        )
        .expect("认证应当通过");
        drop(tunnel);
    }

    #[test]
    fn socks5_wrong_password_names_the_reason() {
        let addr = spawn_socks5(Some(("AEE", "s3cret")));
        let mut proxy = cfg(ProxyKind::Socks5, addr.port());
        proxy.username = Some("AEE".into());
        proxy.password = Some("wrong".into());
        let error = connect(
            &proxy,
            &target("https", "example.com", 443),
            Duration::from_secs(2),
            Some(Duration::from_secs(2)),
        )
        .err()
        .expect("应当失败");
        assert!(
            error.to_string().contains("认证被拒"),
            "错误要说清是认证，不是网络: {error}"
        );
    }

    #[test]
    fn http_connect_tunnels_and_reports_no_absolute_form() {
        let addr = spawn_http_proxy("200 Connection established");
        let proxy = cfg(ProxyKind::Http, addr.port());
        let tunnel = connect(
            &proxy,
            &target("https", "example.com", 443),
            Duration::from_secs(2),
            Some(Duration::from_secs(2)),
        )
        .expect("CONNECT 应当成功");
        assert!(!tunnel.absolute_form);
        let mut stream = tunnel.stream;
        stream.write_all(b"ping").unwrap();
        let mut back = [0u8; 4];
        stream.read_exact(&mut back).unwrap();
        assert_eq!(&back, b"ping");
    }

    #[test]
    fn http_proxy_on_plain_http_uses_absolute_form() {
        // 明文 http 不 CONNECT：直接交给代理，请求行要写完整 URL。
        let addr = spawn_http_proxy("200 OK");
        let proxy = cfg(ProxyKind::Http, addr.port());
        let tunnel = connect(
            &proxy,
            &target("http", "example.com", 80),
            Duration::from_secs(2),
            Some(Duration::from_secs(2)),
        )
        .expect("应当直接拿到隧道");
        assert!(tunnel.absolute_form, "明文 http 走代理要用绝对 URI");
    }

    #[test]
    fn http_proxy_407_is_reported_as_auth() {
        let addr = spawn_http_proxy("407 Proxy Authentication Required");
        let proxy = cfg(ProxyKind::Http, addr.port());
        let error = connect(
            &proxy,
            &target("https", "example.com", 443),
            Duration::from_secs(2),
            Some(Duration::from_secs(2)),
        )
        .err()
        .expect("应当失败");
        assert!(error.to_string().contains("407"), "{error}");
    }

    #[test]
    fn bypassed_targets_are_refused_here() {
        // transport 会先过滤；真走到这里说明调用方漏了判断，要报错而不是悄悄连代理。
        let mut proxy = cfg(ProxyKind::Socks5, 1);
        proxy.bypass = vec!["example.com".into()];
        let error = connect(
            &proxy,
            &target("https", "example.com", 443),
            Duration::from_millis(200),
            None,
        )
        .err()
        .expect("应当拒绝");
        assert!(error.to_string().contains("绕过"), "{error}");
    }
}
