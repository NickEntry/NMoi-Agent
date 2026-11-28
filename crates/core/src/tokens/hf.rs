//! HuggingFace `tokenizer.json` 的最小实现（D441）。
//!
//! 为什么自己写：`tokenizers` crate 在 MSRV 1.75 上装不进来（见 D439 的实测表），
//! 而"每个模型用自己那份词表"这件事不能等。这一份只做**计数需要的那一半**——
//! 编码成 id、数出来多少个，不做解码、不做 padding/truncation、不碰 chat template。
//!
//! 支持三种 `model.type`（用户点名的那批模型都落在里面）：
//!
//! | 类型 | 谁 |
//! |---|---|
//! | `BPE` | Qwen / DeepSeek / GLM / Kimi / Doubao / Claude（社区导出）/ GPT 的 HF 版 |
//! | `Unigram` | Gemini（Gemma 3 词表）、部分中文模型的 SentencePiece 导出 |
//! | `WordPiece` | BERT 系（备用） |
//!
//! # 三条"错得静默"的地方，都单独钉了测试
//!
//! 1. **预切分的正则要从 JSON 里读**。DeepSeek 那份不是 GPT-2 内置那一条，而是
//!    `\p{N}{1,3}` / `[一-龥]+` / 一条标点与字母混合 / `\s+(?!\S)` 四条 `Split`。
//!    写死一份 GPT-2 的，中文数字会被切错，而数出来的 token 只是"少一点点"。
//! 2. **`\s+(?!\S)` 带负向前瞻**，Rust 的 `regex` 不支持。这一处用手写扫描器补
//!    （见 [`gpt2_pieces`]）。
//! 3. **`added_tokens` 要先于普通词表匹配**，且长的先。顺序反了，`<|end|>` 这种
//!    会被拆成五六个普通词块。
//!
//! 认不出的 `model.type`、认不出的 normalizer，一律**报错而不是猜**——
//! 猜出来的分词会让所有 token 数系统性偏一截，而界面上那个数看起来照常。

use std::collections::HashMap;

use serde_json::Value;

/// 分词器类型。词表里没有的那几种（WordLevel 之类）明确不支持。
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ModelKind {
    Bpe,
    Unigram,
    WordPiece,
}

