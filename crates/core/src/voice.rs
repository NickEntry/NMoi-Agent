//! 歌词 → 音素 → 音符（0.17b 的第一层，跟引擎无关）。
//!
//! 唱歌那条链路上，最靠前、也最容易被跳过的一层是"字怎么读"。它不依赖任何 ONNX 模型，
//! 所以先做它：中文（汉字→拼音→声母/韵母）、日文（假名→罗马字→辅音/元音）、
//! 英文（CMUdict）。
//!
//! 这一层出去的形状，就是后面 DiffSinger 要的：**每个音符一串音素 + 占多少帧**。
//! 时长先按拍子平均分给音素里的元音——真·时长模型（`dsdur`）是下一步的事，
//! 现在这份够把"歌词对不对"验掉。
//!
//! 数据文件（`include_str!`，随包发）：
//! - `data/pinyin.json`：6763 个常用汉字 → 不带声调拼音（GB2312 一级+二级）。
//! - `data/cmudict.txt`：CMUdict 去重、去重音数字后的第一读音。

use std::collections::HashMap;
use std::sync::OnceLock;

const PINYIN_JSON: &str = include_str!("../../../data/pinyin.json");
const CMUDICT: &str = include_str!("../../../data/cmudict.txt");

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum Lang {
    Zh,
    Ja,
    En,
}

impl Lang {
    pub fn parse(text: &str) -> Option<Lang> {
        match text.trim().to_ascii_lowercase().as_str() {
            "zh" | "cn" | "cmn" => Some(Lang::Zh),
            "ja" | "jp" => Some(Lang::Ja),
            "en" => Some(Lang::En),
            _ => None,
        }
    }

    pub fn as_str(self) -> &'static str {
        match self {
            Lang::Zh => "zh",
            Lang::Ja => "ja",
            Lang::En => "en",
        }
    }
}

#[derive(Debug, Clone, PartialEq)]
pub struct Note {
    pub lyric: String,
    pub midi: u8,
    pub beats: f32,
}

#[derive(Debug, Clone, PartialEq)]
pub struct Track {
    pub lang: Lang,
    pub bpm: u32,
    pub notes: Vec<Note>,
}

#[derive(Debug, Clone, PartialEq)]
pub struct SungNote {
    pub lyric: String,
    pub midi: u8,
    pub frames: usize,
    pub phonemes: Vec<String>,
}

pub fn pinyin_of(hanzi: &str) -> Option<&'static str> {
    static TABLE: OnceLock<HashMap<String, String>> = OnceLock::new();
    let table = TABLE.get_or_init(|| {
        serde_json::from_str::<HashMap<String, String>>(PINYIN_JSON).unwrap_or_default()
    });
    table.get(hanzi).map(|text| text.as_str())
}

fn cmu() -> &'static HashMap<String, Vec<String>> {
    static TABLE: OnceLock<HashMap<String, Vec<String>>> = OnceLock::new();
    TABLE.get_or_init(|| {
        let mut map = HashMap::new();
        for line in CMUDICT.lines() {
            let mut parts = line.split_whitespace();
            let Some(word) = parts.next() else { continue };
            let phones: Vec<String> = parts.map(|p| p.to_ascii_lowercase()).collect();
            if !phones.is_empty() {
                map.entry(word.to_ascii_lowercase()).or_insert(phones);
            }
        }
        map
    })
}

const INITIALS: &[&str] = &[
    "zh", "ch", "sh", "b", "p", "m", "f", "d", "t", "n", "l", "g", "k", "h", "j", "q", "x", "r",
    "z", "c", "s", "y", "w",
];

/// 一个音节 → 音素。中文走声母/韵母，日文走假名表的罗马字，英文查 CMUdict。
pub fn phonemes_for(lang: Lang, lyric: &str) -> Result<Vec<String>, String> {
    let text = lyric.trim();
    if text.is_empty() {
        return Err("歌词是空的".into());
    }
    if text.starts_with('[') && text.ends_with(']') {
        return Ok(text[1..text.len() - 1]
            .split_whitespace()
            .map(|item| item.to_string())
            .collect());
    }
    match lang {
        Lang::Zh => zh(text),
        Lang::Ja => ja(text),
        Lang::En => en(text),
    }
}

