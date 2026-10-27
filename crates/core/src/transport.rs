//! 传输层：core 与"怎么把字节发出去"之间的那道缝（P2 运行时地基）。
//!
//! 这里**只定形状**：一个请求、一个响应、响应体必须能逐行读（SSE 帧靠它）。
//! 真正建连接的是实现层。本文件自带一个实现 [`PlainHttpTransport`]：std 手写的明文
//! HTTP/1.1，覆盖 `http://`（本机 llama.cpp / ollama / 本机代理这条路）。
//!
//! `https://` 不在这里——TLS 要么落在平台侧原生栈（Android OkHttp / Windows WinHTTP /
//! Linux），要么落在某个 TLS 依赖上。取舍与理由见 `docs/decisions.md` D38。
//!
//! 响应体一律是 [`ResponseBody`]（`BufRead`）：**流式必须能读一半就走**，
//! 所以不提供 `execute` 的"一次性返回整个 body"变体——那会把 SSE 逼成等待完整响应。

use std::fmt;
use std::io::{self, BufRead, BufReader, Read, Write};
use std::net::{TcpStream, ToSocketAddrs};
use std::time::Duration;

/// 一个待发出的请求。body 已序列化成字节，这里不关心它是不是 JSON。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HttpRequest {
    pub method: Method,
    pub url: String,
    pub headers: Vec<(String, String)>,
    pub body: Vec<u8>,
    /// 单次读超时（毫秒）。`None` 用传输实现的默认值。
    ///
    /// 是**每次读**的超时，不是整个请求的超时：SSE 会在两次 token 之间静默很久，
    /// 整体上限会把正常的慢回复切掉。要卡整体时长由上层自己计时。
    pub read_timeout_ms: Option<u64>,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Method {
    Get,
    Post,
}

impl Method {
    pub fn as_str(self) -> &'static str {
        match self {
            Method::Get => "GET",
            Method::Post => "POST",
        }
    }
}

impl HttpRequest {
    pub fn new(method: Method, url: impl Into<String>) -> Self {
        Self {
            method,
            url: url.into(),
            headers: Vec::new(),
            body: Vec::new(),
            read_timeout_ms: None,
        }
    }

    pub fn post(url: impl Into<String>) -> Self {
        Self::new(Method::Post, url)
    }

    /// POST 一段已经序列化好的 JSON。
    pub fn post_json(url: impl Into<String>, body: impl Into<Vec<u8>>) -> Self {
        let mut request = Self::post(url);
        request.body = body.into();
        request
            .headers
            .push(("content-type".into(), "application/json".into()));
        request
    }

    pub fn header(mut self, name: impl Into<String>, value: impl Into<String>) -> Self {
        self.headers.push((name.into(), value.into()));
        self
    }

    pub fn with_read_timeout(mut self, timeout: Duration) -> Self {
        self.read_timeout_ms = Some(timeout.as_millis() as u64);
        self
    }
}

/// 响应体。要求是 `BufRead`：SSE 的帧边界是换行，逐行读才不会把一个帧切两半。
pub trait ResponseBody: BufRead + Send {}
impl<T: BufRead + Send> ResponseBody for T {}

pub struct HttpResponse {
    pub status: u16,
    pub headers: Vec<(String, String)>,
    pub body: Box<dyn ResponseBody>,
}

impl fmt::Debug for HttpResponse {
    fn fmt(&self, f: &mut fmt::Formatter<'_>) -> fmt::Result {
        f.debug_struct("HttpResponse")
            .field("status", &self.status)
            .field("headers", &self.headers)
            .field("body", &"<stream>")
            .finish()
    }
}

impl HttpResponse {
    /// 头部大小写不敏感查找；重名时取第一个。
    pub fn header(&self, name: &str) -> Option<&str> {
        self.headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    }

    pub fn is_success(&self) -> bool {
        (200..300).contains(&self.status)
    }

    /// 把剩余响应体读成一个字符串（错误体、非流式响应用它）。
    ///
    /// 上限 8 MiB：真机上非流式回复远小于它，而"服务端吐了 10 GB"不该把我们拖死。
    pub fn read_to_string(&mut self) -> Result<String, TransportError> {
        self.read_to_string_limit(8 * 1024 * 1024)
    }