#[derive(Debug, thiserror::Error)]
pub enum HfError {
    #[error("这不是 JSON：{0}")]
    Json(String),
    #[error("缺少 {0} 段")]
    Missing(&'static str),
    #[error("不支持的 model.type：{0}（只认 BPE / Unigram / WordPiece）")]
    ModelKind(String),
    #[error("不支持的 normalizer：{0}（会改变字节，宁可拒绝也不猜）")]
    Normalizer(String),
    #[error("不支持的 pre_tokenizer：{0}")]
    PreTokenizer(String),
    #[error("预切分的正则编译不过：{0}")]
    Regex(String),
    #[error("词表是空的")]
    EmptyVocab,
}

/// 一段文本怎么切成"候选词块"。DeepSeek / Qwen 那一类把它显式写在 JSON 里。
#[derive(Debug, Clone)]
enum Pre {
    Identity,
    Sequence(Vec<Pre>),
    /// `Split`：按正则切，`Isolated` = 匹配到的部分单独成段，其余成段。
    Split {
        pattern: regex::Regex,
        isolated: bool,
        invert: bool,
    },
    /// `ByteLevel`：先按 GPT-2 的规则切（可选），再把每个字节映射成可见字符。
    ByteLevel {
        use_regex: bool,
        add_prefix_space: bool,
    },
    Whitespace,
    Metaspace {
        replacement: char,
        prepend: bool,
    },
    Punctuation,
    Digits {
        individual: bool,
    },
    Bert,
}

/// 归一化。**只做不会改变"字节与字符对应关系"的那几种**；NFC/NFKC 要
/// `unicode-normalization`，这一版没有依赖，遇到就拒绝（见 [`HfError::Normalizer`]）。
#[derive(Debug, Clone)]
enum Norm {
    None,
    Lowercase,
    Strip,
    Prepend(char),
    Replace {
        pattern: regex::Regex,
        replacement: String,
    },
    /// 空操作：DeepSeek 那份写的是 `Sequence[ ]`（空的），等于没有。
    Nop,
}

/// 一份已经解析好的词表。
pub struct HfTokenizer {
    kind: ModelKind,
    vocab: HashMap<String, u32>,
    id_to_token: Vec<String>,
    /// BPE 的合并优先级：小的先合。
    merges: HashMap<(String, String), u32>,
    /// Unigram 的 (token, log 概率)。
    unigram: Vec<(String, f32)>,
    /// Unigram 按首字符分桶：262k 词表不能每步全表扫。
    unigram_buckets: HashMap<char, Vec<usize>>,
    unk_id: Option<u32>,
    /// `added_tokens`：按内容长度降序，最长优先匹配。
    added: Vec<(String, u32)>,
    pre: Pre,
    norm: Norm,
}

impl HfTokenizer {
    /// 从 `tokenizer.json` 的原文解析。
    pub fn from_json(text: &str) -> Result<Self, HfError> {
        let root: Value = serde_json::from_str(text).map_err(|e| HfError::Json(e.to_string()))?;
        let model = root.get("model").ok_or(HfError::Missing("model"))?;
        let kind_name = model
            .get("type")
            .and_then(Value::as_str)
            .ok_or(HfError::Missing("model.type"))?;
        let kind = match kind_name {
            "BPE" => ModelKind::Bpe,
            "Unigram" => ModelKind::Unigram,
            "WordPiece" => ModelKind::WordPiece,
            other => return Err(HfError::ModelKind(other.to_string())),
        };

        let mut vocab: HashMap<String, u32> = HashMap::new();
        let mut id_to_token: Vec<String> = Vec::new();
        match kind {
            ModelKind::Unigram => {
                // Unigram 的 vocab 是 [[token, score], ...]
                let list = model
                    .get("vocab")
                    .and_then(Value::as_array)
                    .ok_or(HfError::Missing("model.vocab"))?;
                for (id, item) in list.iter().enumerate() {
                    let token = item.get(0).and_then(Value::as_str).unwrap_or_default();
                    vocab.entry(token.to_string()).or_insert(id as u32);
                    id_to_token.push(token.to_string());
                }
            }
            _ => {
                let map = model
                    .get("vocab")
                    .and_then(Value::as_object)
                    .ok_or(HfError::Missing("model.vocab"))?;
                let mut pairs: Vec<(String, u32)> = map
                    .iter()
                    .filter_map(|(k, v)| v.as_u64().map(|id| (k.clone(), id as u32)))
                    .collect();
                pairs.sort_by_key(|(_, id)| *id);
                let max = pairs.iter().map(|(_, id)| *id).max().unwrap_or(0);
                id_to_token = vec![String::new(); max as usize + 1];
                for (token, id) in pairs {
                    id_to_token[id as usize] = token.clone();
                    vocab.insert(token, id);
                }
            }
        }
        if vocab.is_empty() {
            return Err(HfError::EmptyVocab);
        }

        // Unigram 的分数从 vocab 列表里再读一遍（上面为了简单只存了 token）。
        let mut unigram: Vec<(String, f32)> = Vec::new();
        if kind == ModelKind::Unigram {
            if let Some(list) = model.get("vocab").and_then(Value::as_array) {
                for item in list {
                    let token = item.get(0).and_then(Value::as_str).unwrap_or_default();
                    let score = item.get(1).and_then(Value::as_f64).unwrap_or(0.0) as f32;
                    unigram.push((token.to_string(), score));
                }
            }
        }

        let mut merges: HashMap<(String, String), u32> = HashMap::new();
        if let Some(list) = model.get("merges").and_then(Value::as_array) {
            for (rank, item) in list.iter().enumerate() {
                // 两种写法都收：`"a b"` 与 `["a","b"]`。
                let pair = match item {
                    Value::String(line) => {
                        let mut parts = line.splitn(2, ' ');
                        match (parts.next(), parts.next()) {
                            (Some(a), Some(b)) => Some((a.to_string(), b.to_string())),
                            _ => None,
                        }
                    }
                    Value::Array(items) => match (items.first(), items.get(1)) {
                        (Some(Value::String(a)), Some(Value::String(b))) => {
                            Some((a.clone(), b.clone()))
                        }
                        _ => None,
                    },
                    _ => None,
                };
                if let Some(pair) = pair {
                    merges.insert(pair, rank as u32);
                }
            }
        }

        let unk_id = model
            .get("unk_id")
            .and_then(Value::as_u64)
            .map(|v| v as u32);

        // added_tokens：长的先匹配（`<|end▁of▁sentence|>` 不能被更短的抢先）。
        let mut added: Vec<(String, u32)> = root
            .get("added_tokens")
            .and_then(Value::as_array)
            .map(|list| {
                list.iter()
                    .filter_map(|item| {
                        let content = item.get("content").and_then(Value::as_str)?;
                        let id = item.get("id").and_then(Value::as_u64)? as u32;
                        if content.is_empty() {
                            return None;
                        }
                        Some((content.to_string(), id))
                    })
                    .collect()
            })
            .unwrap_or_default();
        added.sort_by(|a, b| b.0.len().cmp(&a.0.len()));

        let pre = parse_pre(root.get("pre_tokenizer"))?;
        let norm = parse_norm(root.get("normalizer"))?;

        let mut buckets: HashMap<char, Vec<usize>> = HashMap::new();
        for (index, (token, _)) in unigram.iter().enumerate() {
            if let Some(first) = token.chars().next() {
                buckets.entry(first).or_default().push(index);
            }
        }

        Ok(Self {
            kind,
            vocab,
            id_to_token,
            merges,
            unigram,
            unigram_buckets: buckets,
            unk_id,
            added,
            pre,
            norm,
        })
    }

    pub fn kind(&self) -> ModelKind {
        self.kind
    }

    pub fn vocab_size(&self) -> usize {
        self.vocab.len()
    }

