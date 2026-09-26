/* ==========================================================================
   JustGDiary 设计稿 · Material 3 组件与设备画框构造器
   一切以 HTML 字符串返回，页面加载时同步渲染（headless Chrome 直接截图）。
   ========================================================================== */

/* ---------- Material Symbols 图标路径（24dp viewBox） ---------- */
const ICONS = {
  search: "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14z",
  settings: "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58a.49.49 0 0 0 .12-.61l-1.92-3.32a.488.488 0 0 0-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54a.484.484 0 0 0-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58a.49.49 0 0 0-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6c-1.98 0-3.6-1.62-3.6-3.6s1.62-3.6 3.6-3.6 3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
  back: "M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z",
  chevron_right: "M10 6L8.59 7.41 13.17 12l-4.58 4.59L10 18l6-6z",
  chevron_left: "M15.41 7.41L14 6l-6 6 6 6 1.41-1.41L10.83 12z",
  expand_more: "M16.59 8.59L12 13.17 7.41 8.59 6 10l6 6 6-6z",
  calendar_today: "M20 3h-1V1h-2v2H7V1H5v2H4c-1.1 0-2 .9-2 2v16c0 1.1.9 2 2 2h16c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 18H4V8h16v13z",
  calendar_month: "M19 3h-1V1h-2v2H8V1H6v2H5c-1.11 0-1.99.9-1.99 2L3 19c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm0 16H5V8h14v11zM7 10h5v5H7z",
  walk: "M13.5 5.5c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zM9.8 8.9L7 23h2.1l1.8-8 2.1 2v6h2v-7.5l-2.1-2 .6-3C14.8 12 16.8 13 19 13v-2c-1.9 0-3.5-1-4.3-2.4l-1-1.6c-.4-.6-1-1-1.7-1-.3 0-.5.1-.8.1L6 8.3V13h2V9.6l1.8-.7",
  edit: "M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z",
  share: "M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92 1.61 0 2.92-1.31 2.92-2.92s-1.31-2.92-2.92-2.92z",
  add: "M19 13h-6v6h-2v-6H5v-2h6V5h2v6h6v2z",
  close: "M19 6.41L17.59 5 12 10.59 6.41 5 5 6.41 10.59 12 5 17.59 6.41 19 12 13.41 17.59 19 19 17.59 13.41 12z",
  check: "M9 16.17L4.83 12l-1.42 1.41L9 19 21 7l-1.41-1.41z",
  check_circle: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-2 15l-5-5 1.41-1.41L10 14.17l7.59-7.59L19 8l-9 9z",
  place: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
  schedule: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zM12 20c-4.42 0-8-3.58-8-8s3.58-8 8-8 8 3.58 8 8-3.58 8-8 8zm.5-13H11v6l5.25 3.15.75-1.23-4.5-2.67z",
  image: "M21 19V5c0-1.1-.9-2-2-2H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2zM8.5 13.5l2.5 3.01L14.5 12l4.5 6H5l3.5-4.5z",
  bold: "M15.6 10.79c.97-.67 1.65-1.77 1.65-2.79 0-2.26-1.75-4-4-4H7v14h7.04c2.09 0 3.71-1.7 3.71-3.79 0-1.52-.86-2.82-2.15-3.42zM10 6.5h3c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5h-3v-3zm3.5 9H10v-3h3.5c.83 0 1.5.67 1.5 1.5s-.67 1.5-1.5 1.5z",
  italic: "M10 4v3h2.21l-3.42 8H6v3h8v-3h-2.21l3.42-8H18V4z",
  strikethrough: "M7.24 8.75c-.26-.48-.39-1.03-.39-1.67 0-.61.13-1.16.4-1.67.26-.5.63-.93 1.11-1.29.48-.35 1.05-.63 1.72-.83.66-.19 1.39-.29 2.18-.29.81 0 1.54.11 2.21.34.66.22 1.23.54 1.69.94.47.4.83.88 1.08 1.43.25.55.38 1.15.38 1.81h-3.01c0-.31-.05-.59-.15-.85-.09-.27-.24-.49-.44-.68-.2-.19-.45-.33-.75-.44-.3-.1-.66-.16-1.06-.16-.39 0-.74.04-1.03.13-.29.09-.53.21-.72.36-.19.16-.34.34-.44.55-.1.21-.16.43-.16.66 0 .25.05.48.16.68.11.2.28.39.51.55.23.16.53.31.9.44.36.13.79.26 1.28.38h.03c.34.08.68.17 1.02.28h6.77v3H7.24zM21 14c0 .34-.02.66-.06.97-.04.31-.11.6-.21.87-.1.27-.24.52-.43.75-.19.23-.42.43-.7.6-.28.17-.61.3-.99.4-.38.1-.81.15-1.29.15-.9 0-1.68-.17-2.33-.5-.65-.33-1.16-.78-1.52-1.34-.36-.56-.54-1.2-.54-1.92h3.01c0 .34.07.63.21.86.14.23.33.42.57.55.24.13.52.2.83.2.3 0 .55-.05.76-.14.21-.09.38-.22.51-.38.13-.16.23-.35.29-.56.06-.21.09-.44.09-.68 0-.24-.03-.47-.1-.68-.07-.21-.17-.4-.3-.56-.13-.16-.29-.29-.47-.38H21z",
  underline: "M12 17c3.31 0 6-2.69 6-6V3h-2.5v8c0 1.93-1.57 3.5-3.5 3.5S8.5 12.93 8.5 11V3H6v8c0 3.31 2.69 6 6 6zm-7 2v2h14v-2H5z",
  align_left: "M15 15H3v2h12v-2zm0-8H3v2h12V7zM3 13h18v-2H3v2zm0 8h18v-2H3v2zM3 3v2h18V3H3z",
  align_center: "M7 15v2h10v-2H7zm-4 6h18v-2H3v2zm0-8h18v-2H3v2zm4-6v2h10V7H7zM3 3v2h18V3H3z",
  list: "M4 10.5c-.83 0-1.5.67-1.5 1.5s.67 1.5 1.5 1.5 1.5-.67 1.5-1.5-.67-1.5-1.5-1.5zm0-6c-.83 0-1.5.67-1.5 1.5S3.17 7.5 4 7.5 5.5 6.83 5.5 6 4.83 4.5 4 4.5zm0 12c-.83 0-1.5.68-1.5 1.5s.68 1.5 1.5 1.5 1.5-.68 1.5-1.5-.67-1.5-1.5-1.5zM7 19h14v-2H7v2zm0-6h14v-2H7v2zm0-8v2h14V5H7z",
  checklist: "M22 7h-9v2h9V7zm0 8h-9v2h9v-2zM5.54 11L2 7.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 11zm0 8L2 15.46l1.41-1.41 2.12 2.12 4.24-4.24 1.41 1.41L5.54 19z",
  quote: "M6 17h3l2-4V7H5v6h3zm8 0h3l2-4V7h-6v6h3z",
  format_size: "M9 4v3h5v12h3V7h5V4H9zm-6 8h3v7h3v-7h3V9H3v3z",
  delete: "M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z",
  download: "M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z",
  upload: "M9 16h6v-6h4l-7-7-7 7h4zm-4 2h14v2H5z",
  notifications: "M12 22c1.1 0 2-.9 2-2h-4c0 1.1.89 2 2 2zm6-6v-5c0-3.07-1.64-5.64-4.5-6.32V4c0-.83-.67-1.5-1.5-1.5s-1.5.67-1.5 1.5v.68C7.63 5.36 6 7.92 6 11v5l-2 2v1h16v-1l-2-2z",
  language: "M11.99 2C6.47 2 2 6.48 2 12s4.47 10 9.99 10C17.52 22 22 17.52 22 12S17.52 2 11.99 2zm6.93 6h-2.95c-.32-1.25-.78-2.45-1.38-3.56 1.84.63 3.37 1.91 4.33 3.56zM12 4.04c.83 1.2 1.48 2.53 1.91 3.96h-3.82c.43-1.43 1.08-2.76 1.91-3.96zM4.26 14C4.1 13.36 4 12.69 4 12s.1-1.36.26-2h3.38c-.08.66-.14 1.32-.14 2s.06 1.34.14 2H4.26zm.82 2h2.95c.32 1.25.78 2.45 1.38 3.56-1.84-.63-3.37-1.9-4.33-3.56zm2.95-8H5.08c.96-1.66 2.49-2.93 4.33-3.56C8.81 5.55 8.35 6.75 8.03 8zM12 19.96c-.83-1.2-1.48-2.53-1.91-3.96h3.82c-.43 1.43-1.08 2.76-1.91 3.96zM14.34 14H9.66c-.09-.66-.16-1.32-.16-2s.07-1.35.16-2h4.68c.09.65.16 1.32.16 2s-.07 1.34-.16 2zm.25 5.56c.6-1.11 1.06-2.31 1.38-3.56h2.95c-.96 1.65-2.49 2.93-4.33 3.56zM16.36 14c.08-.66.14-1.32.14-2s-.06-1.34-.14-2h3.38c.16.64.26 1.31.26 2s-.1 1.36-.26 2h-3.38z",
  dark_mode: "M12 3c-4.97 0-9 4.03-9 9s4.03 9 9 9 9-4.03 9-9c0-.46-.04-.92-.1-1.36-.98 1.37-2.58 2.26-4.4 2.26-2.98 0-5.4-2.42-5.4-5.4 0-1.81.89-3.42 2.26-4.4-.44-.06-.9-.1-1.36-.1z",
  my_location: "M12 8c-2.21 0-4 1.79-4 4s1.79 4 4 4 4-1.79 4-4-1.79-4-4-4zm8.94 3c-.46-4.17-3.77-7.48-7.94-7.94V1h-2v2.06C6.83 3.52 3.52 6.83 3.06 11H1v2h2.06c.46 4.17 3.77 7.48 7.94 7.94V23h2v-2.06c4.17-.46 7.48-3.77 7.94-7.94H23v-2h-2.06z",
  refresh: "M17.65 6.35C16.2 4.9 14.21 4 12 4c-4.42 0-7.99 3.58-8 8s3.58 8 8 8c3.73 0 6.84-2.55 7.73-6h-2.08c-.82 2.33-3.04 4-5.65 4-3.31 0-6-2.69-6-6s2.69-6 6-6c1.66 0 3.14.69 4.22 1.78L13 11h7V4l-2.35 2.35z",
  filter_list: "M10 18h4v-2h-4v2zM3 6v2h18V6H3zm3 7h12v-2H6v2z",
  more_vert: "M12 8c1.1 0 2-.9 2-2s-.9-2-2-2-2 .9-2 2 .9 2 2 2zm0 2c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2zm0 6c-1.1 0-2 .9-2 2s.9 2 2 2 2-.9 2-2-.9-2-2-2z",
  history: "M13 3c-4.97 0-9 4.03-9 9H1l3.89 3.89.07.14L9 12H6c0-3.87 3.13-7 7-7s7 3.13 7 7-3.13 7-7 7c-1.93 0-3.68-.79-4.94-2.06l-1.42 1.42C8.27 19.99 10.51 21 13 21c4.97 0 9-4.03 9-9s-4.03-9-9-9zm-1 5v5l4.28 2.54.72-1.21-3.5-2.08V8H12z",
  auto_awesome: "M19 9l1.25-2.75L23 5l-2.75-1.25L19 1l-1.25 2.75L15 5l2.75 1.25L19 9zm-7.5.5L9 4 6.5 9.5 1 12l5.5 2.5L9 20l2.5-5.5L17 12l-5.5-2.5zM19 15l-1.25 2.75L15 19l2.75 1.25L19 23l1.25-2.75L23 19l-2.75-1.25L19 15z",
  photo_library: "M22 16V4c0-1.1-.9-2-2-2H8c-1.1 0-2 .9-2 2v12c0 1.1.9 2 2 2h12c1.1 0 2-.9 2-2zm-11-4l2.03 2.71L16 11l4 5H8l3-4zM2 6v14c0 1.1.9 2 2 2h14v-2H4V6H2z",
  title: "M5 4v3h5.5v12h3V7H19V4z",
  arrow_forward: "M12 4l-1.41 1.41L16.17 11H4v2h12.17l-5.58 5.59L12 20l8-8z",
  unfold_more: "M12 5.83L15.17 9l1.41-1.41L12 3 7.41 7.59 8.83 9 12 5.83zm0 12.34L8.83 15l-1.41 1.41L12 21l4.59-4.59L15.17 15 12 18.17z",
  swipe: "M2 12l4 4V8l-4 4zm20 0l-4-4v8l4-4zM12 2l-4 4h3v4h2V6h3l-4-4zm0 20l4-4h-3v-4h-2v4H8l4 4z",
  offline_pin: "M12 2C8.13 2 5 5.13 5 9c0 5.25 7 13 7 13s7-7.75 7-13c0-3.87-3.13-7-7-7zm0 9.5c-1.38 0-2.5-1.12-2.5-2.5s1.12-2.5 2.5-2.5 2.5 1.12 2.5 2.5-1.12 2.5-2.5 2.5z",
  search_off: "M15.5 14h-.79l-.28-.27A6.471 6.471 0 0 0 16 9.5 6.5 6.5 0 1 0 9.5 16c1.61 0 3.09-.59 4.23-1.57l.27.28v.79l5 4.99L20.49 19l-4.99-5zm-6 0C7.01 14 5 11.99 5 9.5S7.01 5 9.5 5 14 7.01 14 9.5 11.99 14 9.5 14zM2.81 2.81L1.39 4.22l2.27 2.27A9.9 9.9 0 0 0 2 12c0 5.52 4.48 10 10 10 2.04 0 3.93-.61 5.51-1.66l2.27 2.27 1.41-1.41L2.81 2.81z",
  info: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm1 15h-2v-6h2v6zm0-8h-2V7h2v2z",
  warning: "M1 21h22L12 2 1 21zm12-3h-2v-2h2v2zm0-4h-2v-4h2v4z",
  open_in_new: "M19 19H5V5h7V3H5c-1.11 0-2 .9-2 2v14c0 1.1.89 2 2 2h14c1.1 0 2-.9 2-2v-7h-2v7zM14 3v2h3.59l-9.83 9.83 1.41 1.41L19 6.41V10h2V3h-7z",
  clean: "M16 11h-1V3c0-1.1-.9-2-2-2h-2c-1.1 0-2 .9-2 2v8H8c-2.76 0-5 2.24-5 5v7h18v-7c0-2.76-2.24-5-5-5z",
  polyline: "M15 16v-2l-4-4V5.83L12 4l1 1.83V9l4 4v3h-2zm-4 0H9v-3l4-4V5.83L14 4l1 1.83V7l-4 4v5z",
  globe: "M12 2C6.48 2 2 6.48 2 12s4.48 10 10 10 10-4.48 10-10S17.52 2 12 2zm-1 17.93c-3.95-.49-7-3.85-7-7.93 0-.62.08-1.21.21-1.79L9 15v2c0 1.1.9 2 2 2v1.93zm6.9-2.54c-.26-.81-1-1.39-1.9-1.39h-1v-3c0-.55-.45-1-1-1H8v-2h2c.55 0 1-.45 1-1V7h2c1.1 0 2-.9 2-2v-.41c2.93 1.19 5 4.06 5 7.41 0 2.08-.8 3.97-2.1 5.39z",
  keyboard_arrow_down: "M7.41 8.59L12 13.17l4.59-4.58L18 10l-6 6-6-6 1.41-1.41z",
  sticky_note: "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h10l6-6V5c0-1.1-.9-2-2-2zm-2 10h-5v-2h5v2zm0-4H7V7h10v2z",
};