    pub fn read_to_string_limit(&mut self, limit: usize) -> Result<String, TransportError> {
        let mut buffer = Vec::new();
        let outcome = self
            .body
            .by_ref()
            .take(limit as u64)
            .read_to_end(&mut buffer);
        let text = String::from_utf8_lossy(&buffer).into_owned();
        if let Err(error) = outcome {
            // 读失败时**不丢**已读到的部分：错误体正是最想看的诊断线索。
            let kind = match error.kind() {
                io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => "读响应体超时",
                io::ErrorKind::UnexpectedEof => "响应体不完整",
                _ => "读响应体失败",
            };
            return Err(TransportError::Io(format!(
                "{kind}: {error}（已读到 {} 字节: {}）",
                text.len(),
                truncate_for_log(&text)
            )));
        }
        Ok(text)
    }
}

fn truncate_for_log(text: &str) -> String {
    const LIMIT: usize = 400;
    if text.chars().count() <= LIMIT {
        return text.to_string();
    }
    let head: String = text.chars().take(LIMIT).collect();
    format!("{head}…")
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum TransportError {
    #[error("URL 无法解析: {0}")]
    InvalidUrl(String),
    /// 实现层不支持这个协议 / 特性（例如明文传输遇到 `https`）。
    #[error("传输层不支持: {0}")]
    Unsupported(String),
    #[error("连接失败 {host}: {message}")]
    Connect { host: String, message: String },
    #[error("超时: {0}")]
    Timeout(String),
    #[error("I/O 错误: {0}")]
    Io(String),
    /// 连上了，但说的不是 HTTP/1.1，或者帧格式坏了。
    #[error("协议错误: {0}")]
    Protocol(String),
}

impl TransportError {
    /// 这一条值不值得重发。语义错误（400/401）不走这里——它们不是传输失败。
    pub fn is_retryable(&self) -> bool {
        matches!(
            self,
            TransportError::Connect { .. } | TransportError::Timeout(_)
        )
    }
}

/// 传输实现。`Send + Sync` 是为了 UI 侧能把它放进共享状态里。
pub trait Transport: Send + Sync {
    fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError>;
}

/// 明文 HTTP/1.1。**只支持 `http://`**，遇到 `https://` 明确报 [`TransportError::Unsupported`]，
/// 不静默降级、不假装成功。
#[derive(Debug, Clone)]
pub struct PlainHttpTransport {
    pub connect_timeout: Duration,
    /// 默认读超时：每次 `read` 的上限，不是整轮上限。
    pub read_timeout: Duration,
    pub user_agent: String,
}

impl Default for PlainHttpTransport {
    fn default() -> Self {
        Self {
            connect_timeout: Duration::from_secs(15),
            read_timeout: Duration::from_secs(120),
            user_agent: format!("{}/{}", crate::PRODUCT_NAME, crate::VERSION),
        }
    }
}

impl PlainHttpTransport {
    pub fn new() -> Self {
        Self::default()
    }

    pub fn with_connect_timeout(mut self, timeout: Duration) -> Self {
        self.connect_timeout = timeout;
        self
    }

    pub fn with_read_timeout(mut self, timeout: Duration) -> Self {
        self.read_timeout = timeout;
        self
    }
}

impl Transport for PlainHttpTransport {
    fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
        let target = Url::parse(&request.url)?;

        // 先把请求头校验完再开 socket：一个头部拼错的请求不该有机会去连别人的机器，
        // 而且校验先跑，失败原因就是"头部非法"而不是"连不上"（后者会让人查错方向）。
        let head = build_head(&request, &target, &self.user_agent)?;

        let mut stream = connect(&target, self.connect_timeout)?;
        apply_read_timeout(&stream, request.read_timeout_ms, self.read_timeout)?;

        stream
            .write_all(head.as_bytes())
            .and_then(|_| stream.write_all(&request.body))
            .and_then(|_| stream.flush())
            .map_err(|error| map_io_error(error, "发送请求"))?;

        let reader = BufReader::with_capacity(16 * 1024, stream);
        read_response(reader)
    }
}
/// 一次请求要连的地址。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Url {
    pub scheme: String,
    pub host: String,
    pub port: u16,
    /// 含查询串，以 `/` 开头（空路径补 `/`）。
    pub path: String,
}

