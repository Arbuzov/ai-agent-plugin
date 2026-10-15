#!/bin/sh
set -eu
test "$1" = acp
policy=''
approval_mode=yolo
shift
while [ "$#" -gt 0 ]; do
  case "$1" in
    --system-prompt) shift 2 ;;
    --approval-mode) approval_mode="$2"; shift 2 ;;
    --config) policy="$2"; shift 2 ;;
    *) shift ;;
  esac
done
[ -n "$policy" ] && [ -f "$policy" ] || exit 95
grep -q '^  approval: null$' "$policy" || exit 96
policy_mode=$(sed -n 's/^  approvalMode: //p' "$policy")
if [ -n "$policy_mode" ]; then approval_mode="$policy_mode"; fi
printf '%s' "$policy" > approval-policy-path

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
if [ "$approval_mode" = always-ask ]; then
  printf '{"jsonrpc":"2.0","id":"omp-approval","method":"elicitation/create","params":{"mode":"form","sessionId":"omp-fixture","message":"Allow tool: write\\nPath: approved.txt\\nContent: %s","requestedSchema":{"type":"object","properties":{"value":{"type":"string","enum":["Approve","Deny"]}},"required":["value"]}}}\n' "$OPENAI_API_KEY"
  IFS= read -r response
  printf '%s\n' "$response" > approval-response.json
  case "$response" in *'"action":"accept"'*'"value":"Approve"'*) ;; *) exit 94 ;; esac
fi
touch approved.txt
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"tool_call_update","toolCallId":"omp-write","status":"completed","rawOutput":"created approved.txt"}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"usage_update","used":999,"size":10000,"cost":{"currency":"USD","amount":0.012}}}}'
printf '%s\n' '{"jsonrpc":"2.0","method":"session/update","params":{"sessionId":"omp-fixture","update":{"sessionUpdate":"agent_message_chunk","content":{"type":"text","text":"Created approved.txt"}}}}'
printf '{"jsonrpc":"2.0","id":%s,"result":{"stopReason":"end_turn","usage":{"inputTokens":30,"outputTokens":10,"totalTokens":40}}}\n' "$prompt_id"
