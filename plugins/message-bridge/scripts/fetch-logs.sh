#!/usr/bin/env bash
#
# fetch-logs.sh - 从 OpenCode 日志文件抓取 message-bridge 相关日志
#
# 用于生产环境问题排查、链路追踪和日志分析
#
# 使用方法:
#   ./scripts/fetch-logs.sh [OPTIONS]
#
# 示例:
#   # 抓取最近 1 小时的错误日志
#   ./scripts/fetch-logs.sh --since "1 hour ago" --level error
#
#   # 根据 traceId 追踪完整链路
#   ./scripts/fetch-logs.sh --trace-id "abc-123-def" --format table
#
#   # 抓取特定时间范围并导出为 JSON
#   ./scripts/fetch-logs.sh --since "2026-03-08 10:00:00" --until "2026-03-08 11:00:00" --format json --output ./logs/incident.json
#

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PLUGIN_DIR="$(cd "${SCRIPT_DIR}/.." && pwd)"

# 默认配置
DEFAULT_LOG_DIR="${HOME}/.local/share/opencode/log"
DEFAULT_FORMAT="table"
DEFAULT_SERVICE="message-bridge"
DEFAULT_LIMIT="10000"

# 帮助信息
usage() {
  cat << 'EOF'
使用方法: fetch-logs.sh [OPTIONS]

选项:
  --log-dir DIR           日志文件目录 (默认: ~/.local/share/opencode/log)
  --since DATETIME        开始时间 (支持自然语言: "1 hour ago", "2026-03-08 10:00:00")
  --until DATETIME        结束时间 (默认: now)
  --level LEVELS          日志级别过滤 (逗号分隔: debug,info,warn,error; 默认: 全部)
  --trace-id ID          根据 traceId 追踪链路
  --session-id ID        根据 sessionId 过滤
  --message-pattern REGEX 消息内容正则匹配
  --service NAME         服务名过滤 (默认: message-bridge)
  --limit N              最大返回条数 (默认: 10000)
  --format FORMAT        输出格式: json|table|raw (默认: table)
  --output FILE          输出到文件 (默认: stdout)
  --no-color             禁用颜色输出
  -h, --help             显示帮助信息

示例:
  # 抓取最近 30 分钟的所有日志
  ./scripts/fetch-logs.sh --since "30 minutes ago"

  # 抓取错误和警告日志，以 JSON 格式输出
  ./scripts/fetch-logs.sh --since "1 hour ago" --level error,warn --format json

  # 追踪特定 traceId 的完整链路
  ./scripts/fetch-logs.sh --trace-id "abc-123-def" --format table

  # 根据 sessionId 查找相关日志
  ./scripts/fetch-logs.sh --session-id "ses_xxx" --since "2026-03-08 10:00"

  # 查找包含 "gateway.ready" 的日志
  ./scripts/fetch-logs.sh --message-pattern "gateway\.ready" --since "1 hour ago"

  # 导出到文件
  ./scripts/fetch-logs.sh --since "2 hours ago" --output ./logs/debug-$(date +%Y%m%d-%H%M%S).json
EOF
}

# 解析参数
LOG_DIR="${DEFAULT_LOG_DIR}"
SINCE=""
UNTIL=""
LEVEL=""
TRACE_ID=""
SESSION_ID=""
MESSAGE_PATTERN=""
SERVICE="${DEFAULT_SERVICE}"
LIMIT="${DEFAULT_LIMIT}"
FORMAT="${DEFAULT_FORMAT}"
OUTPUT=""
NO_COLOR="false"

while [[ $# -gt 0 ]]; do
  case $1 in
    --log-dir)
      LOG_DIR="$2"
      shift 2
      ;;
    --since)
      SINCE="$2"
      shift 2
      ;;
    --until)
      UNTIL="$2"
      shift 2
      ;;
    --level)
      LEVEL="$2"
      shift 2
      ;;
    --trace-id)
      TRACE_ID="$2"
      shift 2
      ;;
    --session-id)
      SESSION_ID="$2"
      shift 2
      ;;
    --message-pattern)
      MESSAGE_PATTERN="$2"
      shift 2
      ;;
    --service)
      SERVICE="$2"
      shift 2
      ;;
    --limit)
      LIMIT="$2"
      shift 2
      ;;
    --format)
      FORMAT="$2"
      shift 2
      ;;
    --output)
      OUTPUT="$2"
      shift 2
      ;;
    --no-color)
      NO_COLOR="true"
      shift
      ;;
    -h|--help)
      usage
      exit 0
      ;;
    *)
      echo "未知选项: $1" >&2
      usage >&2
      exit 1
      ;;
  esac
done

# 检查日志目录
if [[ ! -d "${LOG_DIR}" ]]; then
  echo "错误: 日志目录不存在: ${LOG_DIR}" >&2
  exit 1
fi