impl Url {
    /// 只解析 HTTP 客户端需要的部分。没有用户信息、没有 fragment、没有百分号解码——
    /// 这三样在 LLM 端点上都不出现，装成支持它们反而会掩盖拼错的 URL。
    pub fn parse(raw: &str) -> Result<Self, TransportError> {
        let raw = raw.trim();
        let (scheme, rest) = raw
            .split_once("://")
            .ok_or_else(|| TransportError::InvalidUrl(format!("缺少协议头: {raw}")))?;
        let scheme = scheme.to_ascii_lowercase();
        if scheme.is_empty() {
            return Err(TransportError::InvalidUrl(format!("协议名为空: {raw}")));
        }

        let (authority, path) = match rest.find('/') {
            Some(index) => (&rest[..index], rest[index..].to_string()),
            None => (rest, "/".to_string()),
        };
        if authority.is_empty() {
            return Err(TransportError::InvalidUrl(format!("缺少主机名: {raw}")));
        }

        // IPv6 字面量：[::1]:8080
        let (host, port_text) = if let Some(rest) = authority.strip_prefix('[') {
            let end = rest
                .find(']')
                .ok_or_else(|| TransportError::InvalidUrl(format!("IPv6 字面量没有收尾: {raw}")))?;
            let host = &rest[..end];
            let tail = &rest[end + 1..];
            let port = tail.strip_prefix(':');
            (host.to_string(), port)
        } else {
            match authority.rsplit_once(':') {
                Some((host, port)) => (host.to_string(), Some(port)),
                None => (authority.to_string(), None),
            }
        };

        if host.is_empty() {
            return Err(TransportError::InvalidUrl(format!("主机名为空: {raw}")));
        }

        let port = match port_text {
            Some(text) => text
                .parse::<u16>()
                .map_err(|_| TransportError::InvalidUrl(format!("端口不是数字: {text}")))?,
            None => default_port(&scheme)
                .ok_or_else(|| TransportError::Unsupported(format!("不认识的协议: {scheme}")))?,
        };

        Ok(Self {
            scheme,
            host,
            port,
            path,
        })
    }
}

fn default_port(scheme: &str) -> Option<u16> {
    match scheme {
        "http" => Some(80),
        "https" => Some(443),
        _ => None,
    }
}

fn connect(target: &Url, connect_timeout: Duration) -> Result<TcpStream, TransportError> {
    if target.scheme != "http" {
        return Err(TransportError::Unsupported(format!(
            "明文传输只支持 http://，收到 {}://（https 要用 `HttpsTransport`，见 D38 / D161）",
            target.scheme
        )));
    }
    connect_tcp(target, connect_timeout)
}

/// 只负责"连上去"，不看协议。TLS 那条路也用它。
fn connect_tcp(target: &Url, connect_timeout: Duration) -> Result<TcpStream, TransportError> {
    let addresses = (target.host.as_str(), target.port)
        .to_socket_addrs()
        .map_err(|error| TransportError::Connect {
            host: target.host.clone(),
            message: format!("域名解析失败: {error}"),
        })?;

    let mut last_error = None;
    for address in addresses {
        match TcpStream::connect_timeout(&address, connect_timeout) {
            Ok(stream) => {
                let _ = stream.set_nodelay(true);
                return Ok(stream);
            }
            Err(error) => last_error = Some(error),
        }
    }

    Err(TransportError::Connect {
        host: format!("{}:{}", target.host, target.port),
        message: match last_error {
            Some(error) => error.to_string(),
            None => "没有可用的地址".to_string(),
        },
    })
}

fn apply_read_timeout(
    stream: &TcpStream,
    requested_ms: Option<u64>,
    fallback: Duration,
) -> Result<(), TransportError> {
    let timeout = match requested_ms {
        Some(0) => None,
        Some(ms) => Some(Duration::from_millis(ms)),
        None => Some(fallback),
    };
    stream
        .set_read_timeout(timeout)
        .map_err(|error| TransportError::Io(format!("设置读超时失败: {error}")))
}

fn build_head(
    request: &HttpRequest,
    target: &Url,
    user_agent: &str,
) -> Result<String, TransportError> {
    let mut head = String::with_capacity(256);
    head.push_str(request.method.as_str());
    head.push(' ');
    head.push_str(&target.path);
    head.push_str(" HTTP/1.1\r\n");

    head.push_str("host: ");
    head.push_str(&authority_for_host_header(target));
    head.push_str("\r\n");

    // 连接不复用：省掉 keep-alive 的状态机，也省掉"半关闭的旧连接"这类难查的问题。
    // 每轮对话一次请求，复用带来的收益本来就接近零。
    let mut merged: Vec<(String, String)> = Vec::new();
    for (name, value) in &request.headers {
        check_header(name, value)?;
        // 同名后者覆盖前者：`post_json(..).header("content-type", ..)` 里用户写的那份要赢。
        match merged
            .iter_mut()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
        {
            Some(slot) => slot.1 = value.clone(),
            None => merged.push((name.clone(), value.clone())),
        }
    }

    if !has_header(&merged, "connection") {
        merged.push(("connection".into(), "close".into()));
    }
    if !has_header(&merged, "user-agent") {
        merged.push(("user-agent".into(), user_agent.to_string()));
    }
    if !has_header(&merged, "content-length") && !request.body.is_empty() {
        merged.push(("content-length".into(), request.body.len().to_string()));
    }

    for (name, value) in &merged {
        head.push_str(name);
        head.push_str(": ");
        head.push_str(value);
        head.push_str("\r\n");
    }
    head.push_str("\r\n");
    Ok(head)
}