function icon(name, opt = {}) {
  const size = opt.size || 24;
  const fill = opt.fill || "currentColor";
  const p = ICONS[name] || ICONS.info;
  const style = opt.style ? ` style="${opt.style}"` : "";
  return `<svg width="${size}" height="${size}" viewBox="0 0 24 24" fill="${fill}"${style} aria-hidden="true"><path d="${p}"/></svg>`;
}

/* ---------- 设备外壳 ---------- */

function statusbar(time = "9:41") {
  return `<div class="statusbar"><span>${time}</span><span class="icons"><i class="sig"></i><i class="wifi"></i><i class="batt"></i></span></div>`;
}

function gesturebar() { return `<div class="gesturebar"><span></span></div>`; }

function device(kind, content, opt = {}) {
  const cls = ["device", kind];
  if (kind === "tablet" && opt.landscape) cls.push("land");
  if (opt.dark) cls.push("dark");
  return `<div class="${cls.join(" ")}">${content}</div>`;
}

/** 手机：状态栏 + 内容 + 手势条 */
function phone(content, opt = {}) {
  const inner = `<div class="screen">${statusbar(opt.time || "9:41")}${content}${gesturebar()}</div>`;
  return device("phone", inner, opt);
}

/** 手机（内容自带底部导航时用它，手势条仍由这里补） */
function phoneNoBar(content, opt = {}) {
  return phone(content, opt);
}

