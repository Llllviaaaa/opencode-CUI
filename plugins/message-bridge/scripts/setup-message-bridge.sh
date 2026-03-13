#!/usr/bin/env bash
set -eu

PLUGIN_NAME='@opencode-cui/message-bridge'
SCRIPT_DIR=$(CDPATH= cd -- "$(dirname -- "$0")" && pwd)
ROOT_DIR=$(CDPATH= cd -- "$SCRIPT_DIR/.." && pwd)

print_info() {
  printf '%s\n' "$1"
}

print_error() {
  printf '错误: %s\n' "$1" >&2
}

redact() {
  value=$1
  length=${#value}
  if [ "$length" -le 4 ]; then
    printf '****'
    return
  fi
  printf '%s****%s' "$(printf '%s' "$value" | cut -c1-2)" "$(printf '%s' "$value" | rev | cut -c1-2 | rev)"
}

json_escape() {
  printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g'
}

validate_jsonish_file() {
  path=$1
  if [ ! -f "$path" ]; then
    return 0
  fi

  first=$(awk '/^[[:space:]]*$/ { next } { print; exit }' "$path")
  last=$(awk '/^[[:space:]]*$/ { next } { lines[++count] = $0 } END { if (count) print lines[count] }' "$path")

  printf '%s' "$first" | grep -q '{' || return 1
  printf '%s' "$last" | grep -q '}' || return 1
}

choose_existing_path() {
  jsonc_path=$1
  json_path=$2
  if [ -f "$jsonc_path" ]; then
    printf '%s' "$jsonc_path"
  elif [ -f "$json_path" ]; then
    printf '%s' "$json_path"
  else
    printf '%s' "$jsonc_path"
  fi
}

prompt_value() {
  label=$1
  current=${2:-}
  if [ -n "$current" ]; then
    printf '%s [%s]: ' "$label" "$(redact "$current")" >&2
  else
    printf '%s: ' "$label" >&2
  fi
  IFS= read -r value
  if [ -n "${value## }" ] && [ -n "${value}" ]; then
    printf '%s' "$value"
  else
    printf '%s' "$current"
  fi
}

prompt_secret() {
  label=$1
  current=${2:-}
  if [ -n "$current" ]; then
    printf '%s [%s]: ' "$label" "$(redact "$current")" >&2
  else
    printf '%s: ' "$label" >&2
  fi
  if [ -t 0 ]; then
    stty -echo
  fi
  IFS= read -r value
  if [ -t 0 ]; then
    stty echo
  fi
  printf '\n' >&2
  if [ -n "${value## }" ] && [ -n "${value}" ]; then
    printf '%s' "$value"
  else
    printf '%s' "$current"
  fi
}

confirm() {
  prompt=$1
  printf '%s [y/N]: ' "$prompt"
  IFS= read -r answer
  answer=$(printf '%s' "$answer" | tr '[:upper:]' '[:lower:]')
  [ "$answer" = 'y' ] || [ "$answer" = 'yes' ]
}

ensure_parent_dir() {
  mkdir -p -- "$(dirname -- "$1")"
}

write_file() {
  path=$1
  content=$2
  tmp=$(mktemp)
  printf '%s' "$content" > "$tmp"
  mv "$tmp" "$path"
}

upsert_bridge_config() {
  path=$1
  ak=$2
  sk=$3
  escaped_ak=$(json_escape "$ak")
  escaped_sk=$(json_escape "$sk")

  if [ ! -f "$path" ]; then
    write_file "$path" "{
  \"auth\": {
    \"ak\": \"$escaped_ak\",
    \"sk\": \"$escaped_sk\"
  }
}
"
    return
  fi

  validate_jsonish_file "$path" || {
    print_error "无法安全解析现有 bridge 配置：$path"
    exit 1
  }

  MB_ESCAPED_AK=$escaped_ak MB_ESCAPED_SK=$escaped_sk perl -0pe '
    my $ak = $ENV{MB_ESCAPED_AK};
    my $sk = $ENV{MB_ESCAPED_SK};

    if (/"auth"\s*:/s) {
      my $has_ak = s/"ak"\s*:\s*"[^"]*"/"ak": "$ak"/s;
      my $has_sk = s/"sk"\s*:\s*"[^"]*"/"sk": "$sk"/s;
      if (!$has_ak) {
        s/("auth"\s*:\s*\{)/$1\n    "ak": "$ak",/s;
      }
      if (!$has_sk) {
        s/("auth"\s*:\s*\{[\s\S]*?)(\n\s*\})/$1\n    "sk": "$sk"$2/s;
      }
    } else {
      s/\n\}\s*$/,\n  "auth": {\n    "ak": "$ak",\n    "sk": "$sk"\n  }\n}\n/s;
    }
  ' "$path" > "$path.tmp"

  mv "$path.tmp" "$path"
}

