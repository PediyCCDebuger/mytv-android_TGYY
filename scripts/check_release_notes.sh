#!/usr/bin/env bash
# 发布说明脱敏检查 —— 发布前必须通过
# 用法: scripts/check_release_notes.sh .release-notes/v2.0.16.txt
#
# 规则：公开的 Release 说明 / 附注 tag 只描述"解决了什么问题"，
# 不得出现任何 IP、设备型号、内网地址、运营商、地区、测试环境等可定位到个人的信息。

set -u
f="${1:-}"
if [ -z "$f" ] || [ ! -f "$f" ]; then
  echo "用法: $0 <release-notes-file>" >&2
  exit 2
fi

fail=0
report() { echo "  [命中] $1 -> $2" >&2; fail=1; }

# IPv4（含内网/公网）
while IFS= read -r m; do report "IPv4 地址" "$m"; done < <(grep -oE '\b([0-9]{1,3}\.){3}[0-9]{1,3}\b' "$f" || true)
# IPv6（压缩写法和完整写法）
while IFS= read -r m; do report "IPv6 地址" "$m"; done < <(grep -oE '\b([0-9a-fA-F]{0,4}:){2,7}[0-9a-fA-F]{0,4}\b' "$f" | grep ':' || true)
# MAC
while IFS= read -r m; do report "MAC 地址" "$m"; done < <(grep -oiE '\b([0-9a-f]{2}:){5}[0-9a-f]{2}\b' "$f" || true)
# 设备型号 / 硬件标识
while IFS= read -r m; do report "设备型号" "$m"; done < <(grep -oiE '\b(E900[A-Z0-9]*|MGV\d+|HK1[A-Z0-9]*|N1[A-Z0-9]*|S905[A-Z0-9]*|RK3\d{3}[A-Z]*)\b' "$f" || true)
# 环境/设备口语描述
while IFS= read -r m; do report "测试环境描述" "$m"; done < <(grep -oE '(机顶盒|盒子|光猫|路由器|电视型号|我家|本机实测|出口 ?IP|宽带运营商|移动/联通/电信)' "$f" || true)
# 地区/小区/门牌类
while IFS= read -r m; do report "地理位置" "$m"; done < <(grep -oE '(省|市|区|县|小区|号楼)[^，。、\n]{0,6}(宽带|网络|出口|机房)' "$f" || true)

if [ "$fail" -ne 0 ]; then
  echo "" >&2
  echo "发布说明未通过脱敏检查：请删除上述内容，只保留问题现象与修复方式。" >&2
  exit 1
fi
echo "脱敏检查通过：$f"
