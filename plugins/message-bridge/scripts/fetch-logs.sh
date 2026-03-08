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
#   # 抓取特定时间范围并导出为 JSON（输出时间戳为本地时间+时区偏移）
#   ./scripts/fetch-logs.sh --since "2026-03-08 10:00:00" --until "2026-03-08 11:00:00" --format json --output ./logs/incident.json
#

set -euo pipefail

# 默认配置
DEFAULT_LOG_DIR="${HOME}/.local/share/opencode/log"
DEFAULT_FORMAT="table"
DEFAULT_SERVICE="message-bridge"
DEFAULT_LIMIT="10000"

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
DATE_FLAVOR=""
LEVEL_FILTER=""

usage() {
  cat << 'EOF'
使用方法: fetch-logs.sh [OPTIONS]

选项:
  --log-dir DIR           日志文件目录 (默认: ~/.local/share/opencode/log)
  --since DATETIME        开始时间 (按本地时区解析，支持 "1 hour ago", "2026-03-08 10:00:00")
  --until DATETIME        结束时间 (按本地时区解析，默认: now)
  --level LEVELS          日志级别过滤 (逗号分隔: debug,info,warn,error; 默认: 全部)
  --trace-id ID           根据 traceId 追踪链路
  --session-id ID         根据 sessionId 过滤
  --message-pattern REGEX 消息内容正则匹配
  --service NAME          服务名过滤 (默认: message-bridge)
  --limit N               最大返回条数 (默认: 10000)
  --format FORMAT         输出格式: json|table|raw (默认: table)
  --output FILE           输出到文件 (默认: stdout)
  --no-color              禁用颜色输出
  -h, --help              显示帮助信息

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

if [[ ! -d "${LOG_DIR}" ]]; then
  echo "错误: 日志目录不存在: ${LOG_DIR}" >&2
  exit 1
fi

detect_date_flavor() {
  if date -j -f '%Y-%m-%d %H:%M:%S' '2026-03-08 00:00:00' +%s >/dev/null 2>&1; then
    DATE_FLAVOR="bsd"
  else
    DATE_FLAVOR="gnu"
  fi
}

uppercase() {
  printf '%s' "$1" | tr '[:lower:]' '[:upper:]'
}

normalize_level_filter() {
  local raw_level=""

  LEVEL_FILTER=""
  if [[ -z "${LEVEL}" ]]; then
    return
  fi

  IFS=',' read -r -a raw_levels <<< "${LEVEL}"
  for raw_level in "${raw_levels[@]}"; do
    raw_level=$(printf '%s' "${raw_level}" | tr -d '[:space:]')
    if [[ -z "${raw_level}" ]]; then
      continue
    fi
    LEVEL_FILTER="${LEVEL_FILTER},$(uppercase "${raw_level}")"
  done

  LEVEL_FILTER="${LEVEL_FILTER},"
}

normalize_offset_datetime() {
  local input="$1"

  if [[ "${input}" =~ Z$ ]]; then
    printf '%s+0000\n' "${input%Z}"
    return
  fi

  if [[ "${input}" =~ ^(.*)([+-][0-9]{2}):([0-9]{2})$ ]]; then
    printf '%s%s%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    return
  fi

  printf '%s\n' "${input}"
}

parse_local_datetime_to_timestamp() {
  local input="$1"
  local default="${2:-}"
  local normalized_input=""
  local format=""
  local formats=(
    '%Y-%m-%d %H:%M:%S'
    '%Y-%m-%d %H:%M'
    '%Y-%m-%dT%H:%M:%S'
    '%Y-%m-%dT%H:%M'
    '%Y-%m-%d %H:%M:%S%z'
    '%Y-%m-%d %H:%M%z'
    '%Y-%m-%dT%H:%M:%S%z'
    '%Y-%m-%dT%H:%M%z'
  )

  if [[ -z "${input}" ]]; then
    printf '%s\n' "${default}"
    return
  fi

  if [[ "${input}" =~ ^([0-9]+)[[:space:]]+(minute|hour|day|week|month)s?[[:space:]]+ago$ ]]; then
    local num="${BASH_REMATCH[1]}"
    local unit="${BASH_REMATCH[2]}"

    if [[ "${DATE_FLAVOR}" == "bsd" ]]; then
      case "${unit}" in
        minute) date -v-"${num}"M +%s 2>/dev/null || printf '%s\n' "${default}" ;;
        hour)   date -v-"${num}"H +%s 2>/dev/null || printf '%s\n' "${default}" ;;
        day)    date -v-"${num}"d +%s 2>/dev/null || printf '%s\n' "${default}" ;;
        week)   date -v-"${num}"w +%s 2>/dev/null || printf '%s\n' "${default}" ;;
        month)  date -v-"${num}"m +%s 2>/dev/null || printf '%s\n' "${default}" ;;
      esac
      return
    fi

    case "${unit}" in
      minute) date -d "${num} minute ago" +%s 2>/dev/null || printf '%s\n' "${default}" ;;
      hour)   date -d "${num} hour ago" +%s 2>/dev/null || printf '%s\n' "${default}" ;;
      day)    date -d "${num} day ago" +%s 2>/dev/null || printf '%s\n' "${default}" ;;
      week)   date -d "${num} week ago" +%s 2>/dev/null || printf '%s\n' "${default}" ;;
      month)  date -d "${num} month ago" +%s 2>/dev/null || printf '%s\n' "${default}" ;;
    esac
    return
  fi

  normalized_input=$(normalize_offset_datetime "${input}")

  if [[ "${DATE_FLAVOR}" == "bsd" ]]; then
    for format in "${formats[@]}"; do
      if date -j -f "${format}" "${normalized_input}" +%s 2>/dev/null; then
        return
      fi
    done
    printf '%s\n' "${default}"
    return
  fi

  date -d "${input}" +%s 2>/dev/null || printf '%s\n' "${default}"
}