fn authority_for_host_header(target: &Url) -> String {
    // IPv6 字面量在 Host 头里必须带方括号，否则 `::1:8080` 会被当成"host 是 ::1:8080"
    let host = if target.host.contains(':') {
        format!("[{}]", target.host)
    } else {
        target.host.clone()
    };
    if default_port(&target.scheme) == Some(target.port) {
        host
    } else {
        format!("{host}:{}", target.port)
    }
}

/// 头里的换行 = 请求头注入。宁可报错也不要发出去。
fn check_header(name: &str, value: &str) -> Result<(), TransportError> {
    if name.is_empty()
        || name.contains(['\r', '\n', ':'])
        || value.contains(['\r', '\n'])
        || name.trim() != name
    {
        return Err(TransportError::Protocol(format!(
            "头部非法（可能是注入）: {name:?}: {value:?}"
        )));
    }
    Ok(())
}

/// 头部里有没有这一项（大小写不敏感）。
fn has_header(headers: &[(String, String)], name: &str) -> bool {
    headers
        .iter()
        .any(|(key, _)| key.eq_ignore_ascii_case(name))
}

fn read_response<R: Read + Send + 'static>(
    mut reader: BufReader<R>,
) -> Result<HttpResponse, TransportError> {
    // 1xx 是过渡响应：读完接着读真正的那个（网关发 100 Continue 时会碰到）。
    loop {
        let (status, _reason) = read_status_line(&mut reader)?;
        let headers = read_headers(&mut reader)?;
        if (100..200).contains(&status) {
            continue;
        }
        // 把 reader 的所有权交给响应体：头部之后可能已经预读进缓冲区的字节必须留在
        // 同一个 reader 里。复制一份 BufReader 会凭空多一个 fd 和一份缓冲。
        let body = boxed_body(reader, status, &headers)?;
        return Ok(HttpResponse {
            status,
            headers,
            body,
        });
    }
}

fn read_line<R: Read>(reader: &mut BufReader<R>) -> Result<String, TransportError> {
    let mut line = Vec::new();
    let read = reader
        .read_until(b'\n', &mut line)
        .map_err(|error| map_io_error(error, "读响应"))?;
    if read == 0 {
        return Err(TransportError::Protocol("响应提前结束".into()));
    }
    if line.len() > 64 * 1024 {
        return Err(TransportError::Protocol(format!(
            "单行超过 64 KiB（{} 字节）",
            line.len()
        )));
    }
    Ok(String::from_utf8_lossy(&line)
        .trim_end_matches(['\n', '\r'])
        .to_string())
}

fn read_status_line<R: Read>(reader: &mut BufReader<R>) -> Result<(u16, String), TransportError> {
    let line = read_line(reader)?;
    let mut parts = line.splitn(3, ' ');
    let version = parts.next().unwrap_or_default();
    if !version.starts_with("HTTP/") {
        return Err(TransportError::Protocol(format!(
            "状态行不像 HTTP: {}",
            truncate_for_log(&line)
        )));
    }
    let code = parts
        .next()
        .and_then(|text| text.parse::<u16>().ok())
        .ok_or_else(|| TransportError::Protocol(format!("状态码读不出: {line}")))?;
    Ok((code, parts.next().unwrap_or_default().to_string()))
}

fn read_headers<R: Read>(
    reader: &mut BufReader<R>,
) -> Result<Vec<(String, String)>, TransportError> {
    let mut headers = Vec::new();
    let mut total = 0usize;
    loop {
        let line = read_line(reader)?;
        if line.is_empty() {
            return Ok(headers);
        }
        total += line.len();
        if total > 128 * 1024 {
            return Err(TransportError::Protocol("响应头超过 128 KiB".into()));
        }
        // 折行续行（obs-fold）在 HTTP/1.1 里是废弃语法，但仍有服务端发它。
        if line.starts_with([' ', '\t']) {
            if let Some(last) = headers.last_mut() {
                let (_, value): &mut (String, String) = last;
                value.push(' ');
                value.push_str(line.trim());
                continue;
            }
            return Err(TransportError::Protocol("头部第一行就是续行".into()));
        }
        let (name, value) = line
            .split_once(':')
            .ok_or_else(|| TransportError::Protocol(format!("头部没有冒号: {line}")))?;
        headers.push((name.trim().to_string(), value.trim().to_string()));
    }
}