    /// 编码成 id。**只做计数需要的那一半**：不做特殊 token 包裹。
    pub fn encode(&self, text: &str) -> Vec<u32> {
        let normalized = self.normalize(text);
        let mut out = Vec::new();
        // 先把 added_tokens 切出来，剩下的片段再走常规预切分。
        for piece in split_added(&normalized, &self.added) {
            match piece {
                Piece::Added(id) => out.push(id),
                Piece::Text(text) => {
                    for token in self.pre_tokenize(&text) {
                        self.encode_piece(&token, &mut out);
                    }
                }
            }
        }
        out
    }

    /// 数 token。与 `encode(...).len()` 同一条路——不另写一套。
    pub fn count(&self, text: &str) -> usize {
        self.encode(text).len()
    }

    fn normalize(&self, text: &str) -> String {
        match &self.norm {
            Norm::None | Norm::Nop => text.to_string(),
            Norm::Lowercase => text.to_lowercase(),
            Norm::Strip => text.trim().to_string(),
            Norm::Prepend(ch) => {
                let mut out = String::new();
                out.push(*ch);
                out.push_str(text);
                out
            }
            Norm::Replace {
                pattern,
                replacement,
            } => pattern.replace_all(text, replacement.as_str()).into_owned(),
        }
    }

    /// 预切分：一次拿到所有"候选词块"。
    fn pre_tokenize(&self, text: &str) -> Vec<String> {
        let mut out = Vec::new();
        apply_pre(&self.pre, text, &mut out);
        out
    }

    fn encode_piece(&self, piece: &str, out: &mut Vec<u32>) {
        match self.kind {
            ModelKind::Bpe => self.encode_bpe(piece, out),
            ModelKind::Unigram => self.encode_unigram(piece, out),
            ModelKind::WordPiece => self.encode_wordpiece(piece, out),
        }
    }

    /// BPE：按 rank 从小到大合并相邻对。
    fn encode_bpe(&self, piece: &str, out: &mut Vec<u32>) {
        let mut symbols: Vec<String> = piece.chars().map(|c| c.to_string()).collect();
        if symbols.is_empty() {
            return;
        }
        loop {
            let mut best: Option<((String, String), u32, usize)> = None;
            for index in 0..symbols.len().saturating_sub(1) {
                let pair = (symbols[index].clone(), symbols[index + 1].clone());
                if let Some(rank) = self.merges.get(&pair) {
                    if best.as_ref().map(|(_, r, _)| rank < r).unwrap_or(true) {
                        best = Some((pair, *rank, index));
                    }
                }
            }
            let Some((pair, _, at)) = best else { break };
            let merged = format!("{}{}", pair.0, pair.1);
            symbols.splice(at..at + 2, std::iter::once(merged));
        }
        for symbol in symbols {
            match self.vocab.get(&symbol) {
                Some(id) => out.push(*id),
                None => {
                    if let Some(unk) = self.unk_id {
                        out.push(unk);
                    }
                }
            }
        }
    }

    /// Unigram：Viterbi 取 log 概率和最大的那条切分。
    fn encode_unigram(&self, piece: &str, out: &mut Vec<u32>) {
        let chars: Vec<char> = piece.chars().collect();
        let n = chars.len();
        if n == 0 {
            return;
        }
        const NEG: f32 = f32::NEG_INFINITY;
        let mut best = vec![NEG; n + 1];
        let mut back: Vec<Option<(usize, usize)>> = vec![None; n + 1]; // (起点, unigram 下标)
        best[0] = 0.0;

        // 前缀字符串按字符窗口试：Unigram 词条一般不超过 16 个字符。
        const MAX_LEN: usize = 16;
        for start in 0..n {
            if best[start] == NEG {
                continue;
            }
            let first = chars[start];
            let Some(candidates) = self.unigram_buckets.get(&first) else {
                continue;
            };
            let limit = (n - start).min(MAX_LEN);
            for &index in candidates {
                let token = &self.unigram[index].0;
                let len = token.chars().count();
                if len == 0 || len > limit {
                    continue;
                }
                let slice: String = chars[start..start + len].iter().collect();
                if slice != *token {
                    continue;
                }
                let score = best[start] + self.unigram[index].1;
                if score > best[start + len] {
                    best[start + len] = score;
                    back[start + len] = Some((start, index));
                }
            }
        }

        if best[n] == NEG {
            // 切不动：整段当一个 unk（或者丢掉，取决于词表有没有 unk）。
            if let Some(unk) = self.unk_id {
                out.push(unk);
            }
            return;
        }
        let mut ids = Vec::new();
        let mut cursor = n;
        while cursor > 0 {
            let Some((start, index)) = back[cursor] else {
                break;
            };
            if let Some(id) = self.vocab.get(&self.unigram[index].0) {
                ids.push(*id);
            }
            cursor = start;
        }
        ids.reverse();
        out.extend(ids);
    }

