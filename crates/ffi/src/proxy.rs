//! 代理面的 C ABI：应用配置、起停容器用的本地端点。
//!
//! 三条设计：
//!
//! 1. **配置是热句柄**（`ProxyHandle`），`apply` 之后**下一次请求**就用新的；
//!    正在飞的那一条不变。这是用户 2026-11-02 要的"实时生效"。
//! 2. **本地端点与配置共用一个句柄**：改上游不用重启端点，新连接现读。
//! 3. **状态里不回密码**：只回 `has_password`。界面要显示的是"有没有设"，
//!    不是那串字符；要改密码就整个重填（跟 Vault 的口径一致）。

use std::ffi::c_char;
use std::sync::{Mutex, OnceLock};

use nmoi_core::proxy::{ProxyConfig, ProxyHandle, ProxyKind, ProxyTrust};
use nmoi_core::proxy_server::{self, ProxyServer};
use serde_json::{json, Value};

use crate::json::{read_str, run_json, FfiError};

static HANDLE: OnceLock<ProxyHandle> = OnceLock::new();
static SERVER: OnceLock<Mutex<Option<ProxyServer>>> = OnceLock::new();

/// 全局句柄。turn 那边每次建 client 都拿它，所以设置一改就生效。
pub(crate) fn handle() -> &'static ProxyHandle {
    HANDLE.get_or_init(ProxyHandle::empty)
}

fn server() -> &'static Mutex<Option<ProxyServer>> {
    SERVER.get_or_init(|| Mutex::new(None))
}

/// 这一轮要用的 HTTP 客户端：**每次都挂上当前的代理句柄**。
///
/// 为什么不是建一次留着：句柄里的配置会被 `apply` 换掉，而 `ChatClient` 是每轮新建的，
/// 拿同一份句柄就等于"下一轮自动用新配置"。真正需要共享的是句柄，不是 client。
pub(crate) fn client() -> nmoi_core::runtime::ChatClient<nmoi_core::transport::HttpsTransport> {
    let plain = nmoi_core::transport::PlainHttpTransport {
        proxy: handle().clone(),
        ..Default::default()
    };
    nmoi_core::runtime::ChatClient::new(nmoi_core::transport::HttpsTransport { plain })
}

/// 只拿传输层（不带 `ChatClient` 那套 JSON 语义）：`Speak` 要发的是二进制回包。
///
/// 与 [`client`] 共用同一个代理句柄——两处各建一份的话，设置页改完，
/// 对话走新代理、语音还走旧的，而且没人看得出为什么。
pub(crate) fn transport() -> nmoi_core::transport::HttpsTransport {
    nmoi_core::transport::HttpsTransport {
        plain: nmoi_core::transport::PlainHttpTransport {
            proxy: handle().clone(),
            ..Default::default()
        },
    }
}