parse_log_timestamp_to_epoch() {
  local input="$1"
  local normalized_input=""

  if [[ -z "${input}" ]]; then
    return
  fi

  normalized_input=$(normalize_offset_datetime "${input}")

  if [[ "${normalized_input}" =~ [+-][0-9]{4}$ ]]; then
    if [[ "${DATE_FLAVOR}" == "bsd" ]]; then
      date -j -f '%Y-%m-%dT%H:%M:%S%z' "${normalized_input}" +%s 2>/dev/null || true
      return
    fi

    date -d "${input}" +%s 2>/dev/null || true
    return
  fi

  if [[ "${DATE_FLAVOR}" == "bsd" ]]; then
    date -j -u -f '%Y-%m-%dT%H:%M:%S' "${input}" +%s 2>/dev/null || true
    return
  fi

  TZ=UTC date -d "${input}" +%s 2>/dev/null || true
}

extract_timestamp() {
  local line="$1"

  if [[ "${line}" =~ ([0-9]{4}-[0-9]{2}-[0-9]{2}T[0-9]{2}:[0-9]{2}:[0-9]{2}((Z)|([+-][0-9]{2}:?[0-9]{2}))?) ]]; then
    printf '%s\n' "${BASH_REMATCH[1]}"
  fi
}

format_offset_with_colon() {
  local value="$1"

  if [[ "${value}" =~ ^(.*)([+-][0-9]{2})([0-9]{2})$ ]]; then
    printf '%s%s:%s\n' "${BASH_REMATCH[1]}" "${BASH_REMATCH[2]}" "${BASH_REMATCH[3]}"
    return
  fi

  printf '%s\n' "${value}"
}