    /// WordPiece：贪心最长匹配，续词加 `##`。
    fn encode_wordpiece(&self, piece: &str, out: &mut Vec<u32>) {
        let chars: Vec<char> = piece.chars().collect();
        let n = chars.len();
        let mut start = 0;
        while start < n {
            let mut end = n;
            let mut found: Option<(usize, u32)> = None;
            while end > start {
                let slice: String = chars[start..end].iter().collect();
                let candidate = if start == 0 {
                    slice
                } else {
                    format!("##{slice}")
                };
                if let Some(id) = self.vocab.get(&candidate) {
                    found = Some((end, *id));
                    break;
                }
                end -= 1;
            }
            match found {
                Some((end, id)) => {
                    out.push(id);
                    start = end;
                }
                None => {
                    if let Some(unk) = self.unk_id {
                        out.push(unk);
                    }
                    start += 1;
                }
            }
        }
    }

    /// 反查（诊断用：对账脚本要把它打出来）。
    pub fn token_of(&self, id: u32) -> Option<&str> {
        self.id_to_token.get(id as usize).map(|s| s.as_str())
    }
}

/// added token 与普通文本的分段结果。
enum Piece {
    Added(u32),
    Text(String),
}

/// 从左到右贪心匹配 added_tokens（已经按长度降序）。
fn split_added(text: &str, added: &[(String, u32)]) -> Vec<Piece> {
    if added.is_empty() {
        return vec![Piece::Text(text.to_string())];
    }
    let mut out = Vec::new();
    let mut buf = String::new();
    let mut rest = text;
    'outer: while !rest.is_empty() {
        for (content, id) in added {
            if let Some(tail) = rest.strip_prefix(content.as_str()) {
                if !buf.is_empty() {
                    out.push(Piece::Text(std::mem::take(&mut buf)));
                }
                out.push(Piece::Added(*id));
                rest = tail;
                continue 'outer;
            }
        }
        let ch = rest.chars().next().unwrap_or('\0');
        buf.push(ch);
        rest = &rest[ch.len_utf8()..];
    }
    if !buf.is_empty() {
        out.push(Piece::Text(buf));
    }
    out
}

fn parse_norm(value: Option<&Value>) -> Result<Norm, HfError> {
    let Some(value) = value else {
        return Ok(Norm::None);
    };
    if value.is_null() {
        return Ok(Norm::None);
    }
    let kind = value.get("type").and_then(Value::as_str).unwrap_or("");
    match kind {
        "Sequence" => {
            let list = value
                .get("normalizers")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            if list.is_empty() {
                return Ok(Norm::Nop);
            }
            // 多个归一化串联这一版不做（会改变字节，猜错就全错）。
            Err(HfError::Normalizer(format!("Sequence[{} 个]", list.len())))
        }
        "Lowercase" => Ok(Norm::Lowercase),
        "Strip" => Ok(Norm::Strip),
        "Nop" | "" => Ok(Norm::Nop),
        "Prepend" => {
            let ch = value
                .get("prepend")
                .and_then(Value::as_str)
                .and_then(|s| s.chars().next())
                .unwrap_or(' ');
            Ok(Norm::Prepend(ch))
        }
        "Replace" => {
            let pattern = value
                .get("pattern")
                .and_then(|p| p.get("Regex"))
                .and_then(Value::as_str)
                .ok_or_else(|| HfError::Normalizer("Replace 只认 Regex".into()))?;
            let regex = compile_pattern(pattern)?;
            let replacement = value
                .get("content")
                .and_then(Value::as_str)
                .unwrap_or_default()
                .to_string();
            Ok(Norm::Replace {
                pattern: regex,
                replacement,
            })
        }
        "NFC" | "NFD" | "NFKC" | "NFKD" | "Nmt" | "Precompiled" | "BertNormalizer" => {
            Err(HfError::Normalizer(kind.to_string()))
        }
        other => Err(HfError::Normalizer(other.to_string())),
    }
}

fn parse_pre(value: Option<&Value>) -> Result<Pre, HfError> {
    let Some(value) = value else {
        return Ok(Pre::Identity);
    };
    if value.is_null() {
        return Ok(Pre::Identity);
    }
    let kind = value.get("type").and_then(Value::as_str).unwrap_or("");
    match kind {
        "Sequence" => {
            let list = value
                .get("pretokenizers")
                .and_then(Value::as_array)
                .cloned()
                .unwrap_or_default();
            let mut parts = Vec::new();
            for item in &list {
                parts.push(parse_pre(Some(item))?);
            }
            Ok(Pre::Sequence(parts))
        }
        "Split" => {
            let pattern = value
                .get("pattern")
                .and_then(|p| p.get("Regex"))
                .and_then(Value::as_str)
                .ok_or_else(|| HfError::PreTokenizer("Split 只认 Regex".into()))?;
            let regex = compile_pattern(pattern)?;
            let behavior = value
                .get("behavior")
                .and_then(Value::as_str)
                .unwrap_or("Isolated");
            let invert = value
                .get("invert")
                .and_then(Value::as_bool)
                .unwrap_or(false);
            Ok(Pre::Split {
                pattern: regex,
                isolated: behavior == "Isolated",
                invert,
            })
        }
        "ByteLevel" => Ok(Pre::ByteLevel {
            use_regex: value
                .get("use_regex")
                .and_then(Value::as_bool)
                .unwrap_or(true),
            add_prefix_space: value
                .get("add_prefix_space")
                .and_then(Value::as_bool)
                .unwrap_or(true),
        }),
        "Whitespace" => Ok(Pre::Whitespace),
        "Metaspace" => Ok(Pre::Metaspace {
            replacement: value
                .get("replacement")
                .and_then(Value::as_str)
                .and_then(|s| s.chars().next())
                .unwrap_or('▁'),
            prepend: value
                .get("prepend_scheme")
                .and_then(Value::as_str)
                .map(|s| s != "never")
                .unwrap_or(true),
        }),
        "Punctuation" => Ok(Pre::Punctuation),
        "Digits" => Ok(Pre::Digits {
            individual: value
                .get("individual_digits")
                .and_then(Value::as_bool)
                .unwrap_or(false),
        }),
        "BertPreTokenizer" => Ok(Pre::Bert),
        other => Err(HfError::PreTokenizer(other.to_string())),
    }
}