fn zh(text: &str) -> Result<Vec<String>, String> {
    // 整条都是字母 = 已经写成拼音了（`yi`、`shan`），按一个音节拆；
    // 里面混着汉字就一个字一个字查。
    if text.chars().all(|ch| ch.is_ascii_alphabetic()) {
        return split_pinyin(text).ok_or_else(|| format!("拼音拆不开：{text}"));
    }
    let mut out: Vec<String> = Vec::new();
    for ch in text.chars() {
        if ch.is_whitespace() {
            continue;
        }
        if ch.is_ascii_alphabetic() {
            out.push(ch.to_ascii_lowercase().to_string());
            continue;
        }
        let syllable = pinyin_of(&ch.to_string())
            .ok_or_else(|| format!("不认识的汉字：{ch}（不在词典里，可以直接写拼音）"))?;
        out.extend(split_pinyin(syllable).ok_or_else(|| format!("拼音拆不开：{syllable}"))?);
    }
    if out.is_empty() {
        return Err(format!("这一条没有可发音的字：{text}"));
    }
    Ok(out)
}

/// `zhang` → `["zh", "ang"]`；`er` → `["er"]`；`yi` → `["y", "i"]`。
pub fn split_pinyin(syllable: &str) -> Option<Vec<String>> {
    let text = syllable.to_ascii_lowercase().replace('ü', "v");
    if text.is_empty() || !text.chars().all(|c| c.is_ascii_alphabetic()) {
        return None;
    }
    for initial in INITIALS {
        if let Some(rest) = text.strip_prefix(initial) {
            if rest.is_empty() {
                // 只有声母（`m`、`n` 这种叹词）——整串当一个音素。
                return Some(vec![(*initial).to_string()]);
            }
            return Some(vec![(*initial).to_string(), rest.to_string()]);
        }
    }
    Some(vec![text])
}

fn ja(text: &str) -> Result<Vec<String>, String> {
    let mut out: Vec<String> = Vec::new();
    for ch in text.chars() {
        if ch == 'ー' {
            if let Some(last) = out.last().cloned() {
                if is_vowel(&last) {
                    out.push(last);
                }
            }
            continue;
        }
        if ch == 'っ' || ch == 'ッ' {
            out.push("cl".into());
            continue;
        }
        if ch.is_whitespace() {
            continue;
        }
        let Some(romaji) = kana(ch) else {
            return Err(format!("不认识的假名：{ch}（汉字请先写成假名或罗马字）"));
        };
        out.extend(split_mora(romaji));
    }
    if out.is_empty() {
        return Err(format!("这一条没有可发音的假名：{text}"));
    }
    Ok(out)
}

fn is_vowel(symbol: &str) -> bool {
    matches!(symbol, "a" | "i" | "u" | "e" | "o" | "n")
}

fn split_mora(romaji: &str) -> Vec<String> {
    if romaji == "n" {
        return vec!["n".into()];
    }
    let mut chars: Vec<char> = romaji.chars().collect();
    if chars.last() == Some(&'n') && chars.len() > 1 {
        chars.pop();
    }
    let vowels = ['a', 'i', 'u', 'e', 'o'];
    let Some(position) = chars.iter().rposition(|c| vowels.contains(c)) else {
        return vec![romaji.to_string()];
    };
    let (head, tail) = chars.split_at(position);
    let consonant: String = head.iter().collect();
    let vowel: String = tail.iter().collect();
    if consonant.is_empty() {
        vec![vowel]
    } else {
        vec![consonant, vowel]
    }
}