fn parse_proxy(value: &Value) -> Result<Option<ProxyConfig>, FfiError> {
    if value.is_null() {
        return Ok(None);
    }
    let object = value
        .as_object()
        .ok_or_else(|| FfiError::new("bad_argument", "`proxy` 要么是 null，要么是一个对象"))?;

    let kind_text = object
        .get("kind")
        .and_then(Value::as_str)
        .ok_or_else(|| FfiError::new("bad_argument", "代理缺少 `kind`"))?;
    let kind = ProxyKind::parse(kind_text).ok_or_else(|| {
        FfiError::new(
            "bad_argument",
            format!("不认识的代理类型 `{kind_text}`；可选 socks5 / http / https"),
        )
    })?;

    let host = object
        .get("host")
        .and_then(Value::as_str)
        .map(str::trim)
        .filter(|host| !host.is_empty())
        .ok_or_else(|| FfiError::new("bad_argument", "代理缺少 `host`"))?
        .to_string();

    let port = object
        .get("port")
        .and_then(Value::as_u64)
        .filter(|port| (1..=65535).contains(port))
        .ok_or_else(|| FfiError::new("bad_argument", "代理的 `port` 要在 1–65535 之间"))?
        as u16;

    let trust = match object.get("trust").and_then(Value::as_str) {
        None | Some("bundle") => ProxyTrust::Bundle,
        Some("local") => ProxyTrust::Local,
        Some(other) => {
            return Err(FfiError::new(
                "bad_argument",
                format!("不认识的凭据来源 `{other}`；可选 bundle / local"),
            ))
        }
    };

    let bypass = match object.get("bypass") {
        None | Some(Value::Null) => Vec::new(),
        Some(Value::String(text)) => ProxyConfig::parse_bypass(text),
        Some(Value::Array(items)) => items
            .iter()
            .filter_map(Value::as_str)
            .flat_map(ProxyConfig::parse_bypass)
            .collect(),
        Some(_) => {
            return Err(FfiError::new(
                "bad_argument",
                "`bypass` 要么是一行字符串，要么是字符串数组",
            ))
        }
    };

    Ok(Some(ProxyConfig {
        kind,
        host,
        port,
        username: object
            .get("username")
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
            .map(str::to_string),
        password: object
            .get("password")
            .and_then(Value::as_str)
            .filter(|text| !text.is_empty())
            .map(str::to_string),
        bypass,
        trust,
    }))
}

fn proxy_json(config: &ProxyConfig) -> Value {
    json!({
        "kind": config.kind.as_str(),
        "host": config.host,
        "port": config.port,
        "username": config.username,
        "has_password": config.password.is_some(),
        "bypass": config.bypass,
        "trust": match config.trust {
            ProxyTrust::Bundle => "bundle",
            ProxyTrust::Local => "local",
        },
    })
}

/// 应用一份配置。`{"proxy": null}` = 关掉（之后的新请求直连）。
///
/// 入参：
/// ```json
/// {"proxy": {"kind":"socks5","host":"127.0.0.1","port":2080,
///             "username":"u","password":"p",
///             "bypass":"localhost,10.0.0.0/8","trust":"bundle"}}
/// ```
#[no_mangle]
pub extern "C" fn nmoi_proxy_apply_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let root: Value = serde_json::from_str(text)
            .map_err(|error| FfiError::new("bad_json", format!("配置不是合法 JSON: {error}")))?;
        let proxy = parse_proxy(root.get("proxy").unwrap_or(&Value::Null))?;
        handle().set(proxy.clone());
        Ok(json!({
            "applied": true,
            "proxy": proxy.as_ref().map(proxy_json),
        }))
    })
}

/// 现在生效的那一份（不回密码）。
#[no_mangle]
pub extern "C" fn nmoi_proxy_status_json() -> *mut c_char {
    run_json(|| {
        let current = handle().current();
        Ok(json!({
            "proxy": current.as_ref().map(proxy_json),
            "server": server()
                .lock()
                .unwrap_or_else(|poisoned| poisoned.into_inner())
                .as_ref()
                .map(|server| json!({
                    "addr": server.addr().to_string(),
                    "open_connections": server.open_connections(),
                })),
        }))
    })
}