fn boxed_body<R: Read + Send + 'static>(
    reader: BufReader<R>,
    status: u16,
    headers: &[(String, String)],
) -> Result<Box<dyn ResponseBody>, TransportError> {
    let find = |name: &str| -> Option<&str> {
        headers
            .iter()
            .find(|(key, _)| key.eq_ignore_ascii_case(name))
            .map(|(_, value)| value.as_str())
    };

    // 204/304 明确没有响应体；1xx 已经在上层吃掉。
    if status == 204 || status == 304 {
        return Ok(Box::new(io::Cursor::new(Vec::new())));
    }

    if let Some(encoding) = find("transfer-encoding") {
        if encoding.to_ascii_lowercase().contains("chunked") {
            // 外面再套一层 BufReader：本模块的读法是逐行（SSE 帧边界是换行），
            // 而 ChunkedBody 只实现 Read。套一层比在两种定界里各写一遍 fill_buf 靠谱。
            return Ok(Box::new(BufReader::new(ChunkedBody::new(reader))));
        }
        return Err(TransportError::Unsupported(format!(
            "不认识的 transfer-encoding: {encoding}"
        )));
    }

    if let Some(length) = find("content-length") {
        let length = length
            .trim()
            .parse::<u64>()
            .map_err(|_| TransportError::Protocol(format!("content-length 不是数字: {length}")))?;
        return Ok(Box::new(BufReader::new(LimitedBody::new(reader, length))));
    }

    // 靠关闭连接定界：SSE 的常见形态。
    Ok(Box::new(reader))
}

/// 定长响应体。服务器少发字节是协议错误，不是"读完了"。
struct LimitedBody<R: Read> {
    inner: R,
    remaining: u64,
}

impl<R: Read> LimitedBody<R> {
    fn new(inner: R, remaining: u64) -> Self {
        Self { inner, remaining }
    }
}

impl<R: Read> Read for LimitedBody<R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.remaining == 0 {
            return Ok(0);
        }
        let want = buffer.len().min(self.remaining as usize);
        let read = self.inner.read(&mut buffer[..want])?;
        if read == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                format!("响应体还差 {} 字节就断了", self.remaining),
            ));
        }
        self.remaining -= read as u64;
        Ok(read)
    }
}

/// 分块响应体。这是真机上最常见的形态：每个 SSE 帧可能横跨两个 chunk。
struct ChunkedBody<R: BufRead> {
    inner: R,
    remaining: u64,
    expect_crlf: bool,
    finished: bool,
}

impl<R: BufRead> ChunkedBody<R> {
    fn new(inner: R) -> Self {
        Self {
            inner,
            remaining: 0,
            expect_crlf: false,
            finished: false,
        }
    }

    fn read_size_line(&mut self) -> io::Result<u64> {
        loop {
            let mut line = Vec::new();
            let read = self.inner.read_until(b'\n', &mut line)?;
            if read == 0 {
                return Err(io::Error::new(
                    io::ErrorKind::UnexpectedEof,
                    "分块流在块大小处结束",
                ));
            }
            let text = String::from_utf8_lossy(&line);
            let text = text.trim_end_matches(['\n', '\r']);
            if text.is_empty() {
                continue; // 容错：有些实现会多发一个空行
            }
            let size_text = text.split(';').next().unwrap_or_default().trim();
            return u64::from_str_radix(size_text, 16).map_err(|_| {
                io::Error::new(
                    io::ErrorKind::InvalidData,
                    format!("块大小不是十六进制: {size_text}"),
                )
            });
        }
    }

    /// 读完尾部的 trailer 头，直到空行。
    fn drain_trailers(&mut self) -> io::Result<()> {
        loop {
            let mut line = Vec::new();
            let read = self.inner.read_until(b'\n', &mut line)?;
            if read == 0 {
                return Ok(());
            }
            if line.iter().all(|byte| matches!(byte, b'\r' | b'\n')) {
                return Ok(());
            }
        }
    }
}