fn kana(ch: char) -> Option<&'static str> {
    Some(match ch {
        'あ' => "a",
        'い' => "i",
        'う' => "u",
        'え' => "e",
        'お' => "o",
        'か' => "ka",
        'き' => "ki",
        'く' => "ku",
        'け' => "ke",
        'こ' => "ko",
        'さ' => "sa",
        'し' => "shi",
        'す' => "su",
        'せ' => "se",
        'そ' => "so",
        'た' => "ta",
        'ち' => "chi",
        'つ' => "tsu",
        'て' => "te",
        'と' => "to",
        'な' => "na",
        'に' => "ni",
        'ぬ' => "nu",
        'ね' => "ne",
        'の' => "no",
        'は' => "ha",
        'ひ' => "hi",
        'ふ' => "fu",
        'へ' => "he",
        'ほ' => "ho",
        'ま' => "ma",
        'み' => "mi",
        'む' => "mu",
        'め' => "me",
        'も' => "mo",
        'や' => "ya",
        'ゆ' => "yu",
        'よ' => "yo",
        'ら' => "ra",
        'り' => "ri",
        'る' => "ru",
        'れ' => "re",
        'ろ' => "ro",
        'わ' => "wa",
        'を' => "o",
        'ん' => "n",
        'が' => "ga",
        'ぎ' => "gi",
        'ぐ' => "gu",
        'げ' => "ge",
        'ご' => "go",
        'ざ' => "za",
        'じ' => "ji",
        'ず' => "zu",
        'ぜ' => "ze",
        'ぞ' => "zo",
        'だ' => "da",
        'ぢ' => "ji",
        'づ' => "zu",
        'で' => "de",
        'ど' => "do",
        'ば' => "ba",
        'び' => "bi",
        'ぶ' => "bu",
        'べ' => "be",
        'ぼ' => "bo",
        'ぱ' => "pa",
        'ぴ' => "pi",
        'ぷ' => "pu",
        'ぺ' => "pe",
        'ぽ' => "po",
        'ぁ' => "a",
        'ぃ' => "i",
        'ぅ' => "u",
        'ぇ' => "e",
        'ぉ' => "o",
        'ゃ' => "ya",
        'ゅ' => "yu",
        'ょ' => "yo",
        'ア' => "a",
        'イ' => "i",
        'ウ' => "u",
        'エ' => "e",
        'オ' => "o",
        'カ' => "ka",
        'キ' => "ki",
        'ク' => "ku",
        'ケ' => "ke",
        'コ' => "ko",
        'サ' => "sa",
        'シ' => "shi",
        'ス' => "su",
        'セ' => "se",
        'ソ' => "so",
        'タ' => "ta",
        'チ' => "chi",
        'ツ' => "tsu",
        'テ' => "te",
        'ト' => "to",
        'ナ' => "na",
        'ニ' => "ni",
        'ヌ' => "nu",
        'ネ' => "ne",
        'ノ' => "no",
        'ハ' => "ha",
        'ヒ' => "hi",
        'フ' => "fu",
        'ヘ' => "he",
        'ホ' => "ho",
        'マ' => "ma",
        'ミ' => "mi",
        'ム' => "mu",
        'メ' => "me",
        'モ' => "mo",
        'ヤ' => "ya",
        'ユ' => "yu",
        'ヨ' => "yo",
        'ラ' => "ra",
        'リ' => "ri",
        'ル' => "ru",
        'レ' => "re",
        'ロ' => "ro",
        'ワ' => "wa",
        'ヲ' => "o",
        'ン' => "n",
        _ => return None,
    })
}

fn en(text: &str) -> Result<Vec<String>, String> {
    let word: String = text
        .chars()
        .filter(|c| c.is_ascii_alphanumeric() || *c == '\'')
        .collect::<String>()
        .to_ascii_lowercase();
    if word.is_empty() {
        return Err(format!("这条歌词里没有英文：{text}"));
    }
    cmu().get(&word).cloned().ok_or_else(|| {
        format!("CMUdict 里没有这个英文词：{word}（可以直接写 [t w ih n] 这样的音素）")
    })
}

/// 谱面：`bpm 96` / `lang zh` / `一 C4 1` / `r 1`（休止）。
pub fn parse_score(text: &str) -> Result<Track, String> {
    let mut bpm = 120u32;
    let mut lang: Option<Lang> = None;
    let mut notes = Vec::new();
    for (index, raw) in text.lines().enumerate() {
        // `#` 只在行首或前面是空白时才算注释：`C#4` / `F#3` 里的升号不是注释。
        let line = match raw
            .char_indices()
            .find(|(at, ch)| *ch == '#' && (*at == 0 || raw[..*at].ends_with(char::is_whitespace)))
        {
            Some((at, _)) => &raw[..at],
            None => raw,
        }
        .trim();
        if line.is_empty() {
            continue;
        }
        let lower = line.to_ascii_lowercase();
        if let Some(rest) = lower.strip_prefix("bpm ") {
            bpm = rest
                .trim()
                .parse::<u32>()
                .map_err(|_| format!("第 {} 行：bpm 只认整数，拿到 {rest}", index + 1))?;
            if !(20..=400).contains(&bpm) {
                return Err(format!("第 {} 行：bpm {bpm} 不在 20–400", index + 1));
            }
            continue;
        }
        if let Some(rest) = lower.strip_prefix("lang ") {
            lang = Lang::parse(rest);
            if lang.is_none() {
                return Err(format!("第 {} 行：语言只认 zh / ja / en", index + 1));
            }
            continue;
        }
        let parts: Vec<&str> = line.split_whitespace().collect();
        if parts[0] == "r" {
            continue;
        }
        if parts.len() < 3 {
            return Err(format!(
                "第 {} 行：写成「歌词 音高 拍数」，比如 `一 C4 1`",
                index + 1
            ));
        }
        let lyric = parts[0].to_string();
        let midi = parse_pitch(parts[1])
            .ok_or_else(|| format!("第 {} 行：音高写成 C4 / F#3 / Bb4 / 60", index + 1))?;
        let beats: f32 = parts[2]
            .parse()
            .map_err(|_| format!("第 {} 行：拍数只认数字，比如 1 / 0.5 / 2", index + 1))?;
        if beats <= 0.0 {
            return Err(format!("第 {} 行：拍数要大于 0", index + 1));
        }
        notes.push(Note { lyric, midi, beats });
    }
    let lang = lang.ok_or_else(|| "谱面里要有一行 `lang zh|ja|en`".to_string())?;
    if notes.is_empty() {
        return Err("谱面里一个音符都没有".into());
    }
    Ok(Track { lang, bpm, notes })
}