format_epoch_as_local_timestamp() {
  local timestamp="$1"
  local formatted=""

  if [[ "${DATE_FLAVOR}" == "bsd" ]]; then
    formatted=$(date -r "${timestamp}" '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || true)
  else
    formatted=$(date -d "@${timestamp}" '+%Y-%m-%dT%H:%M:%S%z' 2>/dev/null || true)
  fi

  if [[ -z "${formatted}" ]]; then
    return
  fi

  format_offset_with_colon "${formatted}"
}

normalize_line_timestamp() {
  local line="$1"
  local original_timestamp=""
  local epoch=""
  local local_timestamp=""

  original_timestamp=$(extract_timestamp "${line}")
  if [[ -z "${original_timestamp}" ]]; then
    printf '%s\n' "${line}"
    return
  fi

  epoch=$(parse_log_timestamp_to_epoch "${original_timestamp}")
  if [[ -z "${epoch}" ]]; then
    printf '%s\n' "${line}"
    return
  fi

  local_timestamp=$(format_epoch_as_local_timestamp "${epoch}")
  if [[ -z "${local_timestamp}" ]]; then
    printf '%s\n' "${line}"
    return
  fi

  printf '%s\n' "${line/${original_timestamp}/${local_timestamp}}"
}

json_escape() {
  local value="$1"
  value=${value//\\/\\\\}
  value=${value//\"/\\\"}
  value=${value//$'\n'/\\n}
  value=${value//$'\r'/\\r}
  value=${value//$'\t'/\\t}
  printf '%s' "${value}"
}

stat_mtime() {
  local file="$1"
  if [[ "${DATE_FLAVOR}" == "bsd" ]]; then
    stat -f '%m' "${file}"
  else
    stat -c '%Y' "${file}"
  fi
}

collect_log_files() {
  find "${LOG_DIR}" -name "*.log" -type f -print 2>/dev/null | while IFS= read -r file; do
    printf '%s\t%s\n' "$(stat_mtime "${file}")" "${file}"
  done | sort -rn | cut -f2-
}

red() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[31m%s\033[0m' "$1"; }
green() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[32m%s\033[0m' "$1"; }
yellow() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[33m%s\033[0m' "$1"; }
blue() { [[ "${NO_COLOR}" == "true" ]] && echo "$1" || printf '\033[34m%s\033[0m' "$1"; }

filter_logs() {
  local log_files=()
  local log_file=""
  local since_ts=""
  local until_ts=""
  local count=0
  local line=""

  while IFS= read -r file; do
    log_files+=("$file")
  done < <(collect_log_files)

  if [[ ${#log_files[@]} -eq 0 ]]; then
    echo "未找到日志文件" >&2
    return
  fi

  if [[ -n "${SINCE}" ]]; then
    since_ts=$(parse_local_datetime_to_timestamp "${SINCE}" "")
    if [[ -z "${since_ts}" ]]; then
      echo "错误: 无法解析 --since 时间: ${SINCE}" >&2
      exit 1
    fi
  fi

  if [[ -n "${UNTIL}" ]]; then
    until_ts=$(parse_local_datetime_to_timestamp "${UNTIL}" "")
    if [[ -z "${until_ts}" ]]; then
      echo "错误: 无法解析 --until 时间: ${UNTIL}" >&2
      exit 1
    fi
  else
    until_ts=$(date +%s)
  fi

  for log_file in "${log_files[@]}"; do
    if [[ ${count} -ge ${LIMIT} ]]; then
      break
    fi

    while IFS= read -r line; do
      local line_level="${line%% *}"
      local line_ts_str=""
      local line_ts=""

      if [[ ${count} -ge ${LIMIT} ]]; then
        break
      fi

      if [[ "${line}" != *"service=${SERVICE}"* ]]; then
        continue
      fi

      if [[ -n "${LEVEL}" ]] && [[ "${LEVEL_FILTER}" != *",$(uppercase "${line_level}"),"* ]]; then
        continue
      fi

      if [[ -n "${TRACE_ID}" ]] && [[ "${line}" != *"${TRACE_ID}"* ]]; then
        continue
      fi

      if [[ -n "${SESSION_ID}" ]] && [[ "${line}" != *"${SESSION_ID}"* ]]; then
        continue
      fi

      if [[ -n "${MESSAGE_PATTERN}" ]] && ! echo "${line}" | grep -qE "${MESSAGE_PATTERN}"; then
        continue
      fi

      if [[ -n "${since_ts}" ]] || [[ -n "${until_ts}" ]]; then
        line_ts_str=$(extract_timestamp "${line}")
        if [[ -n "${line_ts_str}" ]]; then
          line_ts=$(parse_log_timestamp_to_epoch "${line_ts_str}")

          if [[ -n "${since_ts}" ]] && [[ -n "${line_ts}" ]] && [[ ${line_ts} -lt ${since_ts} ]]; then
            continue
          fi

          if [[ -n "${until_ts}" ]] && [[ -n "${line_ts}" ]] && [[ ${line_ts} -gt ${until_ts} ]]; then
            continue
          fi
        fi
      fi

      echo "${line}"
      count=$((count + 1))
    done < "${log_file}"
  done
}

format_json() {
  local first=true
  local line=""

  echo "["
  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" ]] && continue
    line=$(normalize_line_timestamp "${line}")
    if [[ "${first}" == "true" ]]; then
      first=false
    else
      echo ","
    fi
    printf '{"raw":"%s"}' "$(json_escape "${line}")"
  done
  printf '\n]\n'
}

format_table() {
  local line=""

  while IFS= read -r line || [[ -n "${line}" ]]; do
    local level="${line%% *}"
    local level_colored="${level}"
    local display_line=""

    [[ -z "${line}" ]] && continue
    line=$(normalize_line_timestamp "${line}")

    case "${level}" in
      ERROR) level_colored=$(red "${level}") ;;
      WARN) level_colored=$(yellow "${level}") ;;
      INFO) level_colored=$(green "${level}") ;;
      DEBUG) level_colored=$(blue "${level}") ;;
    esac

    display_line="${line/${level}/${level_colored}}"
    echo "${display_line}"
  done
}

format_raw() {
  local line=""

  while IFS= read -r line || [[ -n "${line}" ]]; do
    [[ -z "${line}" ]] && continue
    normalize_line_timestamp "${line}"
  done
}

print_stats() {
  local log_count="$1"

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

main() {
  local filtered_logs=""
  local log_count=0
  local output_content=""

  detect_date_flavor
  normalize_level_filter

  if [[ -n "${OUTPUT}" ]]; then
    NO_COLOR="true"
  fi

  filtered_logs=$(filter_logs)

  if [[ -n "${filtered_logs}" ]]; then
    log_count=$(printf '%s\n' "${filtered_logs}" | wc -l | tr -d ' ')
  fi

  case "${FORMAT}" in
    json)
      output_content=$(printf '%s\n' "${filtered_logs}" | format_json)
      ;;
    table)
      output_content=$(printf '%s\n' "${filtered_logs}" | format_table)
      ;;
    raw)
      output_content=$(printf '%s\n' "${filtered_logs}" | format_raw)
      ;;
    *)
      echo "错误: 不支持的格式: ${FORMAT}" >&2
      exit 1
      ;;
  esac

  if [[ -n "${OUTPUT}" ]]; then
    mkdir -p "$(dirname "${OUTPUT}")"
    printf '%s\n' "${output_content}" > "${OUTPUT}"
    echo "日志已保存到: ${OUTPUT}"
    print_stats "${log_count}" >&2
    return
  fi

  printf '%s\n' "${output_content}"
  print_stats "${log_count}" >&2
}

main "$@"