impl<R: BufRead> Read for ChunkedBody<R> {
    fn read(&mut self, buffer: &mut [u8]) -> io::Result<usize> {
        if self.finished || buffer.is_empty() {
            return Ok(0);
        }

        if self.expect_crlf {
            let mut crlf = [0u8; 2];
            self.inner.read_exact(&mut crlf)?;
            if crlf != *b"\r\n" {
                return Err(io::Error::new(
                    io::ErrorKind::InvalidData,
                    "块数据之后不是 CRLF",
                ));
            }
            self.expect_crlf = false;
        }

        if self.remaining == 0 {
            let size = self.read_size_line()?;
            if size == 0 {
                self.drain_trailers()?;
                self.finished = true;
                return Ok(0);
            }
            self.remaining = size;
        }

        let want = buffer.len().min(self.remaining as usize);
        let read = self.inner.read(&mut buffer[..want])?;
        if read == 0 {
            return Err(io::Error::new(
                io::ErrorKind::UnexpectedEof,
                "分块流在数据中途结束",
            ));
        }
        self.remaining -= read as u64;
        if self.remaining == 0 {
            self.expect_crlf = true;
        }
        Ok(read)
    }
}

fn map_io_error(error: io::Error, what: &str) -> TransportError {
    match error.kind() {
        io::ErrorKind::TimedOut | io::ErrorKind::WouldBlock => {
            TransportError::Timeout(format!("{what}: {error}"))
        }
        _ => TransportError::Io(format!("{what}: {error}")),
    }
}

/// `http://` 交给明文实现，`https://` 走 rustls（D161）。
///
/// 为什么 TLS 落在 Rust 侧而不是平台侧：D38 的原话是"TLS 由平台侧传输实现"，
/// 但平台侧实现意味着 Rust 要往 JVM 回调（AttachCurrentThread + 逐块搬 body），
/// 那条路的出错面比 rustls 大得多，而且 Linux/Windows 还得各写一份。
/// 这里用一个**跨平台都能跑**的实现把这句话兑现：证书用内置根（webpki-roots），
/// 不依赖系统证书库，所以行为在三端一致。
#[cfg(feature = "tls")]
#[derive(Debug, Clone, Default)]
pub struct HttpsTransport {
    pub plain: PlainHttpTransport,
}

#[cfg(feature = "tls")]
impl HttpsTransport {
    pub fn new() -> Self {
        Self::default()
    }

    /// 内置根证书。`true` = 用 webpki-roots 里那一份 Mozilla 根集合。
    fn tls_config() -> std::sync::Arc<rustls::ClientConfig> {
        use std::sync::Arc;
        static CONFIG: std::sync::OnceLock<Arc<rustls::ClientConfig>> = std::sync::OnceLock::new();
        CONFIG
            .get_or_init(|| {
                let provider = Arc::new(rustls::crypto::ring::default_provider());
                let mut roots = rustls::RootCertStore::empty();
                roots.extend(webpki_roots::TLS_SERVER_ROOTS.iter().cloned());
                let config = rustls::ClientConfig::builder_with_provider(provider)
                    .with_safe_default_protocol_versions()
                    .expect("rustls 默认协议版本")
                    .with_root_certificates(roots)
                    .with_no_client_auth();
                Arc::new(config)
            })
            .clone()
    }
}

