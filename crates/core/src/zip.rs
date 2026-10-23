//! 最小 zip 读写：**只支持 store（不压缩）**，够导入导出图标包用（D105 / D108）。
//!
//! 为什么自己写：项目有一条禁令——不用 Rust 的 zip 系 crate（`NMoi-Notes/constraints.md`）。
//! 图标包里的东西是 PNG，本身已经压过，再 deflate 一遍只费 CPU；store 让实现小到能一眼看完，
//! 出了事能自己查。
//!
//! # 能力边界（写清楚，免得以后有人以为它能解任意 zip）
//!
//! - 只写 method 0（store）。
//! - 只读 method 0。遇到 deflate 直接报错，不尝试解码。
//! - 不支持 zip64、不支持加密、不支持分卷、不支持注释。
//! - 文件名按 UTF-8 写（通用标志位 bit 11）。
//!
//! 这些限制都是"拒绝"而不是"猜"：读不懂就说读不懂，别给半份数据。

/// 单文件上限（图标很小，512 KiB 已经离谱）。
pub const MAX_FILE_BYTES: usize = 512 * 1024;
/// 包内文件数上限。
pub const MAX_FILES: usize = 512;
/// 解压后总大小上限。
pub const MAX_TOTAL_BYTES: usize = 8 * 1024 * 1024;