/** 平板：状态栏 + 内容 + 手势条 */
function tablet(content, opt = {}) {
  const inner = `<div class="screen">${statusbar(opt.time || "9:41")}${content}${gesturebar()}</div>`;
  return device("tablet", inner, opt);
}

/* ---------- 导航 ---------- */

const TABS = [
  { id: "home", label: "日记", icon: "calendar_month" },
  { id: "footprint", label: "足迹", icon: "walk" },
  { id: "search", label: "搜索", icon: "search" },
  { id: "settings", label: "设置", icon: "settings" },
];

function navbar(active) {
  return `<div class="navbar">${TABS.map(t => `
    <div class="navitem ${t.id === active ? "on" : ""}">
      <div class="pill">${icon(t.icon, { size: 24 })}</div>
      <div class="lbl">${t.label}</div>
    </div>`).join("")}</div>`;
}

function rail(active, opt = {}) {
  const fab = opt.fab === false ? "" :
    `<div class="fab sm">${icon(opt.fabIcon || "add", { size: 22 })}</div>`;
  return `<div class="rail">${fab}${TABS.map(t => `
    <div class="ritem ${t.id === active ? "on" : ""}">
      <div class="pill">${icon(t.icon, { size: 24 })}</div>
      <div class="lbl">${t.label}</div>
    </div>`).join("")}</div>`;
}

