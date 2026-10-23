//! SSE（Server-Sent Events）帧解析。
//!
//! 四种 provider 的流式格式全是 SSE，差别只在 `data:` 里的 JSON 长什么样，
//! 所以帧的切分只做一次，放在这里；每种 provider 的字段解释在 [`crate::stream`]。
//!
//! 只按 W3C 的 EventSource 规范读我们真用得上的字段：`event` / `data` / `id`，
//! `retry` 走 `next_frame` 直接丢掉（浏览器断线重连才用它，我们不做自动重连）。
//!
//! 三条真机上真的会碰到的规矩，这里都照做了：
//!
//! 1. **空行才是一个帧的结束**——不能见到 `data:` 就交出去，多行 data 要拼起来。
//! 2. **`:` 开头是注释**（心跳就别指望它了，很多网关只在空闲时发）。
//! 3. **EOF 时如果还有攒着的 data，照样交出去**：有的服务端最后一帧不带空行。
//!    丢掉它等于丢掉 `message_stop`，上层会把一次正常结束当成断流。

use std::io::{BufRead, BufReader};

/// 一帧。`data` 已把多行用 `\n` 拼好。
#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct SseFrame {
    pub event: Option<String>,
    pub data: String,
    pub id: Option<String>,
}

impl SseFrame {
    pub fn new(data: impl Into<String>) -> Self {
        Self {
            data: data.into(),
            ..Self::default()
        }
    }

    pub fn with_event(event: impl Into<String>, data: impl Into<String>) -> Self {
        Self {
            event: Some(event.into()),
            data: data.into(),
            id: None,
        }
    }

    /// OpenAI 系的终止哨兵。Anthropic 系不用它，但不少网关会在末尾补一个。
    pub fn is_done(&self) -> bool {
        self.data.trim() == "[DONE]"
    }

    /// 事件名：有的服务端只在 `event:` 行给，有的只在 data 里给 `type`。
    /// 这里只负责把 `event:` 那一行给你，data 里的 `type` 由适配器读。
    pub fn event_name(&self) -> Option<&str> {
        self.event.as_deref()
    }
}

#[derive(Debug, Clone, PartialEq, Eq, thiserror::Error)]
pub enum SseError {
    #[error("读流失败: {0}")]
    Io(String),
    /// 单行超过上限。正常 SSE 行不会这么长，出现它基本等于对端在发垃圾
    /// （没有换行的巨块 / 二进制），继续读下去只会把内存吃光。
    #[error("SSE 单行超过 {limit} 字节")]
    LineTooLong { limit: usize },
}

/// 默认单行上限。1 MiB 是"一个塞满 base64 图片的 JSON"都可能碰到的量级。
pub const DEFAULT_MAX_LINE_BYTES: usize = 1024 * 1024;

/// 把字节流切成帧。持有内部状态（跨读取的半个帧）。
pub struct SseReader<R: BufRead> {
    inner: R,
    event: Option<String>,
    id: Option<String>,
    data: Vec<String>,
    finished: bool,
    max_line_bytes: usize,
}

impl<R: BufRead> SseReader<R> {
    pub fn new(inner: R) -> Self {
        Self {
            inner,
            event: None,
            id: None,
            data: Vec::new(),
            finished: false,
            max_line_bytes: DEFAULT_MAX_LINE_BYTES,
        }
    }

    pub fn with_max_line_bytes(mut self, limit: usize) -> Self {
        self.max_line_bytes = limit.max(1);
        self
    }

    pub fn into_inner(self) -> R {
        self.inner
    }

    /// 下一帧。`Ok(None)` = 流正常结束。
    pub fn next_frame(&mut self) -> Result<Option<SseFrame>, SseError> {
        if self.finished {
            return Ok(None);
        }

        loop {
            let mut line = Vec::new();
            let read = self
                .inner
                .read_until(b'\n', &mut line)
                .map_err(|error| SseError::Io(error.to_string()))?;

            if read == 0 {
                self.finished = true;
                // 收尾：EOF 前没等到空行，攒着的东西不能吞掉。
                if !self.data.is_empty() || self.event.is_some() {
                    return Ok(Some(self.take_frame()));
                }
                return Ok(None);
            }

            if line.len() > self.max_line_bytes {
                self.finished = true;
                return Err(SseError::LineTooLong {
                    limit: self.max_line_bytes,
                });
            }

            let text = String::from_utf8_lossy(&line);
            let text = text.trim_end_matches(['\n', '\r']);

            if text.is_empty() {
                if self.data.is_empty() && self.event.is_none() {
                    continue; // 空行当分隔符是常态，连着几个也不奇怪
                }
                return Ok(Some(self.take_frame()));
            }

            if text.starts_with(':') {
                continue; // 注释 / 心跳
            }

            let (field, value) = match text.split_once(':') {
                Some((field, value)) => (field, value.strip_prefix(' ').unwrap_or(value)),
                None => (text, ""),
            };

            match field {
                "data" => self.data.push(value.to_string()),
                "event" => self.event = Some(value.to_string()),
                "id" => self.id = Some(value.to_string()),
                _ => {}
            }
        }
    }