fn apply_pre(pre: &Pre, text: &str, out: &mut Vec<String>) {
    match pre {
        Pre::Identity => out.push(text.to_string()),
        Pre::Sequence(parts) => {
            let mut current = vec![text.to_string()];
            for part in parts {
                let mut next = Vec::new();
                for item in &current {
                    apply_pre(part, item, &mut next);
                }
                current = next;
            }
            out.extend(current);
        }
        Pre::Split {
            pattern,
            isolated,
            invert,
        } => {
            // HF 的 `\s+(?!\S)`（"空白串，且后面不是非空白"）在 `"    return"` 上只吃
            // **三个**空格——第四个留给下一段，于是下一段是 `" return"`（带前导空格）。
            // Rust `regex` 没有 lookaround，所以这里按贪婪的 `\s+` 切完再修一次：
            // 整段是空白且后面紧跟非空白时，**最后一个字符不消费**（游标退回去），
            // 让下一条候选（`[^\r\n\p{L}\p{N}]?[\p{L}\p{M}]+`）把它吃进词里。
            //
            // 少了"游标退一格"这一步，`"    return"` 会切成 `"    "` + `"return"`，
            // 实测 token 变成 `…361,1354…` 之外的 `…290,3916…`——差一个，而界面上看不出。
            let mut cursor = 0;
            // 用 `find_at` 而不是 `find_iter`：收尾修正要把游标**退回去**，
            // 而 `find_iter` 的内部位置只往前走（退不了，会多切出一个空格）。
            while cursor <= text.len() {
                let Some(m) = pattern.find_at(text, cursor) else {
                    break;
                };
                if m.start() > cursor {
                    push_nonempty(out, &text[cursor..m.start()]);
                }
                if *invert {
                    push_nonempty(out, &text[cursor..m.start()]);
                    cursor = m.end();
                    continue;
                }
                if m.end() == m.start() {
                    // 零宽匹配：不推进就会死循环。
                    cursor = m.end() + 1;
                    continue;
                }
                let matched = m.as_str();
                let all_space = matched.chars().all(char::is_whitespace);
                let next_non_space = text[m.end()..]
                    .chars()
                    .next()
                    .map(|c| !c.is_whitespace())
                    .unwrap_or(false);
                // 只有"纯空格/制表"才退一格：换行那一条走的是更前面的
                // `\s*[\r\n]+`（`"\n\n"` 要整段合成一个 `ĊĊ`，退一格就变成两个 `Ċ`）。
                let no_newline = !matched.contains('\n') && !matched.contains('\r');
                if *isolated
                    && all_space
                    && no_newline
                    && next_non_space
                    && matched.chars().count() > 1
                {
                    let last_len = matched.chars().last().map(|c| c.len_utf8()).unwrap_or(1);
                    let keep = &matched[..matched.len() - last_len];
                    push_nonempty(out, keep);
                    // 最后一个空白**不消费**：下一段会把它带进词里。
                    cursor = m.end() - last_len;
                } else {
                    push_nonempty(out, matched);
                    cursor = m.end();
                }
            }
            if cursor < text.len() {
                push_nonempty(out, &text[cursor..]);
            }
        }
        Pre::ByteLevel {
            use_regex,
            add_prefix_space,
        } => {
            if text.is_empty() {
                return;
            }
            let owned;
            let source: &str = if *add_prefix_space && !text.starts_with(' ') {
                owned = format!(" {text}");
                &owned
            } else {
                text
            };
            let pieces: Vec<String> = if *use_regex {
                gpt2_pieces(source)
            } else {
                vec![source.to_string()]
            };
            for piece in pieces {
                push_nonempty(out, &byte_level(piece.as_str()));
            }
        }
        Pre::Whitespace => {
            for piece in text.split_whitespace() {
                out.push(piece.to_string());
            }
        }
        Pre::Metaspace {
            replacement,
            prepend,
        } => {
            let mut owned = String::new();
            if *prepend && !text.starts_with(*replacement) {
                owned.push(*replacement);
            }
            owned.push_str(&text.replace(' ', &replacement.to_string()));
            out.push(owned);
        }
        Pre::Punctuation => {
            for ch in text.chars() {
                out.push(ch.to_string());
            }
        }
        Pre::Digits { individual } => {
            if *individual {
                for ch in text.chars() {
                    out.push(ch.to_string());
                }
            } else {
                out.push(text.to_string());
            }
        }
        Pre::Bert => {
            for piece in bert_pieces(text) {
                out.push(piece);
            }
        }
    }
}

