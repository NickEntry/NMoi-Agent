//! 造一个 7z 样本，给 `scripts/check_sevenz.sh` 用（D425）。
//!
//! 四份内容各代表一类：重复文本（压得动）、二进制（压不动）、空文件（走 empty-stream）、
//! 中文路径（走 UTF-16 名字段）。少一样就少一条判据。
fn main() {
    let out = std::env::args().nth(1).expect("用法：dump7z <输出路径>");
    let bytes = nmoi_core::sevenz::write(&[
        nmoi_core::sevenz::Entry::new("a.txt", "hello world\n".repeat(2000).into_bytes()),
        nmoi_core::sevenz::Entry::new(
            "dir/b.bin",
            (0u8..=255).cycle().take(5000).collect::<Vec<u8>>(),
        ),
        nmoi_core::sevenz::Entry::new("empty.txt", Vec::new()),
        nmoi_core::sevenz::Entry::new("中文/报告.md", "# 标题\n正文\n".as_bytes().to_vec()),
    ])
    .unwrap();
    std::fs::write(&out, &bytes).unwrap();
    println!("wrote {out} {} 字节", bytes.len());
}
