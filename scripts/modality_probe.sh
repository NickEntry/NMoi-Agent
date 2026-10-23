#!/usr/bin/env bash
# 多模态支持探针（P3.4 / D64）
#
# 目的很具体：**别猜端点支不支持某个模态，打一遍看它怎么回。**
# 本机没有多模态测试模型，所以这里不做断言，只出矩阵：
#   ok        —— 端点正常返回了内容
#   rejected  —— 端点明确拒绝了这个模态（400/415 之类，或错误里说 unsupported）
#   unknown   —— 端点回了些看不懂的东西（401 鉴权、429 限流、5xx）→ 这一行等于"没测"
#
# 用法：
#   scripts/modality_probe.sh --base http://127.0.0.1:8790/v1 --model <model> [--key K]
#                             [--image a.png] [--video a.mp4] [--audio a.mp3] [--pdf a.pdf]
#
# 没给素材的模态会被标成"未测"——**不会**用"应该支持"填上去。
set -uo pipefail

BASE=""
MODEL=""
KEY=""
IMAGE=""
VIDEO=""
AUDIO=""
PDF=""
TIMEOUT="${NMOI_PROBE_TIMEOUT:-60}"

usage() {
  cat <<'TEXT'
多模态支持探针（P3.4 / D64）

  scripts/modality_probe.sh --base <URL> --model <model> [--key K]
                            [--image a.png] [--video a.mp4] [--audio a.mp3] [--pdf a.pdf]

结论只有三种，别把它看成一个是/否：
  ok           端点正常返回了**内容**（这才叫支持）
  rejected     端点明确拒绝这个模态（400/415/422，或错误里说 unsupported）
  unknown      没结论：鉴权失败、限流、5xx，或者 2xx 但一个字都没返回
               —— 2xx 但没有正文**不算支持**，它只说明字段被吃了

没给素材的模态标成 untested，**不会**用"应该支持"填上去。
TEXT
  exit "${1:-0}"
}

while [ $# -gt 0 ]; do
  case "$1" in
    --base) BASE="$2"; shift 2 ;;
    --model) MODEL="$2"; shift 2 ;;
    --key) KEY="$2"; shift 2 ;;
    --image) IMAGE="$2"; shift 2 ;;
    --video) VIDEO="$2"; shift 2 ;;
    --audio) AUDIO="$2"; shift 2 ;;
    --pdf) PDF="$2"; shift 2 ;;
    -h|--help) usage 0 ;;
    *) echo "不认识的参数: $1" >&2; usage 2 ;;
  esac
done

if [ -z "$BASE" ] || [ -z "$MODEL" ]; then
  echo "缺少 --base / --model" >&2
  usage 2
fi

command -v curl >/dev/null || { echo "需要 curl" >&2; exit 2; }
command -v python3 >/dev/null || { echo "需要 python3（只用来拼 JSON）" >&2; exit 2; }

TMP="$(mktemp -d)"
trap 'rm -rf "$TMP"' EXIT

# 一张 1x1 的 PNG：用它能证明"端点收不收这个字段"，证明不了"它看不看得懂图"。
# 想验后者就 --image 传一张真图。
printf '%s' 'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==' \
  | base64 -d > "$TMP/probe.png"
[ -z "$IMAGE" ] && IMAGE="$TMP/probe.png"

b64_of() { base64 -w0 "$1" 2>/dev/null || base64 "$1" | tr -d '\n'; }

# 打一次 requests/chat，只看三件事：HTTP 码、有没有正文、错误里有没有 unsupported 字样。
probe() {
  local label="$1" body_file="$2"
  local args=(-s -m "$TIMEOUT" -o "$TMP/out.json" -w '%{http_code}'
              -H 'content-type: application/json' --data-binary "@$body_file")
  [ -n "$KEY" ] && args+=(-H "authorization: Bearer $KEY")

  local code
  code="$(curl "${args[@]}" "$BASE/chat/completions")"
  # 输出两行：第一行是给人看的细节，第二行是"有没有拿到内容"（1/0）
  local detail has_text got_text
  got_text="$(python3 - "$TMP/out.json" <<'INNER'
import json,sys
try:
    data=json.load(open(sys.argv[1]))
except Exception:
    print("(响应不是 JSON)"); print(0); raise SystemExit

def emit(text, got):
    print((text or "").replace("\n", " ")[:180]); print(got)

if isinstance(data.get("error"), dict):
    emit(data["error"].get("message") or "", 0); raise SystemExit
if isinstance(data.get("message"), str):
    emit(data["message"], 0); raise SystemExit
choices = data.get("choices") or []
if not choices:
    emit("(响应里没有 choices)", 0); raise SystemExit
choice = choices[0]
msg = choice.get("message") or {}
content = (msg.get("content") or "").strip()
reasoning = (msg.get("reasoning_content") or msg.get("reasoning") or "").strip()
if content:
    emit(content, 1)
elif reasoning:
    emit("(思考) " + reasoning, 1)
else:
    emit("2xx 但没正文（finish=%s）" % (choice.get("finish_reason") or "?"), 0)
INNER
)"
  detail="$(printf '%s' "$got_text" | sed -n '1p')"
  has_text="$(printf '%s' "$got_text" | sed -n '2p')"

  local verdict
  case "$code" in
    400|413|415|422) verdict="rejected" ;;
    2??) if [ "$has_text" = "1" ]; then verdict="ok"; else verdict="unknown"; fi ;;
    *) verdict="unknown" ;;
  esac
  if [ "$verdict" = "unknown" ] && printf '%s' "$detail" | grep -qi "unsupported\|not support\|invalid.*modal"; then
    verdict="rejected"
  fi
  printf '| %-8s | %-3s | %-9s | %s |\n' "$label" "$code" "$verdict" "${detail:-(无正文)}"
  echo "$verdict" >> "$TMP/verdicts"
}

