//! 只在 unix 上编译：Windows 没有 `dlsym` / `sockaddr_un`，也没有 proot 容器。
#![cfg(unix)]
// MSRV 1.75 还没有 `c"…"` 字面量（1.77 才稳定），clippy 的建议这条先关掉。
#![allow(clippy::manual_c_str_literals)]

//! 容器内的网络垫片（0.15ar-e / D226）。
//!
//! 这个 `.so` 被写进容器的 `/etc/ld.so.preload`，拦两个符号：
//!
//! - `getaddrinfo`：照常问系统解析器，拿到**真的**地址，同时记下 `IP → 域名`；
//! - `connect`：目标 IP 在表里、且配置文件在 → 连本机端点、按**域名**走 SOCKS5；
//!   否则原样交给真的 `connect`。
//!
//! 三条硬规则（都踩过或想过）：
//!
//! 1. **loopback 永远直连**。不这么做，垫片会把它自己到端点的连接也拦下来，死循环。
//! 2. **不改写地址**。返回假 IP（198.18.x.x）在"关掉代理"之后会留下连不上的缓存，
//!    表现是"某些工具突然连不上，重启才好"。所以只记映射，地址原样。
//! 3. **配置读不到就完全透明**。文件不在 = 没开代理 = 一次系统调用都不该被改。
//!
//! 配置文件（App 写入，固定路径，**不用环境变量**——那是最好检测的痕迹）：
//!
//! ```text
//! host=127.0.0.1
//! port=43210
//! user=xxxx
//! pass=yyyy
//! ```
//!
//! 已知边界：静态链接的程序（Go / Rust 默认）不经过动态链接器，拦不到；
//! `dns.resolve` 这类自己发 DNS 的库绕过 `getaddrinfo`；只覆盖 TCP，UDP 不管。

use std::collections::HashMap;
use std::ffi::CStr;
use std::net::{IpAddr, Ipv4Addr, Ipv6Addr, SocketAddr};
use std::sync::{Mutex, OnceLock};
use std::time::SystemTime;

/// 容器里的固定路径。App 侧写、这里读；**不用环境变量**。
const CONFIG_PATH: &str = "/etc/nmoi-net.conf";

// ---------------------------------------------------------------------------
// 配置：按 mtime 缓存。开关一改，下一次 connect 就看见。
// ---------------------------------------------------------------------------

#[derive(Clone, Debug)]
struct Endpoint {
    addr: SocketAddr,
    user: String,
    pass: String,
}

/// `(文件的 mtime, 解析结果)`。mtime 没变就不重读文件。
type ConfigCache = Option<(Option<SystemTime>, Option<Endpoint>)>;

fn config_slot() -> &'static Mutex<ConfigCache> {
    static SLOT: OnceLock<Mutex<ConfigCache>> = OnceLock::new();
    SLOT.get_or_init(|| Mutex::new(None))
}

fn endpoint() -> Option<Endpoint> {
    let modified = std::fs::metadata(CONFIG_PATH)
        .and_then(|meta| meta.modified())
        .ok();
    let mut slot = config_slot()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    if let Some((cached_at, cached)) = slot.as_ref() {
        if *cached_at == modified {
            return cached.clone();
        }
    }
    let parsed = modified.and_then(|_| read_config());
    *slot = Some((modified, parsed.clone()));
    parsed
}

fn read_config() -> Option<Endpoint> {
    let text = std::fs::read_to_string(CONFIG_PATH).ok()?;
    let mut host = None;
    let mut port = None;
    let mut user = String::new();
    let mut pass = String::new();
    for line in text.lines() {
        let line = line.trim();
        if line.is_empty() || line.starts_with('#') {
            continue;
        }
        let Some((key, value)) = line.split_once('=') else {
            continue;
        };
        match key.trim() {
            "host" => host = Some(value.trim().to_string()),
            "port" => port = value.trim().parse::<u16>().ok(),
            "user" => user = value.trim().to_string(),
            "pass" => pass = value.trim().to_string(),
            _ => {}
        }
    }
    Some(Endpoint {
        addr: SocketAddr::new(host?.parse().ok()?, port?),
        user,
        pass,
    })
}