const LOCAL_SIG: u32 = 0x0403_4b50;
const CENTRAL_SIG: u32 = 0x0201_4b50;
const EOCD_SIG: u32 = 0x0605_4b50;
const UTF8_FLAG: u16 = 0x0800;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum ZipError {
    #[error("不是一个 zip：找不到中央目录结尾")]
    NotZip,
    #[error("zip 被截断或结构损坏：{0}")]
    Corrupt(&'static str),
    #[error("不支持的压缩方法 {0}（只支持 store=0）")]
    UnsupportedMethod(u16),
    #[error("zip64 / 加密 / 分卷 都不支持")]
    UnsupportedFeature,
    #[error("条目路径不安全：{0}")]
    UnsafePath(String),
    #[error("条目过多：{0}（上限 {MAX_FILES}）")]
    TooManyFiles(usize),
    #[error("单个文件过大：{name} {size} 字节（上限 {MAX_FILE_BYTES}）")]
    FileTooBig { name: String, size: usize },
    #[error("解压后总量过大：{0} 字节（上限 {MAX_TOTAL_BYTES}）")]
    TotalTooBig(usize),
    #[error("CRC 不符：{name}")]
    CrcMismatch { name: String },
    #[error("重名条目：{0}")]
    DuplicateName(String),
}

/// 一个待写入的条目。
#[derive(Debug, Clone)]
pub struct ZipEntry {
    pub name: String,
    pub data: Vec<u8>,
}

/// CRC-32（IEEE，多项式 0xEDB88320）。手写一张表，20 行，不值得为它引依赖。
fn crc32(data: &[u8]) -> u32 {
    static TABLE: std::sync::OnceLock<[u32; 256]> = std::sync::OnceLock::new();
    let table = TABLE.get_or_init(|| {
        let mut table = [0u32; 256];
        let mut index = 0usize;
        while index < 256 {
            let mut value = index as u32;
            let mut bit = 0;
            while bit < 8 {
                value = if value & 1 != 0 {
                    (value >> 1) ^ 0xEDB8_8320
                } else {
                    value >> 1
                };
                bit += 1;
            }
            table[index] = value;
            index += 1;
        }
        table
    });
    let mut crc = 0xFFFF_FFFFu32;
    for byte in data {
        let slot = ((crc ^ u32::from(*byte)) & 0xFF) as usize;
        crc = (crc >> 8) ^ table[slot];
    }
    crc ^ 0xFFFF_FFFF
}

/// 路径安全：不许绝对路径、不许 `..`、不许空段。
///
/// 这条在**读**的时候也校验，因为它防的是"解出来的文件跑到包目录外面"。
fn check_name(name: &str) -> Result<(), ZipError> {
    if name.is_empty() {
        return Err(ZipError::UnsafePath("空文件名".to_string()));
    }
    if name.starts_with('/') || name.starts_with('\\') {
        return Err(ZipError::UnsafePath(name.to_string()));
    }
    // 盘符形式（C:…）也算绝对路径
    if name.len() > 1 && name.as_bytes()[1] == b':' {
        return Err(ZipError::UnsafePath(name.to_string()));
    }
    for part in name.split('/') {
        if part.is_empty() {
            return Err(ZipError::UnsafePath(name.to_string()));
        }
        if part == ".." {
            return Err(ZipError::UnsafePath(name.to_string()));
        }
    }
    if name.chars().any(|ch| ch == '\\' || (ch as u32) < 0x20) {
        return Err(ZipError::UnsafePath(name.to_string()));
    }
    Ok(())
}

/// 打一个 store zip。
pub fn write(entries: &[ZipEntry]) -> Result<Vec<u8>, ZipError> {
    if entries.len() > MAX_FILES {
        return Err(ZipError::TooManyFiles(entries.len()));
    }
    let mut total = 0usize;
    let mut seen = std::collections::HashSet::new();
    for entry in entries {
        check_name(&entry.name)?;
        if !seen.insert(entry.name.as_str()) {
            return Err(ZipError::DuplicateName(entry.name.clone()));
        }
        if entry.data.len() > MAX_FILE_BYTES {
            return Err(ZipError::FileTooBig {
                name: entry.name.clone(),
                size: entry.data.len(),
            });
        }
        total += entry.data.len();
    }
    if total > MAX_TOTAL_BYTES {
        return Err(ZipError::TotalTooBig(total));
    }
    if entries.len() > u16::MAX as usize {
        return Err(ZipError::TooManyFiles(entries.len()));
    }

    let mut out = Vec::with_capacity(total + entries.len() * 128 + 22);
    let mut central: Vec<u8> = Vec::new();

    for entry in entries {
        let name = entry.name.as_bytes();
        let crc = crc32(&entry.data);
        let offset = out.len() as u32;

        out.extend_from_slice(&LOCAL_SIG.to_le_bytes());
        out.extend_from_slice(&20u16.to_le_bytes()); // version needed
        out.extend_from_slice(&UTF8_FLAG.to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // method = store
        out.extend_from_slice(&0u16.to_le_bytes()); // mod time
        out.extend_from_slice(&0x21u16.to_le_bytes()); // mod date = 1980-01-01
        out.extend_from_slice(&crc.to_le_bytes());
        out.extend_from_slice(&(entry.data.len() as u32).to_le_bytes());
        out.extend_from_slice(&(entry.data.len() as u32).to_le_bytes());
        out.extend_from_slice(&(name.len() as u16).to_le_bytes());
        out.extend_from_slice(&0u16.to_le_bytes()); // extra len
        out.extend_from_slice(name);
        out.extend_from_slice(&entry.data);

        central.extend_from_slice(&CENTRAL_SIG.to_le_bytes());
        central.extend_from_slice(&20u16.to_le_bytes()); // version made by
        central.extend_from_slice(&20u16.to_le_bytes()); // version needed
        central.extend_from_slice(&UTF8_FLAG.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes());
        central.extend_from_slice(&0x21u16.to_le_bytes());
        central.extend_from_slice(&crc.to_le_bytes());
        central.extend_from_slice(&(entry.data.len() as u32).to_le_bytes());
        central.extend_from_slice(&(entry.data.len() as u32).to_le_bytes());
        central.extend_from_slice(&(name.len() as u16).to_le_bytes());
        central.extend_from_slice(&0u16.to_le_bytes()); // extra
        central.extend_from_slice(&0u16.to_le_bytes()); // comment
        central.extend_from_slice(&0u16.to_le_bytes()); // disk start
        central.extend_from_slice(&0u16.to_le_bytes()); // internal attrs
        central.extend_from_slice(&0u32.to_le_bytes()); // external attrs
        central.extend_from_slice(&offset.to_le_bytes());
        central.extend_from_slice(name);
    }

    let central_offset = out.len() as u32;
    let central_size = central.len() as u32;
    out.extend_from_slice(&central);

    out.extend_from_slice(&EOCD_SIG.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes()); // disk
    out.extend_from_slice(&0u16.to_le_bytes()); // cd disk
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    out.extend_from_slice(&(entries.len() as u16).to_le_bytes());
    out.extend_from_slice(&central_size.to_le_bytes());
    out.extend_from_slice(&central_offset.to_le_bytes());
    out.extend_from_slice(&0u16.to_le_bytes()); // comment len
    Ok(out)
}

/// 读出来的条目（已经校验过 CRC 与路径）。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReadEntry {
    pub name: String,
    pub data: Vec<u8>,
}

fn u16_at(data: &[u8], at: usize) -> Result<u16, ZipError> {
    data.get(at..at + 2)
        .map(|slice| u16::from_le_bytes([slice[0], slice[1]]))
        .ok_or(ZipError::Corrupt("越界读 u16"))
}

fn u32_at(data: &[u8], at: usize) -> Result<u32, ZipError> {
    data.get(at..at + 4)
        .map(|slice| u32::from_le_bytes([slice[0], slice[1], slice[2], slice[3]]))
        .ok_or(ZipError::Corrupt("越界读 u32"))
}

/// 解开一个 store zip。任何一条不认识的结构都报错，不半信半疑地返回部分内容。
pub fn read(data: &[u8]) -> Result<Vec<ReadEntry>, ZipError> {
    if data.len() < 22 {
        return Err(ZipError::NotZip);
    }
    // EOCD 在末尾，注释最长 65535
    let search_start = data.len().saturating_sub(22 + 65535);
    let mut eocd = None;
    let mut cursor = data.len() - 22;
    loop {
        if u32_at(data, cursor)? == EOCD_SIG {
            eocd = Some(cursor);
            break;
        }
        if cursor == search_start {
            break;
        }
        cursor -= 1;
    }
    let eocd = eocd.ok_or(ZipError::NotZip)?;

    let count = u16_at(data, eocd + 10)? as usize;
    let central_size = u32_at(data, eocd + 12)? as usize;
    let central_offset = u32_at(data, eocd + 16)? as usize;
    if count == u16::MAX as usize || central_offset == u32::MAX as usize {
        return Err(ZipError::UnsupportedFeature);
    }
    if count > MAX_FILES {
        return Err(ZipError::TooManyFiles(count));
    }
    if central_offset + central_size > data.len() {
        return Err(ZipError::Corrupt("中央目录越界"));
    }

    let mut out = Vec::with_capacity(count);
    let mut total = 0usize;
    let mut cursor = central_offset;
    for _ in 0..count {
        if u32_at(data, cursor)? != CENTRAL_SIG {
            return Err(ZipError::Corrupt("中央目录签名不对"));
        }
        let flags = u16_at(data, cursor + 8)?;
        let method = u16_at(data, cursor + 10)?;
        let crc = u32_at(data, cursor + 16)?;
        let compressed = u32_at(data, cursor + 20)? as usize;
        let uncompressed = u32_at(data, cursor + 24)? as usize;
        let name_len = u16_at(data, cursor + 28)? as usize;
        let extra_len = u16_at(data, cursor + 30)? as usize;
        let comment_len = u16_at(data, cursor + 32)? as usize;
        let local_offset = u32_at(data, cursor + 42)? as usize;

        if flags & 0x0001 != 0 {
            return Err(ZipError::UnsupportedFeature); // 加密
        }
        if method != 0 {
            return Err(ZipError::UnsupportedMethod(method));
        }
        if compressed == u32::MAX as usize || uncompressed == u32::MAX as usize {
            return Err(ZipError::UnsupportedFeature); // zip64
        }
        if compressed != uncompressed {
            return Err(ZipError::Corrupt("store 条目的两个长度不一致"));
        }
        let name_bytes = data
            .get(cursor + 46..cursor + 46 + name_len)
            .ok_or(ZipError::Corrupt("越界读文件名"))?;
        let name = String::from_utf8(name_bytes.to_vec())
            .map_err(|_| ZipError::Corrupt("文件名不是 UTF-8"))?;
        check_name(&name)?;

        if u32_at(data, local_offset)? != LOCAL_SIG {
            return Err(ZipError::Corrupt("本地头签名不对"));
        }
        // 本地头的名字/额外字段长度可能与中央目录不同，必须读本地头
        let local_name_len = u16_at(data, local_offset + 26)? as usize;
        let local_extra_len = u16_at(data, local_offset + 28)? as usize;
        let data_start = local_offset + 30 + local_name_len + local_extra_len;
        let payload = data
            .get(data_start..data_start + compressed)
            .ok_or(ZipError::Corrupt("越界读条目数据"))?;

        if payload.len() > MAX_FILE_BYTES {
            return Err(ZipError::FileTooBig {
                name: name.clone(),
                size: payload.len(),
            });
        }
        total += payload.len();
        if total > MAX_TOTAL_BYTES {
            return Err(ZipError::TotalTooBig(total));
        }
        if crc32(payload) != crc {
            return Err(ZipError::CrcMismatch { name });
        }

        out.push(ReadEntry {
            name,
            data: payload.to_vec(),
        });
        cursor += 46 + name_len + extra_len + comment_len;
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(name: &str, body: &[u8]) -> ZipEntry {
        ZipEntry {
            name: name.to_string(),
            data: body.to_vec(),
        }
    }

    #[test]
    fn roundtrip_preserves_names_and_bytes() {
        let entries = vec![
            entry("manifest.json", b"{\"a\":1}"),
            entry("actions/search.png", b"\x89PNG\r\n\x1a\nfake"),
            entry("providers/中文 名字.png", b"x"),
        ];
        let bytes = write(&entries).unwrap();
        let back = read(&bytes).unwrap();
        assert_eq!(back.len(), 3);
        for (written, read_back) in entries.iter().zip(back.iter()) {
            assert_eq!(written.name, read_back.name);
            assert_eq!(written.data, read_back.data);
        }
    }

    #[test]
    fn crc32_matches_known_vectors() {
        // 标准测试向量
        assert_eq!(crc32(b""), 0x0000_0000);
        assert_eq!(crc32(b"123456789"), 0xCBF4_3926);
        assert_eq!(
            crc32(b"The quick brown fox jumps over the lazy dog"),
            0x414F_A339
        );
    }

    #[test]
    fn empty_zip_is_readable() {
        let bytes = write(&[]).unwrap();
        assert!(read(&bytes).unwrap().is_empty());
    }

    #[test]
    fn traversal_names_are_rejected_on_write_and_read() {
        for bad in [
            "../evil.png",
            "a/../../b.png",
            "/abs.png",
            "C:/abs.png",
            "a//b.png",
        ] {
            assert!(
                matches!(write(&[entry(bad, b"x")]), Err(ZipError::UnsafePath(_))),
                "{bad} 应被拒绝"
            );
        }
        // 手工造一个含 ../ 的 zip：写入时被挡住，所以这里直接改字节。
        // 名字在本地头与中央目录各出现一次，**两处都要改**——只看本地头会漏掉真正的校验点
        // （读的时候用的是中央目录里的名字），这种"测了个寂寞"最容易骗自己。
        let mut bytes = write(&[entry("ok.png", b"x")]).unwrap();
        let mut patched = 0;
        let mut index = 0;
        while index + 6 <= bytes.len() {
            if &bytes[index..index + 6] == b"ok.png" {
                bytes[index..index + 6].copy_from_slice(b"../a.x");
                patched += 1;
            }
            index += 1;
        }
        assert_eq!(patched, 2, "本地头 + 中央目录各一处");
        assert!(matches!(read(&bytes), Err(ZipError::UnsafePath(_))));
    }

    #[test]
    fn duplicate_names_are_rejected() {
        let err = write(&[entry("a.png", b"1"), entry("a.png", b"2")]).unwrap_err();
        assert_eq!(err, ZipError::DuplicateName("a.png".to_string()));
    }

    #[test]
    fn oversized_file_is_rejected() {
        let big = vec![0u8; MAX_FILE_BYTES + 1];
        let err = write(&[entry("big.png", &big)]).unwrap_err();
        assert!(matches!(err, ZipError::FileTooBig { .. }));
    }

    #[test]
    fn deflate_is_refused_not_guessed() {
        let mut bytes = write(&[entry("a.png", b"hello")]).unwrap();
        // method 字段：本地头 +8，中央目录 +10
        bytes[8..10].copy_from_slice(&8u16.to_le_bytes());
        let central = bytes
            .windows(4)
            .position(|window| window == CENTRAL_SIG.to_le_bytes())
            .unwrap();
        bytes[central + 10..central + 12].copy_from_slice(&8u16.to_le_bytes());
        assert_eq!(read(&bytes).unwrap_err(), ZipError::UnsupportedMethod(8));
    }

    #[test]
    fn corruption_is_detected_by_crc() {
        let mut bytes = write(&[entry("a.png", b"hello")]).unwrap();
        let position = bytes
            .windows(5)
            .position(|window| window == b"hello")
            .unwrap();
        bytes[position] = b'j';
        assert!(matches!(read(&bytes), Err(ZipError::CrcMismatch { .. })));
    }

    #[test]
    fn non_zip_input_is_refused() {
        assert_eq!(
            read(b"not a zip at all, really not").unwrap_err(),
            ZipError::NotZip
        );
        assert_eq!(read(b"").unwrap_err(), ZipError::NotZip);
    }

    #[test]
    fn truncated_zip_is_refused() {
        let bytes = write(&[entry("a.png", b"hello")]).unwrap();
        let cut = &bytes[..bytes.len() - 30];
        assert!(read(cut).is_err());
    }
}