function drawer(active) {
  return `<div class="drawer">
    <div class="dhead"><div class="dt">一页时光</div><div class="ds">纯本地 · 不上传</div></div>
    ${TABS.map(t => `<div class="ditem ${t.id === active ? "on" : ""}">${icon(t.icon, { size: 22 })}<span>${t.label}</span></div>`).join("")}
    <div class="hr" style="margin:12px 16px"></div>
    <div class="ditem">${icon("download", { size: 22 })}<span>备份与恢复</span></div>
    <div class="ditem">${icon("info", { size: 22 })}<span>关于</span></div>
  </div>`;
}

/** M3 top app bar（small，64dp） */
function topbar(opt = {}) {
  const leading = opt.leading === undefined
    ? `<div class="iconbtn">${icon("back")}</div>`
    : opt.leading;
  const title = opt.title ? `<div class="title ${opt.lead ? "lead" : ""}">${opt.title}</div>` : "";
  const actions = (opt.actions || []).map(a =>
    `<div class="iconbtn ${a.on ? "on" : ""} ${a.style || ""}">${icon(a.icon)}</div>`).join("");
  return `<div class="topbar ${opt.flat ? "flat" : ""}">${leading}${title}<div class="sp"></div>${actions}</div>`;
}

/* ---------- 日历（2026 年 9 月，与 iOS 参考稿同月） ---------- */

