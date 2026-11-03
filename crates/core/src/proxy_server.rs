//! 本地 SOCKS5 端点：给容器里的垫片用（0.15ar-e）。
//!
//! 链路：`容器里的程序 → 垫片(.so) → 本机 127.0.0.1:<随机端口> → 这里 → 上游代理 → 目标`。
//!
//! 为什么不是 unix socket：proot 的 `-b` 对 `sockaddr_un` 的路径翻译没验过，
//! 而 loopback TCP 在 proot 里是确定能通的。代价是**同设备上别的 App 也连得到**，
//! 所以这里强制用户名/密码（每次启动随机），凭证写在容器的 `/etc/nmoi-net.conf` 里。
//!
//! 为什么垫片不直接连上游代理：垫片是 C，不该背 TLS、认证、协议差异；
//! 这一层把 `SOCKS5 / HTTP / HTTPS` 三种上游都收在这里。
//!
//! 能力边界（写清楚，别在后面才发现）：
//!
//! - 上游是 `HTTPS` 代理时**不支持**（两层 TLS 拆不出双向），启动时就报错；
//! - 只搬 TCP；UDP / ICMP 不覆盖；
//! - 静态链接的程序绕过垫片，根本不经过这里。

use std::io::{Read, Write};
use std::net::{Ipv4Addr, SocketAddr, TcpListener, TcpStream};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;

use crate::proxy::{self, ProxyHandle, ProxyKind};
use crate::transport::{TransportError, Url};

/// 同时最多接这么多条。容器里 `npm install` 会开一堆连接，但 64 够用；
/// 再多的意义是防"某个程序把 fd 用光"。
const MAX_CONNECTIONS: usize = 64;

/// 一条连接的读写超时。空闲隧道不该永远占着 fd。
const IDLE_TIMEOUT: Duration = Duration::from_secs(300);

/// 本机 SOCKS5 端点。
pub struct ProxyServer {
    addr: SocketAddr,
    user: String,
    pass: String,
    stop: Arc<AtomicBool>,
    open: Arc<AtomicUsize>,
    /// 最近一次连接为什么被关掉。
    ///
    /// 为什么要留这个：`handle_client` 的错误原本被 `let _ =` 丢掉了，于是 Windows 上
    /// 那条测试红了两轮，只看到客户端读到 RST（os error 10054），不知道服务端为什么先关。
    /// 排查一次要一轮 CI，太贵。
    last_error: Arc<Mutex<Option<String>>>,
    handle: Option<thread::JoinHandle<()>>,
}

impl ProxyServer {
    /// 起一个端点。端口由系统分配（`127.0.0.1:0`），不写死。
    pub fn start(
        upstream: ProxyHandle,
        user: String,
        pass: String,
    ) -> Result<Self, TransportError> {
        if let Some(upstream) = upstream.current() {
            if upstream.kind == ProxyKind::Https {
                return Err(TransportError::Unsupported(
                    "容器链路暂不支持 HTTPS 代理上游（两层 TLS 拆不出双向）；\
                     应用自身的请求不受影响"
                        .into(),
                ));
            }
        }
        if user.is_empty() || pass.is_empty() {
            return Err(TransportError::Unsupported(
                "本地端点必须带用户名/密码：同设备别的 App 也连得到 loopback".into(),
            ));
        }

        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).map_err(|error| {
            TransportError::Connect {
                host: "127.0.0.1".into(),
                message: format!("本地代理端点起不来: {error}"),
            }
        })?;
        let addr = listener
            .local_addr()
            .map_err(|error| TransportError::Io(format!("取本地端口失败: {error}")))?;
        listener
            .set_nonblocking(true)
            .map_err(|error| TransportError::Io(format!("监听设非阻塞失败: {error}")))?;

        let stop = Arc::new(AtomicBool::new(false));
        let open = Arc::new(AtomicUsize::new(0));
        let last_error = Arc::new(Mutex::new(None));
        let handle = {
            let stop = stop.clone();
            let open = open.clone();
            let last_error = last_error.clone();
            let user = user.clone();
            let pass = pass.clone();
            thread::spawn(move || {
                accept_loop(listener, stop, open, last_error, upstream, user, pass)
            })
        };