/// 用**表单里这一份**配置探一次，不落库、不改全局状态。
///
/// 入参：`{"url":"https://example.com","proxy":{…}|null}`。
/// 出参：`{"ok":true,"status":200,"ms":312}` 或 `{"ok":false,"ms":800,"error":"连不上代理 …"}`。
///
/// 为什么单独一条而不是复用 `apply`：测试按钮要测的是"表单里这一份"，
/// 不能因为点一下测试就把全局代理换了。
#[no_mangle]
pub extern "C" fn nmoi_proxy_probe_json(config: *const c_char) -> *mut c_char {
    use std::time::{Duration, Instant};

    use nmoi_core::transport::{
        HttpRequest, HttpsTransport, Method, PlainHttpTransport, Transport,
    };

    run_json(|| {
        let text = read_str(config, "config")?;
        let root: Value = serde_json::from_str(text)
            .map_err(|error| FfiError::new("bad_json", format!("配置不是合法 JSON: {error}")))?;
        let url = root
            .get("url")
            .and_then(Value::as_str)
            .map(str::trim)
            .filter(|url| !url.is_empty())
            .ok_or_else(|| FfiError::new("bad_argument", "缺少 `url`"))?
            .to_string();
        let proxy = parse_proxy(root.get("proxy").unwrap_or(&Value::Null))?;
        let handle = match proxy {
            Some(config) => ProxyHandle::fixed(config),
            None => ProxyHandle::empty(),
        };
        let transport = HttpsTransport {
            plain: PlainHttpTransport {
                proxy: handle,
                ..Default::default()
            },
        };
        let started = Instant::now();
        let request = HttpRequest::new(Method::Get, url).with_read_timeout(Duration::from_secs(15));
        let elapsed = |started: Instant| started.elapsed().as_millis() as u64;
        match transport.execute(request) {
            Ok(response) => Ok(json!({
                "ok": true,
                "status": response.status,
                "ms": elapsed(started),
            })),
            Err(error) => Ok(json!({
                "ok": false,
                "ms": elapsed(started),
                "error": error.to_string(),
            })),
        }
    })
}

/// 起本地 SOCKS5 端点（给容器里的垫片用）。
///
/// 入参：`{"user":"…","pass":"…"}`——**必须给**，loopback 同设备别的 App 也连得到。
/// 出参里带 `config`，就是该写进容器 `/etc/nmoi-net.conf` 的那一段文本。
#[no_mangle]
pub extern "C" fn nmoi_proxy_server_start_json(config: *const c_char) -> *mut c_char {
    run_json(|| {
        let text = read_str(config, "config")?;
        let root: Value = serde_json::from_str(text)
            .map_err(|error| FfiError::new("bad_json", format!("配置不是合法 JSON: {error}")))?;
        let user = root
            .get("user")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();
        let pass = root
            .get("pass")
            .and_then(Value::as_str)
            .unwrap_or_default()
            .to_string();

        let mut slot = server()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        if slot.is_some() {
            return Err(FfiError::new(
                "already_running",
                "本地代理端点已经起着；先 stop 再 start",
            ));
        }
        let started = ProxyServer::start(handle().clone(), user, pass)
            .map_err(|error| FfiError::new("proxy_server", error.to_string()))?;
        let payload = json!({
            "addr": started.addr().to_string(),
            "config": proxy_server::config_text(&started),
        });
        *slot = Some(started);
        Ok(payload)
    })
}

