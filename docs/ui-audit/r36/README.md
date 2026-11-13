# R36 · P1 批量落地的设备证据（2026-11-13）

设备：Pixel_10_Pro AVD（1280×2856，Android 16 / API 36），debug APK，应用内语言中英各走一遍。

| 文件 | 对应项 | 说明 |
| --- | --- | --- |
| `r36-first-launch-notification.png` | P1-7 | 首启先说明用途，再弹系统通知授权；点「暂不」后不再自动弹 |
| `r36-shortcuts.txt` | P1-9 | `cmd shortcut get-shortcuts` 能看到 `write_today` / `search_diary` 两个静态快捷方式与各自深链 |
| `r36-shortcut-search.xml` | P1-9 | 点 `justdiary://search` 后 uiautomator 树；已切到搜索标签 |
| `r36-month-a11y-nodes.xml` | P1-3 | uiautomator dump 打开无障碍通道时，月态出现 30 个逐日节点（11 月）+ 预挂的 12 月 20 个，文案形如 `11月13日 周五 · 今天 · 已选中 · 无日记` |
| `r36-quote-enter.png` | P1-4 | 引用里输入两行后，底仍覆盖两行；空引用行再回车后 `ghi` 已回到正文（截图里 `a` / `d` 是 adb 快速输入只落下的首字母，不影响格式验证） |
| `r36-theme-light-no-rebuild.png` | P1-12 | 系统深色下把应用主题切成 Light：界面立即变浅、系统栏图标同步，logcat 无 `Relaunch` |
| `r36-startup-error.png` | P1-16 | 把 `databases/just_diary.db` 换成同名目录后启动：显示「无法打开日记本 + 重试」，不再展示空日记本 |

补充命令与结果：

- `cmd shortcut get-shortcuts --user 0 com.cov.justdiary` → 两个 shortcut 均 `Not disabled`，intent 为 `justdiary://editor` / `justdiary://search`。
- 语言切回英文：logcat 只有 **1 次** `finishDrawing of relaunch`（`AppCompatDelegate.setApplicationLocales` 的官方重建），重建后仍停在设置页；主题在重建前已对齐，起始窗口不再用旧主题的底色。
- 启动失败恢复：删掉占位目录、把 `just_diary.db.bak` 移回后重新启动，首页正常。
