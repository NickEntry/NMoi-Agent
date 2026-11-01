//! 最小 base64（标准字母表 + `=` 填充），只因为图片要过 JSON（D208）。
//!
//! 为什么自己写而不是引 crate：这里只需要 encode/decode 两个函数，
//! 而每引一个依赖都要跟着看许可、看供应链（`NMoi-Notes/constraints.md` 的口径）。
//! 解码**严格**：非法字符、长度不对、填充位置不对都返回 `None`——
//! 图片字节被悄悄截断比直接失败更难查。

const ALPHABET: &[u8; 64] = b"ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

pub fn encode(bytes: &[u8]) -> String {
    let mut out = String::with_capacity(bytes.len().div_ceil(3) * 4);
    for chunk in bytes.chunks(3) {
        let b0 = chunk[0] as u32;
        let b1 = *chunk.get(1).unwrap_or(&0) as u32;
        let b2 = *chunk.get(2).unwrap_or(&0) as u32;
        let triple = (b0 << 16) | (b1 << 8) | b2;
        out.push(ALPHABET[(triple >> 18) as usize & 0x3f] as char);
        out.push(ALPHABET[(triple >> 12) as usize & 0x3f] as char);
        out.push(if chunk.len() > 1 {
            ALPHABET[(triple >> 6) as usize & 0x3f] as char
        } else {
            '='
        });
        out.push(if chunk.len() > 2 {
            ALPHABET[triple as usize & 0x3f] as char
        } else {
            '='
        });
    }
    out
}

/// 解码。空白与换行先去掉（Kotlin 的 `Base64.encodeToString` 会带换行）。
pub fn decode(text: &str) -> Option<Vec<u8>> {
    let mut values = Vec::with_capacity(text.len());
    let mut padding = 0usize;
    for byte in text.bytes() {
        match byte {
            b'=' => {
                padding += 1;
                values.push(0u8);
            }
            b'\n' | b'\r' | b' ' | b'\t' => {}
            _ => {
                if padding > 0 {
                    // 填充之后还有数据：不是合法 base64。
                    return None;
                }
                let value = match byte {
                    b'A'..=b'Z' => byte - b'A',
                    b'a'..=b'z' => byte - b'a' + 26,
                    b'0'..=b'9' => byte - b'0' + 52,
                    b'+' => 62,
                    b'/' => 63,
                    _ => return None,
                };
                values.push(value);
            }
        }
    }
    if padding > 2 || values.len() % 4 != 0 {
        return None;
    }
    let mut out = Vec::with_capacity(values.len() / 4 * 3);
    for quad in values.chunks(4) {
        let triple = (u32::from(quad[0]) << 18)
            | (u32::from(quad[1]) << 12)
            | (u32::from(quad[2]) << 6)
            | u32::from(quad[3]);
        out.push((triple >> 16) as u8);
        out.push((triple >> 8) as u8);
        out.push(triple as u8);
    }
    out.truncate(out.len() - padding);
    Some(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn roundtrip_covers_every_padding_case() {
        for raw in [
            &b""[..],
            b"a",
            b"ab",
            b"abc",
            b"abcd",
            &[0u8, 255, 128, 1, 2, 3][..],
        ] {
            let encoded = encode(raw);
            assert_eq!(decode(&encoded).as_deref(), Some(raw), "{encoded}");
        }
    }

    #[test]
    fn known_vectors() {
        assert_eq!(encode(b"hello"), "aGVsbG8=");
        assert_eq!(encode(b"a"), "YQ==");
        assert_eq!(decode("aGVsbG8=").unwrap(), b"hello");
        // Kotlin 的编码器会折行：解之前要能容下换行。
        assert_eq!(decode("aGVs\nbG8=").unwrap(), b"hello");
    }

    #[test]
    fn malformed_input_is_refused() {
        assert!(decode("aGVsbG8").is_none(), "长度不是 4 的倍数");
        assert!(decode("aGV$bG8=").is_none(), "非法字符");
        assert!(decode("aGVsbG8=x").is_none(), "填充之后还有数据");
        assert!(decode("====").is_none(), "全是填充");
    }
}