const LUNAR_SEP_2026 = ["二十","廿一","廿二","廿三","廿四","廿五","廿六","廿七","廿八","廿九",
  "八月","初二","初三","初四","初五","初六","初七","初八","初九","初十",
  "十一","十二","十三","十四","十五","十六","十七","十八","十九","二十"];

/** 周起始 = 周一；1 号是周二 → 前面 1 个空格；30 天 → 5 行 */
const WEEK_LABELS = ["一","二","三","四","五","六","日"];
const SEPT_LEAD = 1, SEPT_DAYS = 30;

/**
 * 月历网格。
 * @param opt.cellH 单元格高度（dp）
 * @param opt.selected 选中日
 * @param opt.today 今天
 * @param opt.hasEntries 有日记的日期集合
 * @param opt.rows 行数（几何由调用方给出，默认 6）
 * @param opt.showAdjacent 是否画相邻月（Android 设计：不画）
 * @param opt.compact 紧凑（周态/迷你）
 */
function monthGrid(opt = {}) {
  const cellH = opt.cellH || 96;
  const rows = opt.rows || 6;
  const sel = opt.selected, today = opt.today;
  const has = opt.hasEntries || [];
  const showLunar = opt.showLunar !== false && cellH >= 64;
  const cells = [];
  const lead = opt.lead === undefined ? SEPT_LEAD : opt.lead;
  const days = opt.days || SEPT_DAYS;
  const total = rows * 7;
  for (let i = 0; i < total; i++) {
    const d = i - lead + 1;
    if (d < 1 || d > days) { cells.push(null); continue; }
    cells.push(d);
  }
  let html = `<div class="cal"><div class="cal-head">${WEEK_LABELS.map((w, i) =>
    `<div style="${i >= 5 ? "color:var(--on-surface-variant)" : ""}">${w}</div>`).join("")}</div><div class="cal-grid">`;
  for (let r = 0; r < rows; r++) {
    const rowHasDay = cells.slice(r * 7, r * 7 + 7).some(x => x !== null);
    for (let c = 0; c < 7; c++) {
      const d = cells[r * 7 + c];
      const cls = ["cell"];
      if (r === 0 || !rowHasDay) cls.push("noline");
      if (d === null) { html += `<div class="${cls.join(" ")}" style="height:${cellH}px"></div>`; continue; }
      if (d === sel) cls.push("sel");
      const lunar = (opt.lunar || LUNAR_SEP_2026)[d - 1] || "";
      const mark = has.includes(d) && d !== sel && d !== today;
      html += `<div class="${cls.join(" ")}" style="height:${cellH}px">
        ${(d === sel || d === today) ? `<div class="ring"></div>` : ""}
        <div class="stack"><span class="d">${d}</span>${showLunar ? `<span class="l">${lunar}</span>` : ""}</div>
        ${mark ? `<div class="mark"></div>` : ""}
      </div>`;
    }
  }
  html += `</div></div>`;
  return html;
}

