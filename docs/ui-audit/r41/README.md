# R41 · P3 第一批（真机 MEIZU 21）

本轮完成 P3 可选增强里的三项：

## 1. 复制 / 分享正文

- 阅读页「分享」面板底部新增「复制文字 / 分享文字」，不依赖长图渲染；
- 纯文本由 `DiaryText` 生成：标题 + 段落、列表 `• `、待办 `☐/☑ `、引用 `> `、图片 `[图片]`，
  片段之间空一行；`DiaryTextTest` 覆盖。
- 证据：`01-share-text-actions.png`、`02-share-text-intent.png`（系统分享面板）。

## 2. 搜索历史

- `SearchHistoryStore`（SharedPreferences + JSON）：最多 10 条、去重置顶、可清除；
  `SearchHistoryStoreRobolectricTest` 覆盖。
- 无条件引导态显示「最近搜索」与「清除」，点 chip 直接搜索；
- 证据：`03-search-history.png`、`04-search-history-cleared.png`。

## 3. 多图一次插入

- 编辑器图片入口改 `PickMultipleVisualMedia(9)`；按选择顺序逐张导入、逐张成行
  （一行一张图，与 iOS 一致），任一失败只提示一次；
- 真机选择 2 张后回到编辑器（`05-multi-image-editor.png`，选择器状态 `06-multi-image-picker.xml`）。

