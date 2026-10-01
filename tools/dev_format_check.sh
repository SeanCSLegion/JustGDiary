#!/bin/bash
# 日记编辑态「格式栏按钮」真机验证（用户 2026-09-28 六条规则）
# 用法：bash tools/dev_format_check.sh <步骤名>
# 每个子命令都打印：光标几何（帧差）+ EditText 文本（uiautomator）
set -u
ADB=/Users/cov/Library/Android/sdk/platform-tools/adb
PY=/Users/cov/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/bin/python3
export PYTHONPATH=/Users/cov/.dsh/dsh-runtimes/dsh-primary-runtime/dependencies/python/lib/python3.12/site-packages
ROOT=/Users/cov/JustGDiary
EVID=$ROOT/docs/ui-audit/r31
mkdir -p "$EVID"

# 格式栏按钮中心（由 uiautomator dump 的真实 bounds 得来，见 04-diary.md §3.3）
MENU="90 1319"; CENTER="215 1319"; LIST="340 1319"; QUOTE="450 1319"; TODO="560 1319"
BOLD="685 1319"; ITALIC="795 1319"; STRIKE="905 1319"; UNDERLINE="1009 1319"
# 段落样式菜单三项
M_TITLE="200 850"; M_HEADING="200 970"; M_BODY="200 1090"

tap() { $ADB shell input tap $1 $2; sleep 0.6; }
shot() { $ADB shell screencap -p /sdcard/e.png; $ADB pull /sdcard/e.png "$EVID/$1.png" >/dev/null; }

caret() {
  for i in 1 2 3; do
    $ADB shell screencap -p /sdcard/c$i.png >/dev/null
    $ADB pull /sdcard/c$i.png /tmp/c$i.png >/dev/null
    sleep 0.22
  done
  echo -n "  光标："
  $PY $ROOT/tools/dev_caret.py /tmp/c1.png /tmp/c2.png /tmp/c3.png --region=70,505,1010,1180
}

etext() {
  $ADB shell uiautomator dump /sdcard/d.xml >/dev/null 2>&1
  $ADB shell cat /sdcard/d.xml | $PY -c "
import sys,re
s=sys.stdin.read()
for m in re.finditer(r'<node[^>]*>', s):
    t=m.group(0)
    if 'EditText' in t:
        txt=re.search(r'text=\"([^\"]*)\"',t); b=re.search(r'bounds=\"([^\"]*)\"',t)
        print('  文本：', repr(txt.group(1)) if txt else '', b.group(1) if b else '')
        break
"
}

enter_editor() {
  $ADB shell am force-stop com.cov.justdiary; sleep 1
  $ADB shell am start -a android.intent.action.VIEW -d "justdiary://editor/2026-09-28" >/dev/null; sleep 3
  tap 871 180   # 顶栏「写日记」
  sleep 2
}


step_body() {
  enter_editor
  echo "== 正文（空行，光标应 ~65px，靠左） =="
  caret; etext; shot r31-empty-body
}

step_title() {
  echo "== 大标题（光标应变高） =="
  tap $MENU; tap $M_TITLE
  caret; etext; shot r31-empty-title
}

step_heading() {
  echo "== 小标题 =="
  tap $MENU; tap $M_HEADING
  caret; etext; shot r31-empty-heading
}

step_quote() {
  echo "== 引用（14sp，光标应更矮） =="
  tap $QUOTE
  caret; etext; shot r31-empty-quote
}

step_center_empty() {
  echo "== 居中（空行 → 光标应在行中央 ~540） =="
  tap $CENTER
  caret; etext; shot r31-empty-centered
}

step_todo() {
  echo "== 待办 =="
  tap $TODO
  caret; etext; shot r31-empty-todo
}

step_list() {
  echo "== 列表 =="
  tap $LIST
  caret; etext; shot r31-empty-list
}


step_center_on_quote() {
  echo "== 规则 5：引用行上点居中（按钮应置灰 → 光标不动） =="
  tap $CENTER
  caret; etext; shot r31-center-disabled-on-quote
}

step_quote_off_then_center() {
  echo "== 取消引用，再居中（空行 → 光标应到中央） =="
  tap $QUOTE
  tap $CENTER
  caret; etext; shot r31-empty-centered
}


step_mutex_list_then_title() {
  enter_editor
  echo "== 规则 2/3/5：正文（居中）→ 列表 → 大标题 =="
  $ADB shell input text "jia-yi-bing"; sleep 0.6
  echo "-- 输入后 --"; caret; etext
  tap $LIST
  echo "-- 点列表（应出现项目符号，且居中按钮置灰） --"; caret; etext; shot r31-list-on-centered
  tap $MENU; tap $M_TITLE
  echo "-- 切大标题（项目符号必须消失，字号变大） --"; caret; etext; shot r31-list-to-title
}

step_enter_blocked() {
  enter_editor
  echo "== 规则 4：空行上再敲回车不执行 =="
  $ADB shell input text "zhengwen"; sleep 0.7
  $ADB shell input keyevent 66; sleep 0.7
  echo "-- 第 1 次回车（本行有内容 → 放行） --"; etext
  $ADB shell input keyevent 66; sleep 0.7
  echo "-- 第 2 次回车（上一行有内容 → 放行，留出一个空行） --"; etext
  $ADB shell input keyevent 66; sleep 0.7
  $ADB shell input keyevent 66; sleep 0.7
  echo "-- 第 3、4 次回车（本行空 + 上一行也空 → 不执行） --"; etext; shot r31-blank-line-blocked
}

step_inline_stack() {
  enter_editor
  echo "== 规则 1：B/I/S/U 同时应用（无选区 → 作用于接下来输入的字） =="
  tap $BOLD; tap $ITALIC; tap $STRIKE; tap $UNDERLINE
  $ADB shell input text "BISU"; sleep 0.8
  etext; shot r31-inline-stack
}

"$@"
