//! 打印若干文本文件的 token 估算（D91 的 L0 / D94 的 L1）。
//!
//! 对账用：拿同一段文本去问真端点，比对 `usage.prompt_tokens`。
//!
//! ```text
//! cargo run -q -p nmoi-core --example token_estimate -- /tmp/a.txt /tmp/b.txt
//! cargo run -q -p nmoi-core --example token_estimate -- --model gpt-4 /tmp/a.txt
//! # 输出：路径 \t 字符数 \t 估算 token \t 级别 \t 内容类
//! ```
//!
//! 估算走的是运行时同一份实现（`tokens::Estimator`），不在脚本里重写——
//! 两份实现迟早分叉，那样对出来的误差是假的。

use std::env;

use nmoi_core::tokens::Estimator;

fn main() {
    let mut model = String::from("relay-a/DeepSeek-V4-Flash");
    let mut paths: Vec<String> = Vec::new();
    let mut args = env::args().skip(1);
    while let Some(arg) = args.next() {
        if arg == "--model" {
            match args.next() {
                Some(value) => model = value,
                None => {
                    eprintln!("--model 后面要跟一个模型名");
                    std::process::exit(2);
                }
            }
        } else {
            paths.push(arg);
        }
    }
    if paths.is_empty() {
        eprintln!("用法：token_estimate [--model M] <文件>…");
        std::process::exit(2);
    }

    let estimator = Estimator::new(&model, &[]);
    for path in paths {
        match std::fs::read_to_string(&path) {
            Ok(text) => {
                let estimate = estimator.estimate(&text);
                println!(
                    "{}\t{}\t{}\t{}\t{}",
                    path,
                    text.chars().count(),
                    estimate.raw,
                    estimate.level.as_str(),
                    estimate.class.as_str(),
                );
            }
            Err(error) => println!("{}\t读取失败\t{}\t-\t-", path, error),
        }
    }
}
