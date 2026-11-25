//! 最小 7z 写入器：**只写不读**，压缩用 LZMA2（D425）。
//!
//! 为什么自己写：仓库那条禁令是"不用 Rust 的 zip 系 crate，要打包解包就自己写最小实现"
//! （`NMoi-Notes/constraints.md`）。7z 是同一类东西，走同一条规矩。而且这一版只需要
//! **写**：导出备份包、导出多选的附件、导出技能目录，没有一处要读别人的 7z。
//!
//! # 能力边界（写清楚，免得以后有人以为它是个 7z 库）
//!
//! - 只写**非固实**（`-ms=off` 的等价物）：一个文件一条流、一条 folder。多选导出的
//!   典型体积是几十 MB、几十个文件，非固实换来"单个文件坏掉不连累其余"，而且实现小到
//!   能一眼看完。
//! - 只写 LZMA2。不写 LZMA1、不写 BCJ 滤镜、不写加密。
//! - 不写时间戳与 Windows 属性（7z 里这两个都是可选段，缺了照样能解开；
//!   写上去就得多背一套 FILETIME 换算，而备份包里时间戳没有用——`7z l` 看得见文件名就够了）。
//! - 名字按 UTF-8 写，段里用 UTF-16LE 编码（7z 的 `kName` 就是这个编码，不是我们能选的）。
//! - 空文件照样进包（`kEmptyStream` + `kEmptyFile` 两段位图），否则"备份里少了一个文件"
//!   这种事在界面上完全看不出来。
//!
//! # 与 `zip` 模块的关系
//!
//! 两个都是"字节层的容器"，各管一种格式：`zip` 给图标包（store，不压缩），
//! `sevenz` 给备份与导出（LZMA2）。判据不同所以不合并——合并之后每个调用点都要多传一个
//! "用哪种"，而那种参数迟早会被抄错。

use std::io::Write;

/// 单文件上限。备份包里最大的是 `nmoi.db`，正常几十 MB；2 GiB 是"明显不对"的那条线，
/// 不是"常见大小"——撞上它说明调用方把整个目录当文件读了。
pub const MAX_ENTRY_BYTES: usize = 2 * 1024 * 1024 * 1024;

/// 条目数上限。一个会话目录正常几十个文件，工作区可能几千个。
pub const MAX_ENTRIES: usize = 20_000;

/// LZMA2 的字典大小（D425 定的"标准压缩等级"）。
///
/// 8 MiB 是 7z 的 `-mx5`（标准档）在 64 位上的字典；`-mx5` 的其它参数（nice_len 32、
/// BT4、lc3 lp0 pb2）由 `lzma_rust::LZMA2Options::with_preset(5)` 一次给齐。
/// 不为省体积换档——用户原话是"依旧是标准压缩等级 7z"。
const DICT_SIZE: u32 = 8 << 20;

#[derive(Debug, thiserror::Error, PartialEq, Eq)]
pub enum SevenZError {
    #[error("条目名不安全：{0}")]
    UnsafeName(String),
    #[error("重名条目：{0}")]
    DuplicateName(String),
    #[error("条目过多：{0}（上限 {MAX_ENTRIES}）")]
    TooManyEntries(usize),
    #[error("单个文件过大：{name} {size} 字节（上限 {MAX_ENTRY_BYTES}）")]
    EntryTooBig { name: String, size: usize },
    #[error("压缩失败：{0}")]
    Compress(String),
    #[error("写不进去：{0}")]
    Io(String),
}

/// 一条要进包的文件。`name` 是包内路径（`a/b.txt`），调用方负责先规范化。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Entry {
    pub name: String,
    pub data: Vec<u8>,
}

impl Entry {
    pub fn new(name: impl Into<String>, data: Vec<u8>) -> Self {
        Self {
            name: name.into(),
            data,
        }
    }
}