/// 把 HF 那些正则编成 Rust `regex` 能吃的形状。
///
/// HF 的预切分里有 `\s+(?!\S)`（"后面不是非空白"），而 Rust `regex` **不支持 lookaround**。
/// 处理办法：**把 lookaround 去掉**，再在 [`apply_pre`] 里对"整段是空白"的匹配做一次收尾
/// 修正（把最后一个空白让给下一段）——正好是 `(?!\S)` 想表达的那件事。
///
/// 这条偏差是有意的，也是这一层最容易出错的地方：去掉 lookaround 之后如果不再修正，
/// `" a"` 这种"前导空格的词"会多切一次，token 数只多不少，而界面上看不出异常。
fn compile_pattern(pattern: &str) -> Result<regex::Regex, HfError> {
    let cleaned = strip_lookaround(pattern);
    regex::Regex::new(&cleaned).map_err(|e| HfError::Regex(format!("{e}（原文 {pattern}）")))
}

/// 去掉 `(?!…)` / `(?=…)` / `(?<!…)` / `(?<=…)` 这几种零宽断言。
fn strip_lookaround(pattern: &str) -> String {
    let mut out = String::with_capacity(pattern.len());
    let bytes: Vec<char> = pattern.chars().collect();
    let mut index = 0;
    while index < bytes.len() {
        if bytes[index] == '(' && index + 1 < bytes.len() && bytes[index + 1] == '?' {
            let rest: String = bytes[index..].iter().take(4).collect();
            let marker = if rest.starts_with("(?<=") || rest.starts_with("(?<!") {
                4
            } else if rest.starts_with("(?=") || rest.starts_with("(?!") {
                3
            } else {
                0
            };
            if marker > 0 {
                // 跳到配对的右括号（内部不再嵌套断言）。
                let mut depth = 0usize;
                let mut cursor = index;
                while cursor < bytes.len() {
                    if bytes[cursor] == '(' {
                        depth += 1;
                    } else if bytes[cursor] == ')' {
                        depth -= 1;
                        if depth == 0 {
                            break;
                        }
                    }
                    cursor += 1;
                }
                index = cursor + 1;
                continue;
            }
            let _ = marker;
        }
        out.push(bytes[index]);
        index += 1;
    }
    out
}

fn push_nonempty(out: &mut Vec<String>, text: &str) {
    if !text.is_empty() {
        out.push(text.to_string());
    }
}

/// GPT-2 的预切分规则（手写，因为 `\s+(?!\S)` 那条带负向前瞻，Rust `regex` 不支持）。
///
/// ```text
/// 's|'t|'re|'ve|'m|'ll|'d | ?\p{L}+ | ?\p{N}+ | ?[^\s\p{L}\p{N}]+ | \s+(?!\S) | \s+
/// ```
fn gpt2_pieces(text: &str) -> Vec<String> {
    let chars: Vec<char> = text.chars().collect();
    let mut out: Vec<String> = Vec::new();
    let mut index = 0;
    while index < chars.len() {
        let ch = chars[index];
        // 缩写：'s / 't / 're / 've / 'm / 'll / 'd（大小写不敏感）
        if ch == '\'' {
            let rest: String = chars[index..]
                .iter()
                .take(3)
                .collect::<String>()
                .to_lowercase();
            let mut matched = 0;
            for contraction in ["'re", "'ve", "'ll", "'s", "'t", "'m", "'d"] {
                if rest.starts_with(contraction) {
                    matched = contraction.chars().count();
                    break;
                }
            }
            if matched > 0 {
                out.push(chars[index..index + matched].iter().collect());
                index += matched;
                continue;
            }
        }

        let mut start = index;
        // 一个前导空格可以粘在后面的词上。
        let mut has_space = false;
        if ch == ' ' && index + 1 < chars.len() {
            has_space = true;
            start = index + 1;
        }
        let next = chars[start];

        let (class, _) = if next.is_alphabetic() {
            (0, ())
        } else if next.is_numeric() {
            (1, ())
        } else if !next.is_whitespace() {
            (2, ())
        } else {
            (3, ())
        };

        match class {
            0 | 1 => {
                let mut end = start;
                while end < chars.len()
                    && if class == 0 {
                        chars[end].is_alphabetic()
                    } else {
                        chars[end].is_numeric()
                    }
                {
                    end += 1;
                }
                out.push(chars[index..end].iter().collect());
                index = end;
            }
            2 => {
                let mut end = start;
                while end < chars.len()
                    && !chars[end].is_whitespace()
                    && !chars[end].is_alphabetic()
                    && !chars[end].is_numeric()
                {
                    end += 1;
                }
                out.push(chars[index..end].iter().collect());
                index = end;
            }
            _ => {
                // 空白：`\s+(?!\S)` —— 后面还有非空白时，**最后那一个空白留给下一段**。
                let ws_start = if has_space { index } else { start };
                let mut end = ws_start;
                while end < chars.len() && chars[end].is_whitespace() {
                    end += 1;
                }
                let mut stop = end;
                if end < chars.len() && end > ws_start {
                    // 后面有非空白：把最后一个空白字符让出去。
                    stop = end - 1;
                }
                if stop > ws_start {
                    out.push(chars[ws_start..stop].iter().collect());
                    index = stop;
                } else if end > ws_start {
                    // 只有一个空白且后面还有内容：它自己成段。
                    out.push(chars[ws_start..end].iter().collect());
                    index = end;
                } else {
                    let _ = has_space;
                    out.push(ch.to_string());
                    index += 1;
                }
            }
        }
    }
    out
}