        Ok(Self {
            addr,
            user,
            pass,
            stop,
            open,
            last_error,
            handle: Some(handle),
        })
    }

    pub fn addr(&self) -> SocketAddr {
        self.addr
    }

    pub fn credentials(&self) -> (&str, &str) {
        (&self.user, &self.pass)
    }

    /// 现在有多少条连接开着（给界面显示）。
    pub fn open_connections(&self) -> usize {
        self.open.load(Ordering::SeqCst)
    }

    /// 最近一条连接为什么被关掉（`None` = 还没出过错）。
    ///
    /// 客户端只会看到"连接被重置"这种没有信息量的症状，原因在这一侧——
    /// 所以它得能被读出来，而不是跟着线程一起消失。
    pub fn last_error(&self) -> Option<String> {
        self.last_error
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner())
            .clone()
    }

    /// 停掉：关监听、等它退出。已经在搬的连接随进程/线程结束而断。
    pub fn stop(mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

impl Drop for ProxyServer {
    fn drop(&mut self) {
        self.stop.store(true, Ordering::SeqCst);
        if let Some(handle) = self.handle.take() {
            let _ = handle.join();
        }
    }
}

fn accept_loop(
    listener: TcpListener,
    stop: Arc<AtomicBool>,
    open: Arc<AtomicUsize>,
    last_error: Arc<Mutex<Option<String>>>,
    upstream: ProxyHandle,
    user: String,
    pass: String,
) {
    while !stop.load(Ordering::SeqCst) {
        match listener.accept() {
            Ok((stream, _)) => {
                if open.load(Ordering::SeqCst) >= MAX_CONNECTIONS {
                    // 满了就干脆关掉：比排队让对面超时更诚实。
                    drop(stream);
                    continue;
                }
                // **接受来的连接必须是阻塞的**。监听设了非阻塞（要靠它做 stop 轮询），
                // 而 Windows 上 `accept` 出来的 socket 会**继承**这个状态：于是握手时
                // 「读问候」能读到已经到达的那几个字节，紧接着「读认证」在数据还没到时
                // 立刻返回 `WouldBlock`，服务端当场关连接，客户端那边就是
                // `os error 10054`（RST）。Linux 不继承，所以这条只在 Windows 上红。
                let _ = stream.set_nonblocking(false);
                open.fetch_add(1, Ordering::SeqCst);
                let upstream = upstream.clone();
                let open = open.clone();
                let errors = last_error.clone();
                let user = user.clone();
                let pass = pass.clone();
                thread::spawn(move || {
                    if let Err(error) = handle_client(stream, &upstream, &user, &pass) {
                        *errors
                            .lock()
                            .unwrap_or_else(|poisoned| poisoned.into_inner()) =
                            Some(error.to_string());
                    }
                    open.fetch_sub(1, Ordering::SeqCst);
                });
            }
            Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                thread::sleep(Duration::from_millis(20));
            }
            Err(_) => break,
        }
    }
}