// ---------------------------------------------------------------------------
// IP → 域名。getaddrinfo 记，connect 查。
// ---------------------------------------------------------------------------

fn mapping() -> &'static Mutex<HashMap<IpAddr, String>> {
    static MAP: OnceLock<Mutex<HashMap<IpAddr, String>>> = OnceLock::new();
    MAP.get_or_init(|| Mutex::new(HashMap::new()))
}

fn remember(ip: IpAddr, host: String) {
    let mut map = mapping()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner());
    // 表不该无限长：一个容器里跑一天也就几百个域名。
    if map.len() > 4096 {
        map.clear();
    }
    map.insert(ip, host);
}

fn hostname_of(ip: IpAddr) -> Option<String> {
    mapping()
        .lock()
        .unwrap_or_else(|poisoned| poisoned.into_inner())
        .get(&ip)
        .cloned()
}

// ---------------------------------------------------------------------------
// 符号拦截
// ---------------------------------------------------------------------------

type GetAddrInfoFn = unsafe extern "C" fn(
    *const libc::c_char,
    *const libc::c_char,
    *const libc::addrinfo,
    *mut *mut libc::addrinfo,
) -> libc::c_int;

type ConnectFn =
    unsafe extern "C" fn(libc::c_int, *const libc::sockaddr, libc::socklen_t) -> libc::c_int;

fn real_getaddrinfo() -> Option<GetAddrInfoFn> {
    static REAL: OnceLock<Option<GetAddrInfoFn>> = OnceLock::new();
    *REAL.get_or_init(|| unsafe {
        let symbol = libc::dlsym(
            libc::RTLD_NEXT,
            b"getaddrinfo\0".as_ptr() as *const libc::c_char,
        );
        if symbol.is_null() {
            None
        } else {
            Some(std::mem::transmute::<*mut libc::c_void, GetAddrInfoFn>(
                symbol,
            ))
        }
    })
}

fn real_connect() -> Option<ConnectFn> {
    static REAL: OnceLock<Option<ConnectFn>> = OnceLock::new();
    *REAL.get_or_init(|| unsafe {
        let symbol = libc::dlsym(
            libc::RTLD_NEXT,
            b"connect\0".as_ptr() as *const libc::c_char,
        );
        if symbol.is_null() {
            None
        } else {
            Some(std::mem::transmute::<*mut libc::c_void, ConnectFn>(symbol))
        }
    })
}

/// 拦 `getaddrinfo`：结果原样返回，只顺手记下 `IP → 域名`。
///
/// # Safety
/// 与 libc 的 `getaddrinfo` 同签名；指针由调用方保证有效。
#[no_mangle]
pub unsafe extern "C" fn getaddrinfo(
    node: *const libc::c_char,
    service: *const libc::c_char,
    hints: *const libc::addrinfo,
    result: *mut *mut libc::addrinfo,
) -> libc::c_int {
    let Some(real) = real_getaddrinfo() else {
        return libc::EAI_SYSTEM;
    };
    let code = real(node, service, hints, result);
    if code != 0 || node.is_null() || result.is_null() {
        return code;
    }
    let host = match CStr::from_ptr(node).to_str() {
        Ok(text) => text.to_string(),
        Err(_) => return code,
    };
    // 记下这一次解析出来的每一个地址。
    let mut current = *result;
    while !current.is_null() {
        let info = &*current;
        if !info.ai_addr.is_null() {
            if let Some(ip) = sockaddr_ip(info.ai_addr) {
                remember(ip, host.clone());
            }
        }
        current = info.ai_next;
    }
    code
}