/// 停本地端点。没起着也算成功（幂等）。
#[no_mangle]
pub extern "C" fn nmoi_proxy_server_stop_json() -> *mut c_char {
    run_json(|| {
        let mut slot = server()
            .lock()
            .unwrap_or_else(|poisoned| poisoned.into_inner());
        let stopped = slot.take().is_some();
        Ok(json!({ "stopped": stopped }))
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::ffi::CString;

    fn call(pointer: *mut c_char) -> Value {
        let text = unsafe { std::ffi::CStr::from_ptr(pointer) }
            .to_str()
            .unwrap()
            .to_string();
        unsafe { crate::nmoi_string_free(pointer) };
        serde_json::from_str(&text).unwrap()
    }

    fn apply(body: &str) -> Value {
        let text = CString::new(body).unwrap();
        call(nmoi_proxy_apply_json(text.as_ptr()))
    }

    #[test]
    fn apply_then_status_roundtrip_hides_the_password() {
        let applied = apply(
            r#"{"proxy":{"kind":"socks5","host":"127.0.0.1","port":2080,
                "username":"u","password":"p","bypass":"localhost,10.0.0.0/8"}}"#,
        );
        assert_eq!(applied["ok"], json!(true), "{applied}");
        assert_eq!(applied["proxy"]["has_password"], json!(true));

        let status = call(nmoi_proxy_status_json());
        assert_eq!(status["proxy"]["kind"], json!("socks5"));
        assert_eq!(status["proxy"]["port"], json!(2080));
        assert_eq!(status["proxy"]["bypass"].as_array().unwrap().len(), 2);
        assert!(
            status["proxy"].get("password").is_none(),
            "状态里不能回密码：{status}"
        );

        // 关掉
        let off = apply(r#"{"proxy":null}"#);
        assert_eq!(off["proxy"], Value::Null);
        assert_eq!(call(nmoi_proxy_status_json())["proxy"], Value::Null);
    }

    #[test]
    fn unknown_kind_names_the_options() {
        let error = apply(r#"{"proxy":{"kind":"socks4","host":"h","port":1}}"#);
        assert_eq!(error["ok"], json!(false));
        let message = error["error"]["message"].as_str().unwrap();
        assert!(message.contains("socks5"), "{message}");
    }

    #[test]
    fn bad_port_is_rejected() {
        let error = apply(r#"{"proxy":{"kind":"http","host":"h","port":0}}"#);
        assert_eq!(error["ok"], json!(false));
    }

    /// 一个只回 200 的本地 HTTP 服务器。
    fn spawn_ok_server() -> u16 {
        use std::io::{Read, Write};
        use std::net::TcpListener;
        let listener = TcpListener::bind("127.0.0.1:0").unwrap();
        let port = listener.local_addr().unwrap().port();
        std::thread::spawn(move || {
            while let Ok((mut stream, _)) = listener.accept() {
                let mut scratch = [0u8; 2048];
                let _ = stream.read(&mut scratch);
                let _ = stream.write_all(
                    b"HTTP/1.1 200 OK\r\ncontent-length: 2\r\nconnection: close\r\n\r\nok",
                );
                let _ = stream.flush();
            }
        });
        port
    }

    #[test]
    fn probe_reports_status_and_time() {
        let port = spawn_ok_server();
        let body = format!(r#"{{"url":"http://127.0.0.1:{port}/","proxy":null}}"#);
        let text = CString::new(body).unwrap();
        let result = call(nmoi_proxy_probe_json(text.as_ptr()));
        assert_eq!(result["ok"], json!(true), "{result}");
        assert_eq!(result["status"], json!(200));
        assert!(result["ms"].as_u64().is_some());
    }

    #[test]
    fn probe_failure_names_the_proxy() {
        let body = r#"{"url":"http://example.com/","proxy":
            {"kind":"socks5","host":"127.0.0.1","port":1}}"#;
        let text = CString::new(body).unwrap();
        let result = call(nmoi_proxy_probe_json(text.as_ptr()));
        assert_eq!(result["ok"], json!(false), "{result}");
        let error = result["error"].as_str().unwrap();
        assert!(error.contains("代理"), "错误要点名代理：{error}");
    }

    #[test]
    fn server_starts_and_stops_idempotently() {
        let _ = call(nmoi_proxy_server_stop_json());
        let text = CString::new(r#"{"user":"u","pass":"p"}"#).unwrap();
        let started = call(nmoi_proxy_server_start_json(text.as_ptr()));
        assert_eq!(started["ok"], json!(true), "{started}");
        assert!(started["addr"].as_str().unwrap().starts_with("127.0.0.1:"));
        assert!(started["config"].as_str().unwrap().contains("pass=p"));

        let again = call(nmoi_proxy_server_start_json(text.as_ptr()));
        assert_eq!(again["ok"], json!(false), "第二次 start 应当被拒");

        let stopped = call(nmoi_proxy_server_stop_json());
        assert_eq!(stopped["stopped"], json!(true));
        let stopped_again = call(nmoi_proxy_server_stop_json());
        assert_eq!(stopped_again["stopped"], json!(false));
    }
}