fn handle_client(
    mut client: TcpStream,
    upstream: &ProxyHandle,
    user: &str,
    pass: &str,
) -> Result<(), TransportError> {
    client
        .set_read_timeout(Some(IDLE_TIMEOUT))
        .and_then(|_| client.set_write_timeout(Some(IDLE_TIMEOUT)))
        .map_err(|error| TransportError::Io(format!("设置连接超时: {error}")))?;

    // 问候：只接受"用户名/密码"这一种方法。
    let mut head = [0u8; 2];
    read_exact(&mut client, &mut head)?;
    if head[0] != 0x05 {
        return Err(TransportError::Protocol("不是 SOCKS5".into()));
    }
    let mut methods = vec![0u8; head[1] as usize];
    read_exact(&mut client, &mut methods)?;
    if !methods.contains(&0x02) {
        let _ = client.write_all(&[0x05, 0xff]);
        return Err(TransportError::Unsupported(
            "客户端不支持用户名/密码认证".into(),
        ));
    }
    client
        .write_all(&[0x05, 0x02])
        .map_err(|error| TransportError::Io(format!("回问候: {error}")))?;

    // 认证
    let mut auth_head = [0u8; 2];
    read_exact(&mut client, &mut auth_head)?;
    let mut name = vec![0u8; auth_head[1] as usize];
    read_exact(&mut client, &mut name)?;
    let mut plen = [0u8; 1];
    read_exact(&mut client, &mut plen)?;
    let mut password = vec![0u8; plen[0] as usize];
    read_exact(&mut client, &mut password)?;
    let ok = name == user.as_bytes() && password == pass.as_bytes();
    client
        .write_all(&[0x01, if ok { 0 } else { 1 }])
        .map_err(|error| TransportError::Io(format!("回认证: {error}")))?;
    if !ok {
        return Err(TransportError::Connect {
            host: "127.0.0.1".into(),
            message: "本地端点认证失败".into(),
        });
    }

    // CONNECT
    let mut request = [0u8; 4];
    read_exact(&mut client, &mut request)?;
    if request[1] != 0x01 {
        let _ = client.write_all(&[0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
        return Err(TransportError::Unsupported("只支持 CONNECT".into()));
    }
    let host = match request[3] {
        0x01 => {
            let mut raw = [0u8; 4];
            read_exact(&mut client, &mut raw)?;
            Ipv4Addr::from(raw).to_string()
        }
        0x04 => {
            let mut raw = [0u8; 16];
            read_exact(&mut client, &mut raw)?;
            std::net::Ipv6Addr::from(raw).to_string()
        }
        0x03 => {
            let mut len = [0u8; 1];
            read_exact(&mut client, &mut len)?;
            let mut raw = vec![0u8; len[0] as usize];
            read_exact(&mut client, &mut raw)?;
            String::from_utf8(raw).map_err(|_| TransportError::Protocol("域名不是 UTF-8".into()))?
        }
        other => {
            let _ = client.write_all(&[0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
            return Err(TransportError::Protocol(format!(
                "地址类型不认识：{other:#04x}"
            )));
        }
    };
    let mut port_raw = [0u8; 2];
    read_exact(&mut client, &mut port_raw)?;
    let port = u16::from_be_bytes(port_raw);

    // 现读一次句柄：设置页刚改的配置，对这条新连接立即生效。
    let upstream = upstream.current();
    let remote = match upstream.as_ref() {
        Some(upstream) if upstream.kind == ProxyKind::Https => Err(TransportError::Unsupported(
            "容器链路暂不支持 HTTPS 代理上游（两层 TLS 拆不出双向）".into(),
        )),
        Some(upstream) => proxy::connect_raw(
            upstream,
            &host,
            port,
            Duration::from_secs(15),
            Some(IDLE_TIMEOUT),
        ),
        None => {
            TcpStream::connect((host.as_str(), port)).map_err(|error| TransportError::Connect {
                host: format!("{host}:{port}"),
                message: error.to_string(),
            })
        }
    };
    let remote = match remote {
        Ok(remote) => remote,
        Err(error) => {
            // 失败原因写在日志里，客户端只拿到一个状态码（SOCKS5 的规矩）。
            let _ = client.write_all(&[0x05, 0x05, 0x00, 0x01, 0, 0, 0, 0, 0, 0]);
            return Err(error);
        }
    };
    let _ = remote.set_read_timeout(Some(IDLE_TIMEOUT));
    let _ = remote.set_write_timeout(Some(IDLE_TIMEOUT));

    client
        .write_all(&[0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0])
        .map_err(|error| TransportError::Io(format!("回 CONNECT: {error}")))?;

    pump(client, remote);
    Ok(())
}

/// 双向搬字节。两条线程各管一个方向，任一端 EOF 就结束。
fn pump(client: TcpStream, remote: TcpStream) {
    let client_read = match client.try_clone() {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let remote_write = match remote.try_clone() {
        Ok(stream) => stream,
        Err(_) => return,
    };
    let up = thread::spawn(move || {
        let mut from = client_read;
        let mut to = remote_write;
        let _ = std::io::copy(&mut from, &mut to);
        let _ = to.shutdown(std::net::Shutdown::Write);
    });
    let mut from = remote;
    let mut to = client;
    let _ = std::io::copy(&mut from, &mut to);
    let _ = to.shutdown(std::net::Shutdown::Write);
    let _ = up.join();
}

fn read_exact(stream: &mut TcpStream, buf: &mut [u8]) -> Result<(), TransportError> {
    stream
        .read_exact(buf)
        .map_err(|error| TransportError::Io(format!("读本地客户端: {error}")))
}

/// 给容器写的那份配置。**不写环境变量**：这是一份文件，程序不主动读就看不见。
pub fn config_text(server: &ProxyServer) -> String {
    let (user, pass) = server.credentials();
    format!(
        "# nmoi 本地代理端点（容器内用）。由 App 写入，停用时删除。\n\
         host=127.0.0.1\nport={}\nuser={user}\npass={pass}\n",
        server.addr.port()
    )
}

/// 把 SOCKS5 目标拼成一条 URL（上游是 HTTP 代理时要判断 scheme）。
pub fn tunnel_url(host: &str, port: u16) -> Url {
    Url {
        scheme: "https".into(),
        host: host.into(),
        port,
        path: "/".into(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::net::TcpListener;

    /// 一个回声服务器：收到什么回什么。
    fn echo_server() -> SocketAddr {
        let listener = TcpListener::bind((Ipv4Addr::LOCALHOST, 0)).unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            while let Ok((mut stream, _)) = listener.accept() {
                thread::spawn(move || {
                    let mut buf = [0u8; 1024];
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
                });
            }
        });
        addr
    }

    fn client_config(addr: SocketAddr, user: &str, pass: &str) -> proxy::ProxyConfig {
        proxy::ProxyConfig {
            kind: ProxyKind::Socks5,
            host: "127.0.0.1".into(),
            port: addr.port(),
            username: Some(user.into()),
            password: Some(pass.into()),
            bypass: Vec::new(),
            trust: proxy::ProxyTrust::Bundle,
        }
    }

    #[test]
    fn tunnels_bytes_to_the_target() {
        let echo = echo_server();
        let server = ProxyServer::start(ProxyHandle::empty(), "u".into(), "p".into()).unwrap();
        let config = client_config(server.addr(), "u", "p");
        let tunnel = proxy::connect(
            &config,
            &tunnel_url("127.0.0.1", echo.port()),
            Duration::from_secs(5),
            Some(Duration::from_secs(5)),
        )
        .unwrap_or_else(|error| {
            panic!(
                "本地端点应当接受连接：{error}（服务端最后一条错误：{:?}）",
                server.last_error()
            )
        });
        let mut stream = tunnel.stream;
        stream.write_all(b"ping").unwrap();
        let mut back = [0u8; 4];
        stream.read_exact(&mut back).unwrap();
        assert_eq!(&back, b"ping");
        server.stop();
    }

    #[test]
    fn rejects_a_wrong_password() {
        let echo = echo_server();
        let server = ProxyServer::start(ProxyHandle::empty(), "u".into(), "p".into()).unwrap();
        let config = client_config(server.addr(), "u", "wrong");
        let error = proxy::connect(
            &config,
            &tunnel_url("127.0.0.1", echo.port()),
            Duration::from_secs(5),
            Some(Duration::from_secs(5)),
        )
        .err()
        .expect("密码错应当被拒");
        assert!(error.to_string().contains("认证"), "{error}");
        server.stop();
    }

    #[test]
    fn refuses_an_https_upstream_with_a_named_reason() {
        let upstream = ProxyHandle::fixed(proxy::ProxyConfig {
            kind: ProxyKind::Https,
            host: "127.0.0.1".into(),
            port: 1,
            username: None,
            password: None,
            bypass: Vec::new(),
            trust: proxy::ProxyTrust::Bundle,
        });
        let error = ProxyServer::start(upstream, "u".into(), "p".into())
            .err()
            .expect("HTTPS 上游应当被拒");
        assert!(error.to_string().contains("HTTPS 代理上游"), "{error}");
    }

    #[test]
    fn requires_credentials_because_loopback_is_shared() {
        let error = ProxyServer::start(ProxyHandle::empty(), String::new(), "p".into())
            .err()
            .expect("空用户名应当被拒");
        assert!(error.to_string().contains("用户名/密码"), "{error}");
    }

    #[test]
    fn config_text_carries_the_port_and_credentials() {
        let server = ProxyServer::start(ProxyHandle::empty(), "u".into(), "p".into()).unwrap();
        let text = config_text(&server);
        assert!(
            text.contains(&format!("port={}", server.addr().port())),
            "{text}"
        );
        assert!(text.contains("user=u") && text.contains("pass=p"), "{text}");
        server.stop();
    }
}
