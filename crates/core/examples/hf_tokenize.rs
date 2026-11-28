//! 对账工具：拿一份 `tokenizer.json` 把一段文本切成 id，打到 stdout（逗号分隔）。
//!
//! 用法：
//!
//! ```text
//! cargo run -q -p nmoi-core --example hf_tokenize -- /tmp/ds.json "hello 你好"
//! cat text.txt | cargo run -q -p nmoi-core --example hf_tokenize -- /tmp/ds.json -
//! ```
//!
//! 存在的理由只有一个：**与 Python `tokenizers` 的输出逐 id 比对**。
//! "两个估算值差 3%"这种判据在这里不够——分词差一个就是差一个。

use std::io::Read;

fn main() {
    let mut args = std::env::args().skip(1);
    let Some(path) = args.next() else {
        eprintln!("用法：hf_tokenize <tokenizer.json> <文本 | ->");
        std::process::exit(2);
    };
    let text = match args.next().as_deref() {
        Some("-") | None => {
            let mut buf = String::new();
            std::io::stdin().read_to_string(&mut buf).expect("读 stdin");
            buf
        }
        Some(value) => value.to_string(),
    };

    let raw = std::fs::read_to_string(path).expect("读词表");
    let started = std::time::Instant::now();
    let tokenizer = nmoi_core::tokens::hf::HfTokenizer::from_json(&raw).expect("解析词表");
    let parsed = started.elapsed();

    let started = std::time::Instant::now();
    let ids = tokenizer.encode(&text);
    let encoded = started.elapsed();

    eprintln!(
        "kind={:?} vocab={} 解析 {} ms · 编码 {} ms · {} token",
        tokenizer.kind(),
        tokenizer.vocab_size(),
        parsed.as_millis(),
        encoded.as_millis(),
        ids.len()
    );
    let line = ids
        .iter()
        .map(|id| id.to_string())
        .collect::<Vec<_>>()
        .join(",");
    println!("{line}");
}
