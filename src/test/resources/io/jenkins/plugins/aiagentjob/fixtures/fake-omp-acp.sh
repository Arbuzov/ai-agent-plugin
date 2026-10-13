#!/bin/sh
set -eu
test "$1" = acp
policy=''
previous=''
for arg in "$@"; do
  if [ "$previous" = '--config' ]; then policy="$arg"; fi
  previous="$arg"
done
[ -n "$policy" ] && [ -f "$policy" ] || exit 95
[ "$(cat "$policy")" = "$(printf 'tools:\n  approval: null\n')" ] || exit 96
printf '%s' "$policy" > approval-policy-path
case "$*" in *'--approval-mode always-ask'*) ;; *) exit 90 ;; esac

IFS= read -r request
case "$request" in *'"form":{}'*) ;; *) exit 91 ;; esac
request_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
printf '{"jsonrpc":"2.0","id":%s,"result":{"protocolVersion":1,"agentCapabilities":{},"authMethods":[{"id":"agent","name":"Agent authentication"}]}}\n' "$request_id"

IFS= read -r request
case "$request" in *'"method":"authenticate"'*'"methodId":"agent"'*) ;; *) exit 92 ;; esac
request_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
printf '{"jsonrpc":"2.0","id":%s,"result":{}}\n' "$request_id"

IFS= read -r request
request_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
options='[{"id":"model","name":"Model","category":"model","type":"select","currentValue":"fixture/model","options":[{"value":"fixture/model","name":"Fixture"}]},{"id":"thinking","name":"Thinking","category":"thought_level","type":"select","currentValue":"high","options":[{"value":"high","name":"High"}]}]'
printf '{"jsonrpc":"2.0","id":%s,"result":{"sessionId":"omp-fixture","configOptions":%s}}\n' "$request_id" "$options"
while IFS= read -r request; do
  case "$request" in
    *'"method":"session/set_config_option"'*)
      printf '%s\n' "$request" >> config-requests.jsonl
      request_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
      printf '{"jsonrpc":"2.0","id":%s,"result":{"configOptions":%s}}\n' "$request_id" "$options"
      ;;
    *'"method":"session/prompt"'*)
      prompt_id=$(printf '%s\n' "$request" | sed -n 's/.*"id":\([^,}]*\).*/\1/p')
      break
      ;;
    *) exit 93 ;;
  esac
done
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"tool_call","toolCallId":"omp-write","title":"write approved.txt","kind":"edit","status":"pending","rawInput":{"path":"approved.txt"}}}}'
printf '{"jsonrpc":"2.0","id":"omp-approval","method":"elicitation/create","params":{"mode":"form","sessionId":"omp-fixture","message":"Allow tool: write\\nPath: approved.txt\\nContent: %s","requestedSchema":{"type":"object","properties":{"value":{"type":"string","enum":["Approve","Deny"]}},"required":["value"]}}}\n' "$OPENAI_API_KEY"
IFS= read -r response
printf '%s\n' "$response" > approval-response.json
case "$response" in *'"action":"accept"'*'"value":"Approve"'*) ;; *) exit 94 ;; esac
touch approved.txt
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"tool_call_update","toolCallId":"omp-write","status":"completed","rawOutput":"created approved.txt"}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"usage_update","used":999,"size":10000,"cost":{"currency":"USD","amount":0.012}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Created approved.txt"}}}}'
printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn","usage":{"inputTokens":30,"outputTokens":10,"totalTokens":40}}}\n' "$prompt_id"