    fn take_frame(&mut self) -> SseFrame {
        SseFrame {
            event: self.event.take(),
            data: std::mem::take(&mut self.data).join("\n"),
            // id 照规范要跨帧保留：重连时用它去重，所以我们只在收到新 id 时覆盖
            id: self.id.clone(),
        }
    }
}

/// 便捷构造：从任意 `Read` 包一层缓冲。
pub fn buffered<R: std::io::Read>(reader: R) -> SseReader<BufReader<R>> {
    SseReader::new(BufReader::new(reader))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Cursor, Read};

    fn frames(raw: &str) -> Vec<SseFrame> {
        let mut reader = SseReader::new(Cursor::new(raw.as_bytes().to_vec()));
        let mut out = Vec::new();
        while let Some(frame) = reader.next_frame().unwrap() {
            out.push(frame);
        }
        out
    }

    #[test]
    fn splits_frames_on_blank_lines_and_keeps_event_names() {
        let raw = "event: content_block_delta\ndata: {\"a\":1}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}\n\n";
        let frames = frames(raw);
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].event_name(), Some("content_block_delta"));
        assert_eq!(frames[0].data, "{\"a\":1}");
        assert_eq!(frames[1].event_name(), Some("message_stop"));
    }

    #[test]
    fn crlf_and_comments_and_pings_are_handled() {
        let raw = ": keep-alive\r\n\r\nevent: ping\r\ndata: {\"type\":\"ping\"}\r\n\r\ndata: real\r\n\r\n";
        let frames = frames(raw);
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[0].event_name(), Some("ping"));
        assert_eq!(frames[1].data, "real");
    }

    #[test]
    fn multiple_data_lines_are_joined_with_newline() {
        let frames = frames("data: line1\ndata: line2\n\n");
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].data, "line1\nline2");
    }

    #[test]
    fn done_sentinel_is_recognized() {
        let frames = frames("data: [DONE]\n\n");
        assert_eq!(frames.len(), 1);
        assert!(frames[0].is_done());
    }

    #[test]
    fn trailing_frame_without_blank_line_is_not_swallowed() {
        // 有的服务端最后一段不带空行就关连接，丢掉它会把正常结束当成断流
        let frames =
            frames("data: {\"a\":1}\n\nevent: message_stop\ndata: {\"type\":\"message_stop\"}");
        assert_eq!(frames.len(), 2);
        assert_eq!(frames[1].event_name(), Some("message_stop"));
    }

    #[test]
    fn empty_stream_yields_nothing() {
        assert!(frames("").is_empty());
        assert!(frames("\n\n\n").is_empty());
        assert!(frames(": only a comment\n\n").is_empty());
    }

    #[test]
    fn value_without_leading_space_and_field_without_colon() {
        let frames = frames("data:{\"a\":1}\n\ndata\ndata: x\n\n");
        assert_eq!(frames[0].data, "{\"a\":1}");
        // `data` 单独一行 = 空 data 行，会拼出前导换行；跟规范一致
        assert_eq!(frames[1].data, "\nx");
    }

    /// 每次只吐一个字节的源：模拟一个帧被 TCP 段切得七零八落。
    struct Drip {
        data: Vec<u8>,
        position: usize,
    }

    impl Read for Drip {
        fn read(&mut self, buffer: &mut [u8]) -> std::io::Result<usize> {
            if self.position >= self.data.len() || buffer.is_empty() {
                return Ok(0);
            }
            buffer[0] = self.data[self.position];
            self.position += 1;
            Ok(1)
        }
    }

    #[test]
    fn frame_survives_being_split_byte_by_byte() {
        let raw = "event: content_block_delta\ndata: {\"delta\":{\"text\":\"你好\"}}\n\n";
        let drip = Drip {
            data: raw.as_bytes().to_vec(),
            position: 0,
        };
        let mut reader = SseReader::new(BufReader::with_capacity(1, drip));
        let frame = reader.next_frame().unwrap().unwrap();
        assert_eq!(frame.event_name(), Some("content_block_delta"));
        assert_eq!(frame.data, "{\"delta\":{\"text\":\"你好\"}}");
        assert!(reader.next_frame().unwrap().is_none());
    }

    #[test]
    fn overlong_line_is_an_error_not_a_memory_sink() {
        let raw = format!("data: {}\n\n", "x".repeat(64));
        let mut reader = SseReader::new(Cursor::new(raw.into_bytes())).with_max_line_bytes(16);
        assert!(matches!(
            reader.next_frame(),
            Err(SseError::LineTooLong { limit: 16 })
        ));
        // 报错之后不再继续读，避免对着烂流空转
        assert!(reader.next_frame().unwrap().is_none());
    }

    #[test]
    fn event_only_frame_is_surfaced() {
        // 规范说 data 为空就不派发，但真有机场把终止事件只写成一行 event；
        // 交出去比吞掉安全，空 data 由适配器忽略。
        let frames = frames("event: message_stop\n\n");
        assert_eq!(frames.len(), 1);
        assert_eq!(frames[0].data, "");
        assert_eq!(frames[0].event_name(), Some("message_stop"));
    }
}