#[cfg(feature = "tls")]
impl Transport for HttpsTransport {
    fn execute(&self, request: HttpRequest) -> Result<HttpResponse, TransportError> {
        let target = Url::parse(&request.url)?;
        if target.scheme != "https" {
            // http 原样交给明文实现：两条路共用同一份头部构造与响应解析。
            return self.plain.execute(request);
        }

        let head = build_head(&request, &target, &self.plain.user_agent)?;
        let stream = connect_tcp(&target, self.plain.connect_timeout)?;
        apply_read_timeout(&stream, request.read_timeout_ms, self.plain.read_timeout)?;

        let server_name =
            rustls::pki_types::ServerName::try_from(target.host.clone()).map_err(|error| {
                TransportError::Connect {
                    host: target.host.clone(),
                    message: format!("主机名不能用作 TLS 名称: {error}"),
                }
            })?;
        let connection =
            rustls::ClientConnection::new(Self::tls_config(), server_name).map_err(|error| {
                TransportError::Connect {
                    host: target.host.clone(),
                    message: format!("TLS 初始化失败: {error}"),
                }
            })?;
        let mut tls = rustls::StreamOwned::new(connection, stream);

        tls.write_all(head.as_bytes())
            .and_then(|_| tls.write_all(&request.body))
            .and_then(|_| tls.flush())
            .map_err(|error| map_io_error(error, "发送请求"))?;

        read_response(BufReader::with_capacity(16 * 1024, tls))
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::net::SocketAddr;
    use std::thread;

    /// 起一个只服务一次连接的小服务器：把 `raw` 原样写出去，然后关掉。
    /// 用 127.0.0.1 的真实 socket，不 mock —— 这样 chunked / 定长 / 关闭定界
    /// 三种帧格式都是真跑出来的，不是我们照着实现对出来的。
    fn spawn_server(raw: &'static str) -> SocketAddr {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                // 先把请求读完再回，否则客户端可能拿到 RST。
                let mut scratch = [0u8; 4096];
                let _ = stream.read(&mut scratch);
                let _ = stream.write_all(raw.as_bytes());
                let _ = stream.flush();
            }
        });
        addr
    }

    /// 会把响应切碎发的服务器：模拟"一个 SSE 帧横跨多个 TCP 段 / 多个 chunk"。
    fn spawn_chopped_server(parts: Vec<String>) -> SocketAddr {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let addr = listener.local_addr().unwrap();
        thread::spawn(move || {
            if let Ok((mut stream, _)) = listener.accept() {
                let mut scratch = [0u8; 4096];
                let _ = stream.read(&mut scratch);
                for part in parts {
                    let _ = stream.write_all(part.as_bytes());
                    let _ = stream.flush();
                    thread::sleep(Duration::from_millis(5));
                }
            }
        });
        addr
    }

    fn get(addr: SocketAddr, path: &str) -> HttpRequest {
        HttpRequest::new(Method::Get, format!("http://{addr}{path}"))
    }

    #[test]
    fn content_length_body_is_bounded() {
        let addr = spawn_server(
            "HTTP/1.1 200 OK\r\ncontent-type: application/json\r\ncontent-length: 11\r\n\r\nhello world",
        );
        let transport = PlainHttpTransport::default();
        let mut response = transport.execute(get(addr, "/x")).unwrap();
        assert_eq!(response.status, 200);
        assert_eq!(response.header("Content-Type"), Some("application/json"));
        assert_eq!(response.read_to_string().unwrap(), "hello world");
    }

    #[test]
    fn chunked_body_reassembles_across_chunks() {
        // 两个 chunk，第一个故意把 "data: {" 切开
        let addr = spawn_server(
            "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n4\r\ndata\r\n9\r\n: {\"a\":1}\r\n0\r\n\r\n",
        );
        let transport = PlainHttpTransport::default();
        let mut response = transport.execute(get(addr, "/x")).unwrap();
        assert_eq!(response.read_to_string().unwrap(), "data: {\"a\":1}");
    }

    #[test]
    fn chunked_frames_survive_being_split_mid_line() {
        // 一个完整 SSE 帧被拆成 5 段发出去，中间还夹着 chunk 边界
        let frame = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\n\n";
        let mut parts = Vec::new();
        for piece in [
            "data:",
            " {\"cho",
            "ices\":[{\"delta\"",
            ":{\"content\":\"你好\"}}]}",
            "\n\n",
        ] {
            parts.push(format!("{:x}\r\n{}\r\n", piece.len(), piece));
        }
        parts.push("0\r\n\r\n".to_string());
        parts.insert(
            0,
            "HTTP/1.1 200 OK\r\ntransfer-encoding: chunked\r\n\r\n".to_string(),
        );

        let addr = spawn_chopped_server(parts);
        let transport = PlainHttpTransport::default();
        let mut response = transport.execute(get(addr, "/x")).unwrap();
        assert_eq!(response.read_to_string().unwrap(), frame);
    }

    #[test]
    fn close_delimited_body_reads_until_eof() {
        let addr = spawn_server("HTTP/1.1 200 OK\r\n\r\ndata: one\n\ndata: two\n\n");
        let transport = PlainHttpTransport::default();
        let mut response = transport.execute(get(addr, "/x")).unwrap();
        assert!(response.header("content-length").is_none());
        assert_eq!(
            response.read_to_string().unwrap(),
            "data: one\n\ndata: two\n\n"
        );
    }

    #[test]
    fn http_1_1_keeps_reason_phrase_out_of_status() {
        let addr = spawn_server("HTTP/1.1 429 Too Many Requests\r\ncontent-length: 0\r\n\r\n");
        let transport = PlainHttpTransport::default();
        let response = transport.execute(get(addr, "/x")).unwrap();
        assert_eq!(response.status, 429);
        assert!(!response.is_success());
    }

    #[test]
    fn informational_response_is_skipped() {
        let addr = spawn_server(
            "HTTP/1.1 100 Continue\r\n\r\nHTTP/1.1 200 OK\r\ncontent-length: 2\r\n\r\nok",
        );
        let transport = PlainHttpTransport::default();
        let mut response = transport.execute(get(addr, "/x")).unwrap();
        assert_eq!(response.status, 200);
        assert_eq!(response.read_to_string().unwrap(), "ok");
    }

    #[test]
    fn short_body_is_an_error_not_a_silent_success() {
        let addr = spawn_server("HTTP/1.1 200 OK\r\ncontent-length: 100\r\n\r\nshort");
        let transport = PlainHttpTransport::default();
        let mut response = transport.execute(get(addr, "/x")).unwrap();
        let error = response.read_to_string().unwrap_err();
        // 报错里要带着已经读到的原文：错误体常常就是诊断线索
        assert!(error.to_string().contains("short"), "{error}");
    }

    /// https 现在**走 TLS**，不再回 `Unsupported`（D161）。
    ///
    /// 连 127.0.0.1 的关端口：握手之前就失败，所以这条测试不碰外网，
    /// 但足以证明"scheme=https 时选的是 TLS 那条路"——回 `Unsupported` 就说明没选对。
    #[cfg(feature = "tls")]
    #[test]
    fn https_goes_through_tls_instead_of_being_refused() {
        let transport = HttpsTransport::default();
        let request = HttpRequest::post_json(
            "https://127.0.0.1:1/v1/chat/completions",
            "{}".as_bytes().to_vec(),
        );
        let error = transport.execute(request).unwrap_err();
        assert!(
            !matches!(error, TransportError::Unsupported(_)),
            "https 不该再被当成不支持：{error}"
        );
        assert!(matches!(error, TransportError::Connect { .. }));
    }

    #[test]
    fn https_is_refused_with_a_pointer_instead_of_silently_failing() {
        let transport = PlainHttpTransport::default();
        let error = transport
            .execute(HttpRequest::post(
                "https://api.example.com/v1/chat/completions",
            ))
            .unwrap_err();
        assert!(matches!(error, TransportError::Unsupported(_)));
        assert!(error.to_string().contains("D38"));
    }

    #[test]
    fn header_injection_is_rejected_before_sending() {
        let transport = PlainHttpTransport::default();
        let request = HttpRequest::post("http://127.0.0.1:1/v1/x")
            .header("x-api-key", "secret\r\nhost: evil.example");
        let error = transport.execute(request).unwrap_err();
        assert!(matches!(error, TransportError::Protocol(_)));
    }

    #[test]
    fn url_parsing_covers_ports_ipv6_and_query() {
        let url = Url::parse("http://127.0.0.1:8080/v1/chat/completions?debug=1").unwrap();
        assert_eq!(url.host, "127.0.0.1");
        assert_eq!(url.port, 8080);
        assert_eq!(url.path, "/v1/chat/completions?debug=1");

        let url = Url::parse("http://example.com").unwrap();
        assert_eq!(url.port, 80);
        assert_eq!(url.path, "/");

        let url = Url::parse("http://[::1]:8080/v1").unwrap();
        assert_eq!(url.host, "::1");
        assert_eq!(url.port, 8080);
        assert_eq!(authority_for_host_header(&url), "[::1]:8080");

        assert!(matches!(
            Url::parse("example.com/v1"),
            Err(TransportError::InvalidUrl(_))
        ));
        assert!(matches!(
            Url::parse("ftp://example.com/v1"),
            Err(TransportError::Unsupported(_))
        ));
        assert!(matches!(
            Url::parse("http://example.com:abc/v1"),
            Err(TransportError::InvalidUrl(_))
        ));
    }

    #[test]
    fn default_port_is_omitted_from_host_header() {
        let url = Url::parse("http://example.com/v1").unwrap();
        assert_eq!(authority_for_host_header(&url), "example.com");
        let url = Url::parse("http://example.com:8080/v1").unwrap();
        assert_eq!(authority_for_host_header(&url), "example.com:8080");
    }

    #[test]
    fn connect_failure_is_retryable_and_names_the_host() {
        let transport =
            PlainHttpTransport::default().with_connect_timeout(Duration::from_millis(200));
        // 端口 1 上不会有人监听
        let error = transport
            .execute(HttpRequest::new(Method::Get, "http://127.0.0.1:1/v1"))
            .unwrap_err();
        assert!(matches!(error, TransportError::Connect { .. }), "{error}");
        assert!(error.is_retryable());
        assert!(error.to_string().contains("127.0.0.1:1"));
    }
}