/** 迷你月历（年态用）。 */
function miniMonth(month, opt = {}) {
  const first = opt.firstWeekday === undefined ? 1 : opt.firstWeekday; // 0=周日
  const days = opt.days || 30;
  const lead = opt.lead === undefined ? 1 : opt.lead;
  const has = opt.has || [];
  const todayD = opt.today;
  const name = `${month}月`;
  const cells = [];
  for (let i = 0; i < 42; i++) {
    const d = i - lead + 1;
    cells.push(d >= 1 && d <= days ? d : null);
  }
  const rowsUsed = Math.ceil((lead + days) / 7);
  let grid = "";
  for (let i = 0; i < rowsUsed * 7; i++) {
    const d = cells[i];
    if (d === null) { grid += `<i class="blank">0</i>`; continue; }
    const cls = [];
    if (has.includes(d)) cls.push("has");
    if (d === todayD) cls.push("today");
    grid += `<i class="${cls.join(" ")}">${d}</i>`;
  }
  return `<div class="mini">
    <div class="mt ${has.length ? "" : "zero"}">${name}</div>
    <div class="mg">${["一","二","三","四","五","六","日"].map(x => `<i class="hd">${x}</i>`).join("")}${grid}</div>
  </div>`;
}

/* ---------- 板面排版 ---------- */

function shot(label, desc, html, tags, width) {
  const tg = (tags || []).map(t => {
    if (typeof t === "string") return `<span class="tag">${t}</span>`;
    return `<span class="tag ${t.k || ""}">${t.t}</span>`;
  }).join("");
  const w = width ? `width:${width}px;` : "";
  return `<div class="shot" style="${w}">
    <div class="cap"><div class="t">${label}</div><div class="d">${tg}${desc || ""}</div></div>
    ${html}
  </div>`;
}

function section(title, sub, body) {
  return `<div class="section"><h2>${title}</h2>${sub ? `<div class="sec-sub">${sub}</div>` : ""}${body}</div>`;
}

function renderBoard(cfg) {
  document.body.innerHTML = `<div class="board">
    <div class="board-head">
      <span class="board-kicker">${cfg.kicker || "JustGDiary · Android 设计稿"}</span>
      <h1>${cfg.title}</h1>
      <div class="sub">${cfg.sub || ""}</div>
      <div class="meta">${cfg.meta || ""}</div>
    </div>
    ${cfg.body}
  </div>`;
}
