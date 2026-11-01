#!/usr/bin/env bash
# 生成只用于编译的 google-services 占位配置（不含真实 Firebase 项目），
# 同时覆盖 release 包名与 debug 包名；格式满足 Firebase SDK 的初始化校验。
set -euo pipefail
client() {
  printf '{"client_info":{"mobilesdk_app_id":"1:000000000000:android:%s","android_client_info":{"package_name":"%s"}},"oauth_client":[],"api_key":[{"current_key":"AIzaSyA00000000000000000000000000000000"}],"services":{"appinvite_service":{"other_platform_oauth_client":[]}}}' "$2" "$1"
}
json=$(printf '{"project_info":{"project_number":"000000000000","project_id":"rikkahub-fork-local","storage_bucket":"rikkahub-fork-local.appspot.com"},"client":[%s,%s],"configuration_version":"1"}' \
  "$(client me.rerere.rikkahub.fork 0000000000000001)" \
  "$(client me.rerere.rikkahub.fork.debug 0000000000000002)")
mkdir -p app/src/debug
printf '%s\n' "$json" > app/google-services.json
printf '%s\n' "$json" > app/src/debug/google-services.json
python3 -c 'import json,sys; [json.load(open(p)) for p in sys.argv[1:]]' app/google-services.json app/src/debug/google-services.json
echo "placeholder google-services.json written"