upsert_opencode_config() {
  path=$1

  if [ ! -f "$path" ]; then
    write_file "$path" "{
  \"\$schema\": \"https://opencode.ai/config.json\",
  \"plugin\": [\"$PLUGIN_NAME\"]
}
"
    return
  fi

  validate_jsonish_file "$path" || {
    print_error "无法安全解析现有 OpenCode 配置：$path"
    exit 1
  }

  if grep -q "\"$PLUGIN_NAME\"" "$path"; then
    return
  fi

  MB_PLUGIN_NAME=$PLUGIN_NAME perl -0pe '
    my $plugin = $ENV{MB_PLUGIN_NAME};

    if (/"plugin"\s*:\s*\[/s) {
      s/"plugin"\s*:\s*\[\s*\]/"plugin": ["$plugin"]/s
        or s/("plugin"\s*:\s*\[)([\s\S]*?)(\])/
          my ($start, $items, $end) = ($1, $2, $3);
          $items =~ s/\s+$//;
          my $separator = $items =~ /\S/ ? ", " : "";
          $start . $items . $separator . "\"$plugin\"" . $end;
        /es;
    } else {
      s/\n\}\s*$/,\n  "plugin": ["$plugin"]\n}\n/s;
    }
  ' "$path" > "$path.tmp"

  mv "$path.tmp" "$path"
}

scope='user'
if [ "${1:-}" = '--scope' ]; then
  case "${2:-}" in
    user|project) scope=$2 ;;
    *)
      print_error '--scope 仅支持 user 或 project'
      exit 1
      ;;
  esac
fi

if [ "$scope" = 'user' ]; then
  config_dir=${XDG_CONFIG_HOME:-"$HOME/.config"}/opencode
  opencode_config=$(choose_existing_path "$config_dir/opencode.jsonc" "$config_dir/opencode.json")
else
  config_dir="$PWD/.opencode"
  opencode_config=$(choose_existing_path "$PWD/opencode.jsonc" "$PWD/opencode.json")
fi

bridge_config=$(choose_existing_path "$config_dir/message-bridge.jsonc" "$config_dir/message-bridge.json")
package_json="$config_dir/package.json"

current_ak=''
current_sk=''
if [ -f "$bridge_config" ]; then
  current_ak=$(sed -n 's/.*"ak"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$bridge_config" | head -n 1)
  current_sk=$(sed -n 's/.*"sk"[[:space:]]*:[[:space:]]*"\([^"]*\)".*/\1/p' "$bridge_config" | head -n 1)
fi

print_info 'Message Bridge 初始化'
print_info "作用域: $scope"
print_info "bridge 配置: $bridge_config"
print_info "OpenCode 配置: $opencode_config"
printf '\n'

ak=$(prompt_value '请输入 AK' "$current_ak")
if [ -z "$ak" ]; then
  print_error 'AK 不能为空'
  exit 1
fi

sk=$(prompt_secret '请输入 SK' "$current_sk")
if [ -z "$sk" ]; then
  print_error 'SK 不能为空'
  exit 1
fi

printf '\n'
print_info '将写入以下内容：'
print_info "- bridge auth.ak: $(redact "$ak")"
print_info "- bridge auth.sk: $(redact "$sk")"
print_info "- OpenCode plugin: $PLUGIN_NAME"
printf '\n'

if ! confirm '确认写入以上配置'; then
  print_info '已取消，未写入任何文件。'
  exit 0
fi

ensure_parent_dir "$bridge_config"
ensure_parent_dir "$opencode_config"

upsert_bridge_config "$bridge_config" "$ak" "$sk"
upsert_opencode_config "$opencode_config"

print_info '配置完成。'
print_info "1. 已写入 $bridge_config"
print_info "2. 已更新 $opencode_config"
print_info '3. 下次启动 OpenCode 时会自动安装并加载 npm 插件。'