/// 拦 `connect`：命中映射且代理开着 → 走本地端点；否则原样直连。
///
/// # Safety
/// 与 libc 的 `connect` 同签名。
#[no_mangle]
pub unsafe extern "C" fn connect(
    fd: libc::c_int,
    address: *const libc::sockaddr,
    length: libc::socklen_t,
) -> libc::c_int {
    let Some(real) = real_connect() else {
        return -1;
    };
    let Some(ip) = sockaddr_ip(address) else {
        // AF_UNIX / 其它家族：不碰。
        return real(fd, address, length);
    };
    // 规则 1：loopback 永远直连（含端点自己）。
    if ip.is_loopback() {
        return real(fd, address, length);
    }
    let Some(endpoint) = endpoint() else {
        return real(fd, address, length);
    };
    let Some(host) = hostname_of(ip) else {
        return real(fd, address, length);
    };
    let port = sockaddr_port(address);

    match tunnel(fd, &endpoint, &host, port) {
        Ok(()) => 0,
        // 隧道失败就退回直连：垫片不该成为"断网"的原因。
        Err(_) => real(fd, address, length),
    }
}

/// 在已有的 fd 上完成 SOCKS5 握手（不新建 socket，`connect` 的语义就是"连这个 fd"）。
///
/// 全程用 `send`/`recv` 直接操作 fd：`TcpStream` 会接管所有权，而垫片绝不能关掉调用方的 fd。
fn tunnel(
    fd: libc::c_int,
    endpoint: &Endpoint,
    host: &str,
    port: u16,
) -> Result<(), std::io::Error> {
    set_timeout(fd, libc::SO_RCVTIMEO)?;
    set_timeout(fd, libc::SO_SNDTIMEO)?;

    // 1) 连端点。这个 fd 还没连过，所以这一步是真的 connect（走 real，不再进垫片）。
    let Some(real) = real_connect() else {
        return Err(std::io::Error::new(
            std::io::ErrorKind::Other,
            "拿不到 connect",
        ));
    };
    let (sockaddr, length) = to_sockaddr(endpoint.addr);
    let code = unsafe { real(fd, &sockaddr as *const _ as *const libc::sockaddr, length) };
    if code != 0 {
        return Err(std::io::Error::last_os_error());
    }

    // 2) 问候：只报用户名/密码一种方法。
    send_all(fd, &[0x05, 0x01, 0x02])?;
    let mut choice = [0u8; 2];
    recv_exact(fd, &mut choice)?;
    if choice != [0x05, 0x02] {
        return Err(std::io::Error::new(
            std::io::ErrorKind::Other,
            "端点不接受用户名/密码认证",
        ));
    }

    // 3) 认证
    let user = endpoint.user.as_bytes();
    let pass = endpoint.pass.as_bytes();
    if user.len() > 255 || pass.len() > 255 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::Other,
            "用户名/密码超过 255 字节",
        ));
    }
    let mut auth = vec![0x01u8, user.len() as u8];
    auth.extend_from_slice(user);
    auth.push(pass.len() as u8);
    auth.extend_from_slice(pass);
    send_all(fd, &auth)?;
    let mut reply = [0u8; 2];
    recv_exact(fd, &mut reply)?;
    if reply[1] != 0x00 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::PermissionDenied,
            "端点认证失败",
        ));
    }

    // 4) CONNECT：域名优先，让端点那边解析。
    if host.len() > 255 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::Other,
            "主机名超过 255 字节",
        ));
    }
    let mut request = vec![0x05u8, 0x01, 0x00, 0x03, host.len() as u8];
    request.extend_from_slice(host.as_bytes());
    request.extend_from_slice(&port.to_be_bytes());
    send_all(fd, &request)?;

    let mut head = [0u8; 4];
    recv_exact(fd, &mut head)?;
    if head[0] != 0x05 || head[1] != 0x00 {
        return Err(std::io::Error::new(
            std::io::ErrorKind::ConnectionRefused,
            "端点拒绝了 CONNECT",
        ));
    }
    // 把绑定地址读掉。
    let skip = match head[3] {
        0x01 => 4 + 2,
        0x04 => 16 + 2,
        0x03 => {
            let mut len = [0u8; 1];
            recv_exact(fd, &mut len)?;
            len[0] as usize + 2
        }
        _ => 0,
    };
    let mut discard = vec![0u8; skip];
    recv_exact(fd, &mut discard)?;
    Ok(())
}