echo "端点: $BASE"
echo "模型: $MODEL"
echo
echo "| 模态     | 码  | 结论      | 细节 |"
echo "|----------|-----|-----------|------|"

python3 - "$MODEL" > "$TMP/body_text.json" <<'PY'
import json,sys
json.dump({"model":sys.argv[1],"max_tokens":64,"messages":[{"role":"user","content":"ping"}]},sys.stdout)
PY
probe "text" "$TMP/body_text.json"

python3 - "$MODEL" "$IMAGE" > "$TMP/body_image.json" <<'PY'
import base64,json,sys
data=base64.b64encode(open(sys.argv[2],'rb').read()).decode()
json.dump({"model":sys.argv[1],"max_tokens":64,"messages":[{"role":"user","content":[
    {"type":"text","text":"这张图里有什么？一句话。"},
    {"type":"image_url","image_url":{"url":"data:image/png;base64,"+data}}]}]},sys.stdout)
PY
probe "image" "$TMP/body_image.json"

if [ -n "$PDF" ]; then
  python3 - "$MODEL" "$PDF" > "$TMP/body_pdf.json" <<'PY'
import base64,json,sys
data=base64.b64encode(open(sys.argv[2],'rb').read()).decode()
json.dump({"model":sys.argv[1],"max_tokens":64,"messages":[{"role":"user","content":[
    {"type":"text","text":"这份 PDF 讲什么？"},
    {"type":"file","file":{"filename":"probe.pdf","file_data":"data:application/pdf;base64,"+data}}]}]},sys.stdout)
PY
  probe "pdf" "$TMP/body_pdf.json"
else
  printf '| %-8s | %-3s | %-9s | %s |\n' "pdf" "-" "untested" "没给 --pdf"
fi

if [ -n "$AUDIO" ]; then
  python3 - "$MODEL" "$AUDIO" > "$TMP/body_audio.json" <<'PY'
import base64,json,sys
data=base64.b64encode(open(sys.argv[2],'rb').read()).decode()
json.dump({"model":sys.argv[1],"max_tokens":64,"messages":[{"role":"user","content":[
    {"type":"text","text":"这段音频说了什么？"},
    {"type":"input_audio","input_audio":{"data":data,"format":"mp3"}}]}]},sys.stdout)
PY
  probe "audio" "$TMP/body_audio.json"
else
  printf '| %-8s | %-3s | %-9s | %s |\n' "audio" "-" "untested" "没给 --audio"
fi

if [ -n "$VIDEO" ]; then
  python3 - "$MODEL" "$VIDEO" > "$TMP/body_video.json" <<'PY'
import base64,json,sys
data=base64.b64encode(open(sys.argv[2],'rb').read()).decode()
json.dump({"model":sys.argv[1],"max_tokens":64,"messages":[{"role":"user","content":[
    {"type":"text","text":"这段视频里发生了什么？"},
    {"type":"video_url","video_url":{"url":"data:video/mp4;base64,"+data}}]}]},sys.stdout)
PY
  probe "video" "$TMP/body_video.json"
else
  printf '| %-8s | %-3s | %-9s | %s |\n' "video" "-" "untested" "没给 --video"
fi

echo
echo "说明："
echo "- 1x1 的测试图只能证明端点收不收这个字段，证明不了它看得懂；要验后者请 --image 传真图。"
echo "- 'rejected' 是结论（这个模型/端点不收），'unknown' 是没结论（鉴权、限流、5xx）。"
echo "- 结论请写回 docs/model-data.md 或 issue，别只留在终端里。"