/// 把一组文件写成一份 7z，返回完整字节。
///
/// 内存版：调用方是"导出用户选中的那几个附件"，几十 MB 量级。整目录（工作区 9.86 GB）
/// 走 [`write_to`]——那个边读边写，内存里只过一块 64 KiB 的缓冲。
pub fn write(entries: &[Entry]) -> Result<Vec<u8>, SevenZError> {
    let mut plan: Vec<StreamEntry<'_>> = Vec::with_capacity(entries.len());
    for entry in entries {
        plan.push(StreamEntry {
            name: entry.name.clone(),
            reader: Box::new(std::io::Cursor::new(entry.data.as_slice())),
        });
    }
    let mut out = std::io::Cursor::new(Vec::new());
    write_to(&mut out, &mut plan, &mut |_| {})?;
    Ok(out.into_inner())
}

/// 一条"从哪儿读、在包里叫什么"。
pub struct StreamEntry<'a> {
    /// 包内路径（`a/b.txt`）。调用方负责先规范化，这里只做安全检查。
    pub name: String,
    /// 这条从哪儿读。`Box<dyn Read>` 而不是 `&mut dyn Read`：调用方一次要摆几十上百条，
    /// 借用版本在"先建表、再遍历"这个形状下借不过编译器（`&mut` 不能同时借两次）。
    pub reader: Box<dyn std::io::Read + 'a>,
}

impl<'a> StreamEntry<'a> {
    /// 从一段内存建一条。测试与小文件用。
    pub fn from_bytes(name: impl Into<String>, data: &'a [u8]) -> Self {
        Self {
            name: name.into(),
            reader: Box::new(std::io::Cursor::new(data)),
        }
    }

    /// 从盘上一个文件建一条。导出走这个。
    pub fn from_path(name: impl Into<String>, path: &std::path::Path) -> Result<Self, SevenZError> {
        let file = std::fs::File::open(path)
            .map_err(|error| SevenZError::Io(format!("打不开 {}：{error}", path.display())))?;
        Ok(Self {
            name: name.into(),
            reader: Box::new(std::io::BufReader::with_capacity(BUF, file)),
        })
    }
}

/// 一次写包的进度。9.86 GB 的工作区导出要几十秒，界面上没有这些数就只剩一个转圈。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct Progress {
    pub entries_done: usize,
    pub entries_total: usize,
    /// 已经写进包的字节（压缩后）。
    pub bytes_out: u64,
    /// 已经读过的原始字节。
    pub bytes_in: u64,
}

/// 一条进包之后的账：解开是多大、原文 CRC 是多少。判据用，不写进包。
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct Written {
    pub name: String,
    pub size: u64,
    pub crc: u32,
}