# 将时间字符串转换为 Unix 时间戳 (用于比较)
parse_datetime_to_timestamp() {
  local input="$1"
  local default="$2"

  if [[ -z "${input}" ]]; then
    echo "${default}"
    return
  fi

  # 尝试解析自然语言时间
  if [[ "${input}" =~ ^[0-9]+[[:space:]]+(minute|hour|day|week|month)s?[[:space:]]+ago$ ]]; then
    local num=$(echo "${input}" | grep -o '^[0-9]*')
    local unit=$(echo "${input}" | grep -oE '(minute|hour|day|week|month)')

    # macOS BSD date 语法
    case "${unit}" in
      minute) date -v-${num}M +%s 2>/dev/null || echo "${default}" ;;
      hour)   date -v-${num}H +%s 2>/dev/null || echo "${default}" ;;
      day)    date -v-${num}d +%s 2>/dev/null || echo "${default}" ;;
      week)   date -v-${num}w +%s 2>/dev/null || echo "${default}" ;;
      month)  date -v-${num}m +%s 2>/dev/null || echo "${default}" ;;
    esac
  else
    # 尝试解析 ISO 8601 格式或其他格式
    date -j -f '%Y-%m-%d %H:%M:%S' "${input}" +%s 2>/dev/null || \
    date -j -f '%Y-%m-%dT%H:%M:%S' "${input}" +%s 2>/dev/null || \
    echo "${default}"
  fi
}

# 从日志行中提取时间戳 (格式: 2026-03-07T02:50:31)
extract_timestamp() {
  local line="$1"
  # 提取 ISO 8601 格式的时间戳
  echo "${line}" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}' | head -1
}

red() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[31m%s\033[0m' "$1"; }
green() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[32m%s\033[0m' "$1"; }
yellow() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[33m%s\033[0m' "$1"; }
blue() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[34m%s\033[0m' "$1"; }

# 过滤和格式化日志
filter_logs() {
  local log_files=()

  # 收集所有 .log 文件，按修改时间排序（最新的在前）
  while IFS= read -r -d '' file; do
    log_files+=("$file")
  done < <(find "${LOG_DIR}" -name "*.log" -type f -print0 2>/dev/null | sort -z -r)

  if [[ ${#log_files[@]} -eq 0 ]]; then
    echo "未找到日志文件" >&2
    return
  fi

  # 解析时间范围
  local since_ts=""
  local until_ts=""

  if [[ -n "${SINCE}" ]]; then
    since_ts=$(parse_datetime_to_timestamp "${SINCE}" "")
  fi

  if [[ -n "${UNTIL}" ]]; then
    until_ts=$(parse_datetime_to_timestamp "${UNTIL}" "")
  else
    until_ts=$(date +%s)  # 默认为当前时间
  fi

  if [[ -n "${UNTIL}" ]]; then
    until_ts=$(parse_datetime_to_timestamp "${UNTIL}" "")
  else
    until_ts=$(date +%s)  # 默认为当前时间
  fi

  # 构建级别过滤正则
  local level_regex=""
  if [[ -n "${LEVEL}" ]]; then
    level_regex=$(echo "${LEVEL}" | sed 's/,/\\|/g')
  fi

  local count=0

  for log_file in "${log_files[@]}"; do
    # 如果已达到限制，停止处理
    if [[ ${count} -ge ${LIMIT} ]]; then
      break
    fi

    while IFS= read -r line; do
      # 如果已达到限制，停止处理
      if [[ ${count} -ge ${LIMIT} ]]; then
        break
      fi

      # 跳过不包含服务名的行
      if [[ ! "${line}" =~ service=${SERVICE} ]]; then
        continue
      fi

      # 级别过滤
      if [[ -n "${LEVEL}" ]]; then
        local line_level=$(echo "${line}" | awk '{print $1}')
        local line_level_upper=$(echo "${line_level}" | tr '[:lower:]' '[:upper:]')
        local level_match=false
        IFS=',' read -ra LEVEL_ARRAY <<< "${LEVEL}"
        for l in "${LEVEL_ARRAY[@]}"; do
          local l_upper=$(echo "${l}" | tr '[:lower:]' '[:upper:]')
          if [[ "${line_level_upper}" == "${l_upper}" ]]; then
            level_match=true
            break
          fi
        done
        if [[ "${level_match}" == "false" ]]; then
          continue
        fi
      fi

      # traceId 过滤
      if [[ -n "${TRACE_ID}" ]]; then
        if [[ "${line}" != *"${TRACE_ID}"* ]]; then
          continue
        fi
      fi

      # sessionId 过滤
      if [[ -n "${SESSION_ID}" ]]; then
        if [[ "${line}" != *"${SESSION_ID}"* ]]; then
          continue
        fi
      fi

      # 消息模式过滤
      if [[ -n "${MESSAGE_PATTERN}" ]]; then
        if ! echo "${line}" | grep -qE "${MESSAGE_PATTERN}"; then
          continue
        fi
      fi

      # 时间范围过滤
      if [[ -n "${since_ts}" ]] || [[ -n "${until_ts}" ]]; then
        local line_ts_str=$(extract_timestamp "${line}")
        if [[ -n "${line_ts_str}" ]]; then
          local line_ts=$(date -j -f '%Y-%m-%dT%H:%M:%S' "${line_ts_str}" +%s 2>/dev/null)

          if [[ -n "${since_ts}" ]] && [[ -n "${line_ts}" ]]; then
            if [[ ${line_ts} -lt ${since_ts} ]]; then
              continue
            fi
          fi

          if [[ -n "${until_ts}" ]] && [[ -n "${line_ts}" ]]; then
            if [[ ${line_ts} -gt ${until_ts} ]]; then
              continue
            fi
          fi
        fi
      fi

      # 输出匹配的行
      echo "${line}"
      ((count++))

    done < "${log_file}"
  done
}

# 格式化输出
format_json() {
  local first=true
  echo "["
  while IFS= read -r line; do
    # 解析日志行
    local level=$(echo "${line}" | awk '{print $1}')
    local timestamp=$(echo "${line}" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}')
    local extra=$(echo "${line}" | grep -oE '\+[^[:space:]]+[[:space:]]+.*' || echo "")
    local message=$(echo "${line}" | sed -E 's/^[A-Z]+[[:space:]]+[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[[:space:]]+//' | sed -E 's/\+[^[:space:]]+[[:space:]]+//' | sed -E "s/service=${SERVICE}[[:space:]]*//")

    if [[ "${first}" == "true" ]]; then
      first=false
    else
      echo ","
    fi

    printf '{"timestamp":"%s","level":"%s","message":"%s","extra":"%s"}' \
      "${timestamp}" "${level}" "${message//"/\\"}" "${extra//"/\\"}"
  done
  echo ""
  echo "]"
}

format_table() {
  while IFS= read -r line; do
    local level=$(echo "${line}" | awk '{print $1}')
    local timestamp=$(echo "${line}" | grep -oE '[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}')
    local extra=$(echo "${line}" | grep -oE '\+[^[:space:]]+' | head -1 || echo "")
    local message=$(echo "${line}" | sed -E 's/^[A-Z]+[[:space:]]+[0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}[[:space:]]+//' | sed -E 's/\+[^[:space:]]+[[:space:]]+//' | sed -E "s/service=${SERVICE}[[:space:]]*//" | sed -E 's/type=object keys=\[[^]]*\] size=[0-9]+[[:space:]]*//')

    local level_colored="${level}"
    case "${level}" in
      ERROR) level_colored=$(red "${level}") ;;
      WARN) level_colored=$(yellow "${level}") ;;
      INFO) level_colored=$(green "${level}") ;;
      DEBUG) level_colored=$(blue "${level}") ;;
    esac

    printf "%-23s | %-7s | %s | %s\n" "${timestamp}" "${level_colored}" "${message}" "${extra}"
  done
}

format_raw() {
  while IFS= read -r line; do
    echo "${line}"
  done
}

# 统计信息
print_stats() {
  local log_count=$1

  echo ""
  echo "=== 统计信息 ==="
  echo "匹配日志条数: ${log_count}"
  echo "日志目录: ${LOG_DIR}"
  echo "过滤服务: ${SERVICE}"

  if [[ -n "${SINCE}" ]]; then
    echo "时间范围 (开始): ${SINCE}"
  fi
  if [[ -n "${UNTIL}" ]]; then
    echo "时间范围 (结束): ${UNTIL}"
  fi
}

# 主逻辑
main() {
  # 收集过滤后的日志
  local filtered_logs
  filtered_logs=$(filter_logs)

  # 统计条数
  local log_count=0
  if [[ -n "${filtered_logs}" ]]; then
    log_count=$(echo "${filtered_logs}" | wc -l)
  fi

  # 格式化输出
  local output_content=""
  case "${FORMAT}" in
    json)
      output_content=$(echo "${filtered_logs}" | format_json)
      ;;
    table)
      output_content=$(echo "${filtered_logs}" | format_table)
      ;;
    raw)
      output_content=$(echo "${filtered_logs}" | format_raw)
      ;;
    *)
      echo "错误: 不支持的格式: ${FORMAT}" >&2
      exit 1
      ;;
  esac

  # 输出
  if [[ -n "${OUTPUT}" ]]; then
    # 确保目录存在
    mkdir -p "$(dirname "${OUTPUT}")"
    echo "${output_content}" > "${OUTPUT}"
    echo "日志已保存到: ${OUTPUT}"

    # 如果有统计信息，也输出到 stderr
    print_stats "${log_count}" >&2
  else
    echo "${output_content}"
    print_stats "${log_count}" >&2
  fi
}

main "$@"