fn set_timeout(fd: libc::c_int, option: libc::c_int) -> Result<(), std::io::Error> {
    let timeout = libc::timeval {
        tv_sec: 15,
        tv_usec: 0,
    };
    let code = unsafe {
        libc::setsockopt(
            fd,
            libc::SOL_SOCKET,
            option,
            &timeout as *const _ as *const libc::c_void,
            std::mem::size_of::<libc::timeval>() as libc::socklen_t,
        )
    };
    if code == 0 {
        Ok(())
    } else {
        Err(std::io::Error::last_os_error())
    }
}

fn send_all(fd: libc::c_int, mut bytes: &[u8]) -> Result<(), std::io::Error> {
    while !bytes.is_empty() {
        let sent = unsafe { libc::send(fd, bytes.as_ptr() as *const libc::c_void, bytes.len(), 0) };
        if sent <= 0 {
            return Err(std::io::Error::last_os_error());
        }
        bytes = &bytes[sent as usize..];
    }
    Ok(())
}

fn recv_exact(fd: libc::c_int, mut bytes: &mut [u8]) -> Result<(), std::io::Error> {
    while !bytes.is_empty() {
        let read =
            unsafe { libc::recv(fd, bytes.as_mut_ptr() as *mut libc::c_void, bytes.len(), 0) };
        if read <= 0 {
            return Err(std::io::Error::last_os_error());
        }
        let (_, rest) = bytes.split_at_mut(read as usize);
        bytes = rest;
    }
    Ok(())
}

fn to_sockaddr(address: SocketAddr) -> (libc::sockaddr_storage, libc::socklen_t) {
    let mut storage: libc::sockaddr_storage = unsafe { std::mem::zeroed() };
    match address {
        SocketAddr::V4(v4) => {
            let target = &mut storage as *mut _ as *mut libc::sockaddr_in;
            unsafe {
                (*target).sin_family = libc::AF_INET as libc::sa_family_t;
                (*target).sin_port = v4.port().to_be();
                (*target).sin_addr.s_addr = u32::from(*v4.ip()).to_be();
            }
            (
                storage,
                std::mem::size_of::<libc::sockaddr_in>() as libc::socklen_t,
            )
        }
        SocketAddr::V6(v6) => {
            let target = &mut storage as *mut _ as *mut libc::sockaddr_in6;
            unsafe {
                (*target).sin6_family = libc::AF_INET6 as libc::sa_family_t;
                (*target).sin6_port = v6.port().to_be();
                (*target).sin6_addr.s6_addr = v6.ip().octets();
            }
            (
                storage,
                std::mem::size_of::<libc::sockaddr_in6>() as libc::socklen_t,
            )
        }
    }
}

fn sockaddr_ip(address: *const libc::sockaddr) -> Option<IpAddr> {
    if address.is_null() {
        return None;
    }
    unsafe {
        match (*address).sa_family as libc::c_int {
            libc::AF_INET => {
                let v4 = &*(address as *const libc::sockaddr_in);
                Some(IpAddr::V4(Ipv4Addr::from(u32::from_be(v4.sin_addr.s_addr))))
            }
            libc::AF_INET6 => {
                let v6 = &*(address as *const libc::sockaddr_in6);
                Some(IpAddr::V6(Ipv6Addr::from(v6.sin6_addr.s6_addr)))
            }
            _ => None,
        }
    }
}

fn sockaddr_port(address: *const libc::sockaddr) -> u16 {
    unsafe {
        match (*address).sa_family as libc::c_int {
            libc::AF_INET => u16::from_be((*(address as *const libc::sockaddr_in)).sin_port),
            libc::AF_INET6 => u16::from_be((*(address as *const libc::sockaddr_in6)).sin6_port),
            _ => 0,
        }
    }
}