/// 边读边写一份 7z 到 `out`。
///
/// # 为什么要求 `Seek`
///
/// 7z 的签名头在**最前面**、条目头在**最后面**，而签名头里的三个数（payload 长度、
/// 头长度、头 CRC）要等全部写完才知道。所以顺序是：先让开 32 字节 → 流式写 payload →
/// 写头 → 回头补签名头。这正是 7z 格式本身的样子，不是偷懒。
///
/// # 内存
///
/// 每条条目只过一块 [`BUF`] 大小的缓冲，压缩器自己的字典是 8 MiB。
/// 导出 9.86 GB 的工作区时，占用的内存与导出 1 MB 时一样。
pub fn write_to<W: std::io::Write + std::io::Seek>(
    out: &mut W,
    entries: &mut [StreamEntry<'_>],
    progress: &mut dyn FnMut(&Progress),
) -> Result<Vec<Written>, SevenZError> {
    if entries.len() > MAX_ENTRIES {
        return Err(SevenZError::TooManyEntries(entries.len()));
    }
    let mut seen = std::collections::BTreeSet::new();
    for entry in entries.iter() {
        check_name(&entry.name)?;
        if !seen.insert(entry.name.clone()) {
            return Err(SevenZError::DuplicateName(entry.name.clone()));
        }
    }

    // 让开签名头那 32 字节：它的内容要等写完全部才知道。
    out.seek(std::io::SeekFrom::Start(SIGNATURE_HEADER_SIZE))?;

    let total = entries.len();
    let mut written: Vec<Written> = Vec::with_capacity(total);
    // 与 `entries` 同序：有内容的那些各占一条 pack stream，空的是 0。
    let mut pack_sizes: Vec<u64> = Vec::with_capacity(total);
    let mut bytes_out = 0u64;
    let mut bytes_in = 0u64;

    for (index, entry) in entries.iter_mut().enumerate() {
        let mut buf = vec![0u8; BUF];
        let first = read_some(entry.reader.as_mut(), &mut buf)?;
        if first == 0 {
            // 空文件：不建 pack stream，只在头里记一个 empty-stream 位。
            pack_sizes.push(0);
            written.push(Written {
                name: entry.name.clone(),
                size: 0,
                crc: 0,
            });
            progress(&Progress {
                entries_done: index + 1,
                entries_total: total,
                bytes_out,
                bytes_in,
            });
            continue;
        }

        let mut crc = 0xffff_ffffu32;
        let mut size = 0u64;
        let options = lzma_rust::LZMA2Options::with_preset(5);
        let packed_len;
        {
            let counter = lzma_rust::CountingWriter::new(&mut *out);
            // `CountingWriter` 被 writer 拿走之后拿不回来，所以提前留一个共享计数句柄。
            let seen = counter.counting();
            let mut writer = lzma_rust::LZMA2Writer::new(counter, &options);
            let mut chunk = &buf[..first];
            loop {
                crc = crc32_update(crc, chunk);
                size += chunk.len() as u64;
                writer
                    .write_all(chunk)
                    .map_err(|error| SevenZError::Compress(error.to_string()))?;
                let read = read_some(entry.reader.as_mut(), &mut buf)?;
                if read == 0 {
                    break;
                }
                chunk = &buf[..read];
                progress(&Progress {
                    entries_done: index,
                    entries_total: total,
                    bytes_out: bytes_out + seen.get() as u64,
                    bytes_in: bytes_in + size,
                });
            }
            writer
                .finish()
                .map_err(|error| SevenZError::Compress(error.to_string()))?;
            packed_len = seen.get() as u64;
        }
        if size > MAX_ENTRY_BYTES as u64 {
            return Err(SevenZError::EntryTooBig {
                name: entry.name.clone(),
                size: size as usize,
            });
        }

        bytes_out += packed_len;
        bytes_in += size;
        pack_sizes.push(packed_len);
        written.push(Written {
            name: entry.name.clone(),
            size,
            crc: crc ^ 0xffff_ffff,
        });
        progress(&Progress {
            entries_done: index + 1,
            entries_total: total,
            bytes_out,
            bytes_in,
        });
    }

    let header = build_header(&written, &pack_sizes)?;
    out.write_all(&header)
        .map_err(|error| SevenZError::Compress(error.to_string()))?;

    // 回头补签名头。
    let mut start = Vec::with_capacity(20);
    start.extend_from_slice(&bytes_out.to_le_bytes());
    start.extend_from_slice(&(header.len() as u64).to_le_bytes());
    start.extend_from_slice(&crc32(&header).to_le_bytes());

    let mut signature = Vec::with_capacity(SIGNATURE_HEADER_BYTES);
    signature.extend_from_slice(&[0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c]);
    signature.extend_from_slice(&[0x00, 0x04]);
    signature.extend_from_slice(&crc32(&start).to_le_bytes());
    signature.extend_from_slice(&start);

    out.seek(std::io::SeekFrom::Start(0))?;
    out.write_all(&signature)
        .map_err(|error| SevenZError::Compress(error.to_string()))?;
    out.flush()
        .map_err(|error| SevenZError::Compress(error.to_string()))?;
    Ok(written)
}

impl From<std::io::Error> for SevenZError {
    fn from(error: std::io::Error) -> Self {
        SevenZError::Io(error.to_string())
    }
}

/// 读满一块或读到头。`Ok(0)` = 这条读完了。
fn read_some(reader: &mut dyn std::io::Read, buf: &mut [u8]) -> Result<usize, SevenZError> {
    let mut filled = 0usize;
    while filled < buf.len() {
        match reader.read(&mut buf[filled..]) {
            Ok(0) => break,
            Ok(read) => filled += read,
            Err(error) if error.kind() == std::io::ErrorKind::Interrupted => continue,
            Err(error) => return Err(SevenZError::Compress(format!("读不出来：{error}"))),
        }
    }
    Ok(filled)
}

/// 签名头占的字节数（`7zFormat.txt` §2）。
const SIGNATURE_HEADER_SIZE: u64 = 32;

/// 签名头的字节数（`Vec::with_capacity` 要 `usize`）。
const SIGNATURE_HEADER_BYTES: usize = 32;

/// 流式读写的缓冲大小。64 KiB 是 `io::copy` 的默认值，对 LZMA2 也够——它内部还有
/// 自己的字典与分块，外层的块大小只影响 `read` 的调用次数。
const BUF: usize = 64 * 1024;

/// 名字只允许"相对路径"，不许绝对、不许 `..`、不许空、不许控制字符。
///
/// 这条不是洁癖：包解开之后名字就是盘上的路径，`../x` 会把文件写到目标目录外面去。
/// 导出时名字由调用方从盘上路径算出来，算错了在这里当场拒绝，比事后发现"导出包里
/// 有个奇怪的条目"便宜得多。
fn check_name(name: &str) -> Result<(), SevenZError> {
    let bad = name.is_empty()
        || name.starts_with('/')
        || name.starts_with('\\')
        || name.contains('\0')
        || name
            .split(['/', '\\'])
            .any(|part| part == ".." || part == ".")
        || name.chars().any(|ch| ch.is_control());
    if bad {
        return Err(SevenZError::UnsafeName(name.to_string()));
    }
    Ok(())
}

// ---------------------------------------------------------------------------
// 头部序列化
// ---------------------------------------------------------------------------

const K_END: u8 = 0x00;
const K_HEADER: u8 = 0x01;
const K_MAIN_STREAMS_INFO: u8 = 0x04;
const K_FILES_INFO: u8 = 0x05;
const K_PACK_INFO: u8 = 0x06;
const K_UNPACK_INFO: u8 = 0x07;
const K_SUBSTREAMS_INFO: u8 = 0x08;
const K_SIZE: u8 = 0x09;
const K_CRC: u8 = 0x0a;
const K_FOLDER: u8 = 0x0b;
const K_CODERS_UNPACK_SIZE: u8 = 0x0c;
const K_EMPTY_STREAM: u8 = 0x0e;
const K_EMPTY_FILE: u8 = 0x0f;
const K_NAME: u8 = 0x11;
const K_LZMA2: u8 = 0x21;

/// 7z 的 UINT64（`7zIn.cpp` 的 `ReadNumber`）。
///
/// **不是 LEB128**：首字节的高位是"后面还有几个字节"的标记，剩下的低位是数值的**高位**；
/// 后续字节按小端给出数值的低 8 位。写反了 7z 不会给你一句"格式不对"，它只报
/// `Headers Error`，然后所有条目都打不开——所以这里照着读的那一侧反推着写。
///
/// 编码规则（`i` = 后续字节数）：
/// - `i = 0`：一个字节，值 < 0x80；
/// - `i > 0`：首字节 = `i` 个 1 打头（`0xFF << (8-i)`）+ 数值的高位（占 `7-i` 位），
///   然后跟 `i` 个小端字节。
fn put_u64(out: &mut Vec<u8>, value: u64) {
    let mut extra = 0usize;
    while extra < 8 && (value >> (8 * extra)) >= (1u64 << (7 - extra)) {
        extra += 1;
    }
    if extra == 8 {
        out.push(0xff);
        out.extend_from_slice(&value.to_le_bytes());
        return;
    }
    let lead = if extra == 0 {
        0u8
    } else {
        ((0xffu16 << (8 - extra)) & 0xff) as u8
    };
    let high = (value >> (8 * extra)) as u8;
    out.push(lead | high);
    for index in 0..extra {
        out.push(((value >> (8 * index)) & 0xff) as u8);
    }
}

/// 位图：第 i 位对应第 i 个条目。
///
/// **7z 的位图是"高位在前"**（`CBitSet` 的写法）：第 0 个条目是首字节的 `0x80` 位，
/// 不是 `0x01`。这一条是拿 `7z` 打出来的包逐字节对出来的——写反了 7z 不报错，
/// 它只是把空文件当成目录，解出来是一堆空文件夹。
fn put_bits(out: &mut Vec<u8>, bits: &[bool]) {
    let mut byte = 0u8;
    let mut index = 0usize;
    for bit in bits {
        if *bit {
            byte |= 0x80 >> (index % 8);
        }
        index += 1;
        if index % 8 == 0 {
            out.push(byte);
            byte = 0;
        }
    }
    if index % 8 != 0 {
        out.push(byte);
    }
}

/// LZMA2 的字典属性字节（`7zFormat.txt` 里 LZMA2 的 props）。
///
/// 规范给的是一张表：`dict = (2 | (i & 1)) << (i / 2 + 11)`，`i = 40` 表示
/// `0xFFFFFFFF`。这里反查那张表，查不到就报错——**不取近似值**，取错了 7z 解不开，
/// 而"解不开"要到用户手里才发现。
fn lzma2_props(dict: u32) -> Option<u8> {
    if dict == u32::MAX {
        return Some(40);
    }
    for index in 0..40u32 {
        let size = (2 | (index & 1)) << (index / 2 + 11);
        if size == dict {
            return Some(index as u8);
        }
    }
    None
}

fn build_header(written: &[Written], pack_sizes: &[u64]) -> Result<Vec<u8>, SevenZError> {
    let props = lzma2_props(DICT_SIZE)
        .ok_or_else(|| SevenZError::Compress(format!("{DICT_SIZE} 不是合法的 LZMA2 字典大小")))?;
    // 有内容的那些条目各占一条 pack stream（非固实），顺序与 written 一致。
    let with_stream: Vec<usize> = (0..written.len())
        .filter(|index| pack_sizes[*index] > 0)
        .collect();

    let mut header = Vec::with_capacity(256);
    header.push(K_HEADER);

    // ---- MainStreamsInfo ----
    header.push(K_MAIN_STREAMS_INFO);
    if !with_stream.is_empty() {
        // PackInfo：位置 0（紧跟签名头）、每条流的压缩长度。
        header.push(K_PACK_INFO);
        put_u64(&mut header, 0);
        put_u64(&mut header, with_stream.len() as u64);
        header.push(K_SIZE);
        for index in &with_stream {
            put_u64(&mut header, pack_sizes[*index]);
        }
        header.push(K_END);

        // UnpackInfo：每个文件一条 folder，一条 coder（LZMA2）。
        header.push(K_UNPACK_INFO);
        header.push(K_FOLDER);
        put_u64(&mut header, with_stream.len() as u64);
        header.push(0); // External = 0：folder 定义就在这一段里
        for _ in &with_stream {
            put_u64(&mut header, 1); // NumCoders
                                     // coder 的 flags 字节：idSize=1（低四位）、0x20 位是"带属性"。
                                     // **这一字节不是方法 ID**——漏了它后面那个 0x21，7z 会报 Headers Error。
            header.push(0x21);
            header.push(K_LZMA2); // 方法 ID：LZMA2
            put_u64(&mut header, 1); // 属性长度
            header.push(props);
        }
        header.push(K_CODERS_UNPACK_SIZE);
        for index in &with_stream {
            put_u64(&mut header, written[*index].size);
        }
        header.push(K_END);

        // SubStreamsInfo：一条流一个子流，所以"每个子流的 CRC"就是那一段原文的 CRC。
        // 不写 NumUnpackStreams（默认每条流 1 个），也不写 Size（同上）——少两段就少两处
        // 可能算错的地方。
        header.push(K_SUBSTREAMS_INFO);
        header.push(K_CRC);
        header.push(1); // AllAreDefined
        for index in &with_stream {
            header.extend_from_slice(&written[*index].crc.to_le_bytes());
        }
        header.push(K_END);
    }
    header.push(K_END); // MainStreamsInfo 结束

    // ---- FilesInfo ----
    header.push(K_FILES_INFO);
    put_u64(&mut header, written.len() as u64);

    // 空流位图：没有内容的那几个（空文件）在这里标出来。全都有内容时这一段不写。
    let empty_stream: Vec<bool> = written.iter().map(|entry| entry.size == 0).collect();
    if empty_stream.iter().any(|empty| *empty) {
        header.push(K_EMPTY_STREAM);
        let mut bits = Vec::new();
        put_bits(&mut bits, &empty_stream);
        put_u64(&mut header, bits.len() as u64);
        header.extend_from_slice(&bits);
        // 空流里可能还有目录；这一版只写文件，所以它们**全都**是空文件。
        // （不写这一段的话 7z 会把它们当成目录，解出来是一堆空文件夹。）
        header.push(K_EMPTY_FILE);
        let mut bits = Vec::new();
        put_bits(
            &mut bits,
            &vec![true; empty_stream.iter().filter(|e| **e).count()],
        );
        put_u64(&mut header, bits.len() as u64);
        header.extend_from_slice(&bits);
    }

    // 名字：UTF-16LE，每条以 0x0000 结尾，整段前面带一个"外部标志"字节。
    header.push(K_NAME);
    let mut names = Vec::with_capacity(written.len() * 16);
    for entry in written {
        for unit in entry.name.encode_utf16() {
            names.extend_from_slice(&unit.to_le_bytes());
        }
        names.extend_from_slice(&[0, 0]);
    }
    put_u64(&mut header, (names.len() + 1) as u64);
    header.push(0); // External = 0
    header.extend_from_slice(&names);

    header.push(K_END); // FilesInfo 结束
    header.push(K_END); // Header 结束
    Ok(header)
}

/// CRC-32（IEEE，多项式 0xEDB88320）。
///
/// 与 `zip.rs` 里那份同一个算法，但那边的表是模块私有的：这里再建一张 256 项的静态表，
/// 比把 `zip` 的内部掏出来给两个格式共用便宜（共用就要把那张表提成 `pub`，
/// 而它跟 zip 的格式没有关系）。
fn crc32(data: &[u8]) -> u32 {
    crc32_update(0xffff_ffff, data) ^ 0xffff_ffff
}

/// 增量版：给流式写包用（一次只过一块缓冲，不能等整份文件读完再算）。
///
/// 入参是**未取反**的中间值：第一次传 `0xffff_ffff`，之后一路传上一块的结果，
/// 最后再 `^ 0xffff_ffff`。分开两步是为了不必把整份文件攒在内存里。
fn crc32_update(crc: u32, data: &[u8]) -> u32 {
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
    let mut crc = crc;
    for byte in data {
        crc = table[((crc ^ *byte as u32) & 0xff) as usize] ^ (crc >> 8);
    }
    crc
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 签名头的形状是外部契约：`7z` 认不认全看这 32 字节。
    #[test]
    fn signature_header_matches_the_spec() {
        let bytes = write(&[Entry::new("a.txt", b"hello".to_vec())]).unwrap();
        assert_eq!(&bytes[0..6], &[0x37, 0x7a, 0xbc, 0xaf, 0x27, 0x1c]);
        assert_eq!(&bytes[6..8], &[0x00, 0x04]);

        let start_crc = u32::from_le_bytes(bytes[8..12].try_into().unwrap());
        assert_eq!(
            start_crc,
            crc32(&bytes[12..32]),
            "StartHeaderCRC 覆盖后 20 字节"
        );

        let next_offset = u64::from_le_bytes(bytes[12..20].try_into().unwrap());
        let next_size = u64::from_le_bytes(bytes[20..28].try_into().unwrap());
        let next_crc = u32::from_le_bytes(bytes[28..32].try_into().unwrap());
        let header = &bytes[32 + next_offset as usize..];
        assert_eq!(header.len(), next_size as usize);
        assert_eq!(crc32(&header[..next_size as usize]), next_crc);
        // 头以 kHeader 开头、以两个 kEnd 收尾（MainStreamsInfo 与 Header 各一个）。
        assert_eq!(header[0], K_HEADER);
        assert_eq!(&header[header.len() - 2..], &[K_END, K_END]);
    }

    #[test]
    fn lzma2_props_covers_the_documented_table() {
        // 4 KiB 是表的第 0 项；8 MiB 是第 22 项；0xFFFFFFFF 是 40。
        assert_eq!(lzma2_props(4 << 10), Some(0));
        assert_eq!(lzma2_props(8 << 20), Some(22));
        assert_eq!(lzma2_props(u32::MAX), Some(40));
        // 表外的值一律拒绝，不取近似。
        assert_eq!(lzma2_props(3 << 10), None);
    }

    #[test]
    fn empty_archive_is_still_a_valid_header() {
        let bytes = write(&[]).unwrap();
        // 空包没有 pack stream：签名头 32 字节 + 头部。
        let next_size = u64::from_le_bytes(bytes[20..28].try_into().unwrap()) as usize;
        assert_eq!(bytes.len(), 32 + next_size);
        assert_eq!(u64::from_le_bytes(bytes[12..20].try_into().unwrap()), 0);
    }

    #[test]
    fn empty_file_gets_an_empty_stream_bit() {
        let bytes = write(&[
            Entry::new("a.txt", b"x".to_vec()),
            Entry::new("empty.txt", Vec::new()),
        ])
        .unwrap();
        // 头部里有 kEmptyStream 与 kEmptyFile 两段。
        let next_offset = u64::from_le_bytes(bytes[12..20].try_into().unwrap()) as usize;
        let header = &bytes[32 + next_offset..];
        assert!(header.contains(&K_EMPTY_STREAM));
        assert!(header.contains(&K_EMPTY_FILE));
    }

    #[test]
    fn unsafe_names_are_refused() {
        for name in ["", "/abs.txt", "../out.txt", "a/../../b", "a/./b", "a\0b"] {
            assert!(
                matches!(
                    write(&[Entry::new(name, b"x".to_vec())]),
                    Err(SevenZError::UnsafeName(_))
                ),
                "{name} 该被拒绝"
            );
        }
    }

    /// 7z 的 UINT64 不是 LEB128（D425）。这一条钉住编码形状：
    /// 首字节高位是"后面还有几个字节"，数值的**低位**在后续字节里（小端）。
    #[test]
    fn uint64_matches_the_sevenz_encoding() {
        /// 7z 的 `ReadNumber`（`7zIn.cpp`），照着实现一遍当参照。
        fn read(bytes: &[u8]) -> (u64, usize) {
            let first = bytes[0];
            let mut mask = 0x80u8;
            let mut value = 0u64;
            let mut used = 1usize;
            for index in 0..8 {
                if first & mask == 0 {
                    value |= ((first & (mask - 1)) as u64) << (8 * index);
                    return (value, used);
                }
                value |= (bytes[used] as u64) << (8 * index);
                used += 1;
                mask >>= 1;
            }
            (value, used)
        }

        let cases: [u64; 8] = [0, 1, 0x7f, 0x80, 0x3fff, 0x4000, 24000, 0xffff_ffff];
        for value in cases {
            let mut out = Vec::new();
            put_u64(&mut out, value);
            let (back, used) = read(&out);
            assert_eq!(back, value, "{value} 编成 {out:02x?} 读回来是 {back}");
            assert_eq!(
                used,
                out.len(),
                "{value} 编成 {out:02x?}，只用了 {used} 字节"
            );
        }

        // 形状也要钉：小值占一个字节，0x80 起才是"后面还有"。
        let mut out = Vec::new();
        put_u64(&mut out, 0x7f);
        assert_eq!(out, vec![0x7f]);
        let mut out = Vec::new();
        put_u64(&mut out, 0x80);
        assert_eq!(out, vec![0x80, 0x80], "0x80 = 首字节标记 + 低位 0x80");
        // 24000 = 0x5DC0：首字节 0xC0（两个后续字节、高位为 0），再跟 0xC0 0x5D。
        let mut out = Vec::new();
        put_u64(&mut out, 24000);
        assert_eq!(out, vec![0xc0, 0xc0, 0x5d]);
    }

    #[test]
    fn duplicate_names_are_refused() {
        let result = write(&[
            Entry::new("a.txt", b"1".to_vec()),
            Entry::new("a.txt", b"2".to_vec()),
        ]);
        assert!(matches!(result, Err(SevenZError::DuplicateName(_))));
    }

    /// 压缩是可逆的：同一份数据压完再解回来必须逐字节一样。
    ///
    /// 这一条钉的是"我们自己压的、我们自己能不能解开"——7z CLI 那一头由
    /// `scripts/check_sevenz.sh` 在 CI 里验（它才代表别人的解压器）。
    #[test]
    fn compressed_payload_round_trips_through_lzma2() {
        let body = b"hello world\n".repeat(500);
        let packed = compress_for_test(&body);
        assert!(
            packed.len() < body.len(),
            "{} >= {}",
            packed.len(),
            body.len()
        );
        let mut reader = lzma_rust::LZMA2Reader::new(&packed[..], DICT_SIZE, None);
        let mut out = Vec::new();
        std::io::Read::read_to_end(&mut reader, &mut out).unwrap();
        assert_eq!(out, body);
    }

    /// 流式写包：内容超过一块缓冲（64 KiB）时，跨块的 CRC 与长度都要对。
    ///
    /// 这一条是第一版最容易错的地方：CRC 要**增量**算（一块一块接上去），
    /// 而 `crc32()` 是"整段一次算完"。写错的表现是 7z 报 `Data Error`——
    /// 解出来的字节是对的，只有 CRC 不对，那种错看着像解压器坏了。
    #[test]
    fn streaming_write_handles_payloads_bigger_than_one_buffer() {
        // 300 KiB：跨 5 块。
        let body: Vec<u8> = (0..300 * 1024).map(|index| (index % 251) as u8).collect();
        let mut plan = vec![StreamEntry::from_bytes("big.bin", &body)];
        let mut out = std::io::Cursor::new(Vec::new());
        let mut ticks = 0usize;
        let written = write_to(&mut out, &mut plan, &mut |_| ticks += 1).unwrap();

        assert_eq!(written.len(), 1);
        assert_eq!(written[0].size, body.len() as u64);
        assert_eq!(written[0].crc, crc32(&body), "增量 CRC 与整段 CRC 必须一致");
        assert!(ticks > 1, "跨块时应该报过多次进度，实际 {ticks} 次");

        let bytes = out.into_inner();
        let next_offset = u64::from_le_bytes(bytes[12..20].try_into().unwrap()) as usize;
        assert!(next_offset > 0, "payload 不该是空的");
    }

    /// 进度回调的最后一个数必须是"全部做完"。
    #[test]
    fn progress_ends_at_the_total() {
        let a = b"aaaa".to_vec();
        let b = Vec::new();
        let mut plan = vec![
            StreamEntry::from_bytes("a.txt", &a),
            StreamEntry::from_bytes("b.txt", &b),
        ];
        let mut out = std::io::Cursor::new(Vec::new());
        let mut last: Option<Progress> = None;
        write_to(&mut out, &mut plan, &mut |p| last = Some(*p)).unwrap();
        let last = last.expect("至少报一次进度");
        assert_eq!(last.entries_done, 2);
        assert_eq!(last.entries_total, 2);
        assert_eq!(last.bytes_in, 4);
    }

    /// 测试里用的一份压缩（内存版），与 `write` 走同一条编码路径。
    fn compress_for_test(data: &[u8]) -> Vec<u8> {
        let mut out = Vec::new();
        {
            let counter = lzma_rust::CountingWriter::new(&mut out);
            let options = lzma_rust::LZMA2Options::with_preset(5);
            let mut writer = lzma_rust::LZMA2Writer::new(counter, &options);
            std::io::Write::write_all(&mut writer, data).unwrap();
            writer.finish().unwrap();
        }
        out
    }
}