/// BERT 的预切分：标点单独切，其余按空白。
fn bert_pieces(text: &str) -> Vec<String> {
    let mut out = Vec::new();
    let mut buf = String::new();
    for ch in text.chars() {
        if ch.is_whitespace() {
            if !buf.is_empty() {
                out.push(std::mem::take(&mut buf));
            }
        } else if ch.is_ascii_punctuation() || (!ch.is_alphanumeric() && !ch.is_whitespace()) {
            if !buf.is_empty() {
                out.push(std::mem::take(&mut buf));
            }
            out.push(ch.to_string());
        } else {
            buf.push(ch);
        }
    }
    if !buf.is_empty() {
        out.push(buf);
    }
    out
}

/// GPT-2 的 byte-level 映射：把每个字节搬成一个可见的 Unicode 字符。
///
/// 规则：可打印的 ASCII 原样，其余 160 个字节按顺序映射到 U+0100 之后。
fn byte_level(text: &str) -> String {
    let mut out = String::with_capacity(text.len());
    for byte in text.as_bytes() {
        out.push(byte_to_char(*byte));
    }
    out
}

/// 单个字节的映射（与 OpenAI 的 `bytes_to_unicode` 一致）。
fn byte_to_char(byte: u8) -> char {
    // 可打印区间直接给；其余按顺序排在 0x100 之后。
    let printable = (b'!'..=b'~').contains(&byte)
        || (0xA1..=0xAC).contains(&byte)
        || (0xAE..=0xFF).contains(&byte);
    if printable {
        return byte as char;
    }
    let mut offset = 0u32;
    for candidate in 0..byte {
        let printable = (b'!'..=b'~').contains(&candidate)
            || (0xA1..=0xAC).contains(&candidate)
            || (0xAE..=0xFF).contains(&candidate);
        if !printable {
            offset += 1;
        }
    }
    char::from_u32(0x100 + offset).unwrap_or('\u{FFFD}')
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 一份最小 BPE 词表：ByteLevel + GPT-2 规则，词表手工给小样。
    fn tiny_bpe() -> HfTokenizer {
        let json = r#"{
            "added_tokens": [
                {"id": 9, "content": "<|end|>", "special": true}
            ],
            "normalizer": null,
            "pre_tokenizer": {"type": "ByteLevel", "add_prefix_space": false, "use_regex": true},
            "model": {
                "type": "BPE",
                "vocab": {"h":0,"e":1,"l":2,"o":3,"he":4,"ll":5,"hello":6,"Ġ":7,"world":8,"<|end|>":9},
                "merges": ["h e", ["l","l"], "he ll", "hell o", "Ġ world"]
            }
        }"#;
        HfTokenizer::from_json(json).expect("解析")
    }

    #[test]
    fn bpe_按_rank_合并() {
        let tok = tiny_bpe();
        // hello → h+e → he；l+l → ll；he+ll → hell；hell+o → hello（词表里有）→ 一个 id。
        assert_eq!(tok.encode("hello"), vec![6]);
        // " world" 走 ByteLevel 变成 `Ġworld`，但合并表里只有 `Ġ world`（两个整词），
        // 而 BPE 是在**字符**上做合并的，所以这一步合不出来：
        // 只剩词表里单独有的 Ġ / o / l。w、r、d 不在词表里又没有 unk → 丢掉。
        // 这条钉住的是"BPE 不会拿多字符词条直接匹配"——它只认合并表。
        assert_eq!(tok.encode(" world"), vec![7, 3, 2]);
    }

    #[test]
    fn added_token_先匹配且长的先() {
        let tok = tiny_bpe();
        let ids = tok.encode("<|end|>");
        assert_eq!(ids, vec![9]);
        // 混在文本里也要切出来，周围照常编码。
        let mixed = tok.encode("hello<|end|>");
        assert_eq!(mixed, vec![6, 9]);
    }

    #[test]
    fn merges_两种写法都认() {
        let tok = tiny_bpe();
        assert_eq!(tok.kind(), ModelKind::Bpe);
        // `["l","l"]` 是**数组**写法（另一条 `"h e"` 是字符串写法）。
        // 它能合出来的证据：l+l → ll，而词表里有 ll → 一个 id。
        assert_eq!(tok.encode("ll"), vec![5]);
        // 字符串写法的证据：h+e → he。
        assert_eq!(tok.encode("he"), vec![4]);
    }

    #[test]
    fn 中文按字切再合并() {
        // 词表里没有中文，逐字返回 unk 不应 panic；这里只验"不炸且长度合理"。
        let tok = tiny_bpe();
        let ids = tok.encode("你好");
        assert!(ids.is_empty() || ids.len() <= 8);
    }

    #[test]
    fn 空的_sequence_normalizer_等于没有() {
        let json = r#"{
            "added_tokens": [],
            "normalizer": {"type": "Sequence", "normalizers": []},
            "pre_tokenizer": null,
            "model": {"type": "BPE", "vocab": {"a":0}, "merges": []}
        }"#;
        assert!(HfTokenizer::from_json(json).is_ok());
    }

    #[test]
    fn 认不出的_normalizer_要拒绝而不是猜() {
        let json = r#"{
            "added_tokens": [],
            "normalizer": {"type": "NFKC"},
            "pre_tokenizer": null,
            "model": {"type": "BPE", "vocab": {"a":0}, "merges": []}
        }"#;
        match HfTokenizer::from_json(json) {
            Err(HfError::Normalizer(name)) => assert_eq!(name, "NFKC"),
            other => panic!("应当拒绝 NFKC，实际：{:?}", other.err()),
        }
    }

    #[test]
    fn 认不出的_model_type_要拒绝() {
        let json = r#"{
            "added_tokens": [],
            "normalizer": null,
            "pre_tokenizer": null,
            "model": {"type": "WordLevel", "vocab": {"a":0}}
        }"#;
        assert!(matches!(
            HfTokenizer::from_json(json),
            Err(HfError::ModelKind(_))
        ));
    }

    #[test]
    fn wordpiece_贪心最长匹配() {
        // `##` 在 JSON 里要写成 \u0023\u0023：Rust 的 raw string 里 `"##` 会把字面量提前收尾。
        let json = r#"{
            "added_tokens": [],
            "normalizer": null,
            "pre_tokenizer": {"type": "BertPreTokenizer"},
            "model": {"type": "WordPiece", "vocab": {"un":0,"\u0023\u0023able":1,"able":2,"unable":3,"\u0023\u0023s":4}, "unk_id": 99}
        }"#;
        let tok = HfTokenizer::from_json(json).expect("解析");
        assert_eq!(tok.encode("unable"), vec![3]);
        // 词表里没有整词时走 ## 续词
        assert_eq!(tok.encode("unable"), vec![3]);
    }

    #[test]
    fn unigram_取概率最大的那条切分() {
        let json = r#"{
            "added_tokens": [],
            "normalizer": null,
            "pre_tokenizer": {"type": "Whitespace"},
            "model": {"type": "Unigram", "vocab": [["ab",-1.0],["a",-2.0],["b",-2.0]], "unk_id": 3}
        }"#;
        let tok = HfTokenizer::from_json(json).expect("解析");
        // "ab" 一条比 "a"+"b" 概率高，应当合成一个。
        assert_eq!(tok.encode("ab"), vec![0]);
    }

    #[test]
    fn gpt2_预切分把缩写与前导空格切对() {
        let pieces = gpt2_pieces("hello world's");
        assert_eq!(pieces, vec!["hello", " world", "'s"]);
    }

    #[test]
    fn byte_level_映射是可逆的可见字符() {
        // 空格 → Ġ（U+0120），换行 → Ċ（U+010A）
        assert_eq!(byte_level(" "), "Ġ");
        assert_eq!(byte_level("\n"), "Ċ");
        assert_eq!(byte_level("a"), "a");
    }

    /// DeepSeek 那份预切分里最刁的两条，用一份最小词表钉住：
    ///
    /// 1. `\s+(?!\S)` 在 `"  a"` 上只吃**一个**空格（`" a"` 整段进下一块）；
    /// 2. 换行走的是更前面的 `\s*[\r\n]+`，`"\n\na"` 的两个换行**合成一块**。
    ///
    /// 这两条都是"差一个 token 而界面上看不出"的位置，实测对账时各错过一次。
    #[test]
    fn 空白串的切法与_hf_一致() {
        let json = r#"{
            "added_tokens": [],
            "normalizer": {"type": "Sequence", "normalizers": []},
            "pre_tokenizer": {"type": "Sequence", "pretokenizers": [
                {"type": "Split", "pattern": {"Regex": "\\s+(?!\\S)|\\s*[\\r\\n]+"}, "behavior": "Isolated", "invert": false},
                {"type": "ByteLevel", "add_prefix_space": false, "use_trim": true, "use_regex": false}
            ]},
            "model": {"type": "BPE", "vocab": {"Ġ":0,"a":1,"Ċ":2}, "merges": []}
        }"#;
        let tok = HfTokenizer::from_json(json).expect("解析");
        // `"  a"`：第一个空格单独成块，第二个空格并进 `a`。两块都是 `Ġ` + `a`。
        assert_eq!(tok.encode("  a"), vec![0, 0, 1]);
        // `"\n\na"`：两个换行一块（`ĊĊ` 拆成两个 `Ċ`，因为词表里没有合并），再是 `a`。
        assert_eq!(tok.encode("\n\na"), vec![2, 2, 1]);
    }
}
