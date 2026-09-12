#!/usr/bin/env bash
# 火山方舟（Ark）deepseek 流式工具调用探针
#
# 目的：回答「学习帮手能不能改成原生流式（真流式思维链 + 原生工具循环）」：
#   1) delta.reasoning_content 是否逐块返回（真流式 CoT）
#   2) delta.tool_calls[].function.name 是完整还是分片（决定 Spring AI 聚合层能否直接用）
#   3) 第二轮请求里 assistant 消息不带 reasoning_content 会不会 400（思考模式硬约束）
#
# 用法：
#   ARK_API_KEY=xxx ./scripts/ark-probe.sh
#   或者直接运行，脚本会提示粘贴 Key（不回显，不进 shell 历史）
# 可选环境变量：
#   ARK_BASE   默认 https://ark.cn-beijing.volces.com/api/plan/v3（Agent Plan 专用路径，不是 /api/v3）
#   ARK_MODEL  默认 deepseek-v4-flash
#
# 只读探针：不改代码、不写业务数据，产物都在 /tmp/ 下，用完可删。

set -uo pipefail

if [ -z "${ARK_API_KEY:-}" ]; then
  printf '粘贴方舟 API Key（输入不回显）: '
  read -rs ARK_API_KEY
  printf '\n'
fi

ARK_BASE="${ARK_BASE:-https://ark.cn-beijing.volces.com/api/v3}"
ARK_MODEL="${ARK_MODEL:-deepseek-v4-flash}"
RAW=/tmp/ark_probe_raw.sse
REQ=/tmp/ark_probe_req.json

command -v python3 >/dev/null 2>&1 || { echo '需要 python3 解析 SSE'; exit 1; }

cat > "$REQ" <<JSON
{
  "model": "${ARK_MODEL}",
  "stream": true,
  "stream_options": { "include_usage": true },
  "tools": [
    {
      "type": "function",
      "function": {
        "name": "get_weather",
        "description": "查询指定城市的当前天气",
        "parameters": {
          "type": "object",
          "properties": { "city": { "type": "string", "description": "城市名，如 北京" } },
          "required": ["city"]
        }
      }
    }
  ],
  "messages": [
    { "role": "system", "content": "需要外部信息时必须调用工具，不要凭空回答。" },
    { "role": "user", "content": "查一下北京现在的天气" }
  ]
}
JSON

echo "== 第一轮：${ARK_BASE}/chat/completions  model=${ARK_MODEL}  stream=true + tools =="
curl -sN "${ARK_BASE}/chat/completions" \
  -H "Authorization: Bearer ${ARK_API_KEY}" \
  -H "Content-Type: application/json" \
  --data @"${REQ}" \
  -o "${RAW}" -w 'HTTP %{http_code}, 用时 %{time_total}s, 原始 SSE 已存 '"${RAW}"$'\n'

if [ ! -s "$RAW" ]; then
  echo "没拿到响应体（网络/鉴权/参数问题），检查 Base URL 与 Key"
  exit 1
fi

python3 - <<'PY'
import json
import pathlib

raw = pathlib.Path('/tmp/ark_probe_raw.sse').read_text(encoding='utf-8', errors='replace')
req = json.loads(pathlib.Path('/tmp/ark_probe_req.json').read_text(encoding='utf-8'))

reasoning, content, calls, finish, usage = [], [], {}, None, None
first_tc_payload = None

for line in raw.splitlines():
    if not line.startswith('data:'):
        continue  # 顺带忽略 ': keep-alive' 保活行
    payload = line[5:].strip()
    if payload == '[DONE]':
        break
    try:
        obj = json.loads(payload)
    except json.JSONDecodeError:
        continue
    if obj.get('usage'):
        usage = obj['usage']
    choice = (obj.get('choices') or [{}])[0]
    delta = choice.get('delta') or {}
    if delta.get('reasoning_content'):
        reasoning.append(delta['reasoning_content'])
    if delta.get('content'):
        content.append(delta['content'])
    tcs = delta.get('tool_calls')
    if tcs:
        if first_tc_payload is None:
            first_tc_payload = payload
        for tc in tcs:
            idx = tc.get('index', 0)
            slot = calls.setdefault(idx, {'id': None, 'name_frags': [], 'args': ''})
            if tc.get('id'):
                slot['id'] = tc['id']
            fn = tc.get('function') or {}
            if fn.get('name'):
                slot['name_frags'].append(fn['name'])
            if fn.get('arguments'):
                slot['args'] += fn['arguments']
    if choice.get('finish_reason'):
        finish = choice['finish_reason']

joined = ''.join(reasoning)
print('1) reasoning_content 分片数=%d 总字数=%d' % (len(reasoning), len(joined)))
print('   前 80 字: %s' % (joined[:80].replace('\n', ' ')
                           or '(空：模型默认没开思考，可在请求体加 "thinking":{"type":"enabled"} 重跑)'))
print('   各分片长度(前 15): %s' % [len(x) for x in reasoning[:15]])
print('   content 分片数=%d  finish_reason=%s' % (len(content), finish))
if first_tc_payload:
    print('   tool_calls 首个原始 chunk: %s' % first_tc_payload[:220])

for idx in sorted(calls):
    slot = calls[idx]
    verdict = ('完整（Spring AI 聚合层可用）' if len(slot['name_frags']) == 1
               else '分片（就是 Spring AI 报 toolName cannot be null or empty 的形态）')
    print('2) tool_call[%d] id=%s' % (idx, slot['id']))
    print('   name 分片=%s -> %s' % (slot['name_frags'], verdict))
    print('   arguments=%s' % slot['args'])

if usage:
    print('   usage=%s' % json.dumps(usage, ensure_ascii=False))

if not calls:
    print('2) 本轮没触发工具调用，重跑时把 user 内容改成「必须调用 get_weather 查询北京天气」')
    raise SystemExit

idx = sorted(calls)[0]
slot = calls[idx]
assistant = {
    'role': 'assistant',
    'content': ''.join(content),
    'reasoning_content': joined,
    'tool_calls': [{
        'id': slot['id'],
        'type': 'function',
        'function': {'name': ''.join(slot['name_frags']), 'arguments': slot['args']},
    }],
}
tool_msg = {'role': 'tool', 'tool_call_id': slot['id'], 'content': '北京 26℃，晴'}
history = list(req['messages'])
for tag, msg in (('with', assistant),
                 ('without', {k: v for k, v in assistant.items() if k != 'reasoning_content'})):
    body = {
        'model': req['model'],
        'stream': False,
        'tools': req.get('tools', []),
        'messages': history + [msg, tool_msg],
    }
    pathlib.Path('/tmp/ark_round2_%s.json' % tag).write_text(
        json.dumps(body, ensure_ascii=False), encoding='utf-8')
print('3) 已生成 /tmp/ark_round2_with.json（带 reasoning_content）与 /tmp/ark_round2_without.json（不带）')
PY

echo
echo "== 第二轮：assistant 消息带 reasoning_content vs 不带 =="
for f in /tmp/ark_round2_with.json /tmp/ark_round2_without.json; do
  [ -f "$f" ] || continue
  printf -- '-- %s\n' "$f"
  curl -s "${ARK_BASE}/chat/completions" \
    -H "Authorization: Bearer ${ARK_API_KEY}" \
    -H "Content-Type: application/json" \
    --data @"${f}" \
    -o /tmp/ark_round2_resp.json -w '   HTTP %{http_code}\n'
  head -c 400 /tmp/ark_round2_resp.json
  printf '\n'
done