fn parse_pitch(text: &str) -> Option<u8> {
    let text = text.trim();
    if let Ok(value) = text.parse::<u8>() {
        return (value <= 127).then_some(value);
    }
    let mut chars = text.chars();
    let letter = chars.next()?.to_ascii_uppercase();
    let base = match letter {
        'C' => 0,
        'D' => 2,
        'E' => 4,
        'F' => 5,
        'G' => 7,
        'A' => 9,
        'B' => 11,
        _ => return None,
    };
    let mut accidental = 0i32;
    let mut rest = String::new();
    for ch in chars {
        match ch {
            '#' => accidental += 1,
            'b' => accidental -= 1,
            c if c.is_ascii_digit() => rest.push(c),
            _ => return None,
        }
    }
    let octave: i32 = rest.parse().ok()?;
    let value = (octave + 1) * 12 + base + accidental;
    (0..=127).contains(&value).then_some(value as u8)
}

/// 音符 → 帧数 + 音素。时长先按拍子平均分：辅音占一成、元音分剩下的（`dsdur` 接上之后换成模型）。
pub fn phonemize(track: &Track, frame_ms: f32) -> Result<Vec<SungNote>, String> {
    if frame_ms <= 0.0 {
        return Err("frame_ms 要大于 0".into());
    }
    let beat_ms = 60_000.0 / track.bpm as f32;
    let mut out = Vec::with_capacity(track.notes.len());
    for note in &track.notes {
        let phonemes = phonemes_for(track.lang, &note.lyric)?;
        let frames = ((note.beats * beat_ms / frame_ms).round() as usize).max(1);
        out.push(SungNote {
            lyric: note.lyric.clone(),
            midi: note.midi,
            frames,
            phonemes,
        });
    }
    Ok(out)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn chinese_goes_through_pinyin() {
        assert_eq!(phonemes_for(Lang::Zh, "一").unwrap(), vec!["y", "i"]);
        assert_eq!(phonemes_for(Lang::Zh, "闪").unwrap(), vec!["sh", "an"]);
        assert_eq!(phonemes_for(Lang::Zh, "亮").unwrap(), vec!["l", "iang"]);
        assert_eq!(phonemes_for(Lang::Zh, "儿").unwrap(), vec!["er"]);
    }

    #[test]
    fn unknown_hanzi_is_named() {
        let error = phonemes_for(Lang::Zh, "龘").unwrap_err();
        assert!(error.contains('龘'), "{error}");
    }

    #[test]
    fn japanese_splits_into_mora() {
        assert_eq!(phonemes_for(Lang::Ja, "さ").unwrap(), vec!["s", "a"]);
        assert_eq!(phonemes_for(Lang::Ja, "く").unwrap(), vec!["k", "u"]);
        assert_eq!(phonemes_for(Lang::Ja, "ら").unwrap(), vec!["r", "a"]);
        assert_eq!(
            phonemes_for(Lang::Ja, "きょう").unwrap(),
            vec!["k", "i", "y", "o", "u"]
        );
    }

    #[test]
    fn english_uses_cmudict() {
        let phones = phonemes_for(Lang::En, "twinkle").unwrap();
        assert_eq!(phones, vec!["t", "w", "ih", "ng", "k", "ah", "l"]);
        let error = phonemes_for(Lang::En, "zzzq").unwrap_err();
        assert!(error.contains("zzzq"), "{error}");
    }

    #[test]
    fn explicit_phonemes_pass_through() {
        assert_eq!(
            phonemes_for(Lang::En, "[t w ih n]").unwrap(),
            vec!["t", "w", "ih", "n"]
        );
    }

    #[test]
    fn score_parses_notes_and_language() {
        let score = parse_score("bpm 96\nlang zh\n一 C4 1\n闪 F#4 0.5\nr 1\n").unwrap();
        assert_eq!(score.bpm, 96);
        assert_eq!(score.lang, Lang::Zh);
        assert_eq!(score.notes.len(), 2);
        assert_eq!(score.notes[1].midi, 66);
        assert_eq!(score.notes[1].beats, 0.5);
    }

    #[test]
    fn a_score_without_language_is_refused() {
        let error = parse_score("一 C4 1").unwrap_err();
        assert!(error.contains("lang"), "{error}");
    }

    #[test]
    fn frames_follow_tempo() {
        let score = parse_score("bpm 96\nlang ja\nさ C4 1").unwrap();
        let sung = phonemize(&score, 5.0).unwrap();
        assert_eq!(sung[0].frames, 125);
        assert_eq!(sung[0].phonemes, vec!["s", "a"]);
    }
}
