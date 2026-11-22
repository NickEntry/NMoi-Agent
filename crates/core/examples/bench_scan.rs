use std::path::Path;
use std::time::Instant;

fn scan(root: &Path) -> usize {
    let mut n = 0usize;
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(entries) = std::fs::read_dir(&dir) else {
            continue;
        };
        for entry in entries.flatten() {
            let Ok(meta) = entry.metadata() else { continue };
            if meta.is_dir() {
                let name = entry.file_name();
                let name = name.to_string_lossy();
                if name.starts_with('.')
                    || matches!(
                        name.as_ref(),
                        "target" | "node_modules" | "build" | ".git" | ".gradle"
                    )
                {
                    continue;
                }
                stack.push(entry.path());
            } else {
                n += 1;
            }
        }
    }
    n
}

fn bench(label: &str, root: &Path, times: usize) {
    let mut total = 0u128;
    let mut files = 0;
    for _ in 0..times {
        let t = Instant::now();
        files = scan(root);
        total += t.elapsed().as_micros();
    }
    println!(
        "{label}: {files} files, avg {:.1} ms over {times} runs",
        total as f64 / times as f64 / 1000.0
    );
}

fn main() {
    let args: Vec<String> = std::env::args().collect();
    let root = args.get(1).map(|s| s.as_str()).unwrap_or(".");
    let times: usize = args.get(2).and_then(|s| s.parse().ok()).unwrap_or(5);
    bench(root, Path::new(root), times);

    // 模拟 N 个会话各自扫一次 vs 共享一次
    for sessions in [1usize, 2, 4, 8] {
        let t = Instant::now();
        for _ in 0..sessions {
            scan(Path::new(root));
        }
        let each = t.elapsed().as_millis();
        println!("  {sessions} 个会话各自扫: {each} ms");
    }
    let t = Instant::now();
    scan(Path::new(root));
    println!("  共享一次: {} ms", t.elapsed().as_millis());
}
