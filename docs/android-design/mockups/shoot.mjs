#!/usr/bin/env node
/**
 * 用 Chrome DevTools Protocol 精确截图：先量页面实际尺寸，再按 clip + 2x 输出整页 PNG。
 * 比 `--screenshot --window-size` 可靠：窗口高度不再受渲染上限影响（超高的板面会被
 * 降采样并填成灰色），也不需要事后裁剪。
 *
 * 用法： node shoot.mjs <port> <out.png> <cssWidth> <html-file> [--trim]
 */
import { writeFileSync } from "node:fs";
import { readFileSync } from "node:fs";

const [port, out, cssWidth, file] = process.argv.slice(2);
const W = Number(cssWidth);

async function cdp(ws, calls) {
  let id = 0;
  const pending = new Map();
  const send = (method, params = {}) =>
    new Promise((res, rej) => {
      const mid = ++id;
      pending.set(mid, { res, rej });
      ws.send(JSON.stringify({ id: mid, method, params }));
    });
  ws.addEventListener("message", (ev) => {
    const msg = JSON.parse(ev.data);
    if (msg.id && pending.has(msg.id)) {
      const { res, rej } = pending.get(msg.id);
      pending.delete(msg.id);
      msg.error ? rej(new Error(msg.error.message)) : res(msg.result);
    }
  });
  return calls(send);
}

// 1) 找一个可用 target
const list = await (await fetch(`http://127.0.0.1:${port}/json/list`)).json();
const target = list.find((t) => t.type === "page");
if (!target) throw new Error("no page target");

const ws = new WebSocket(target.webSocketDebuggerUrl);
await new Promise((r) => ws.addEventListener("open", r, { once: true }));

await cdp(ws, async (send) => {
  await send("Page.enable");
  await send("Emulation.setDeviceMetricsOverride", {
    width: W, height: 1200, deviceScaleFactor: 1, mobile: false,
  });
  const loaded = new Promise((r) => {
    const h = (ev) => {
      const m = JSON.parse(ev.data);
      if (m.method === "Page.loadEventFired") { ws.removeEventListener("message", h); r(); }
    };
    ws.addEventListener("message", h);
  });
  await send("Page.navigate", { url: "file://" + file });
  await loaded;
  await new Promise((r) => setTimeout(r, 700)); // 等字体与同步脚本落定

  const { result } = await send("Runtime.evaluate", {
    expression: `JSON.stringify({h: document.documentElement.scrollHeight, w: document.documentElement.scrollWidth})`,
    returnByValue: true,
  });
  const { h, w } = JSON.parse(result.value);

  const shot = await send("Page.captureScreenshot", {
    format: "png",
    captureBeyondViewport: true,
    clip: { x: 0, y: 0, width: Math.min(w, W), height: h, scale: 2 },
  });
  writeFileSync(out, Buffer.from(shot.data, "base64"));
  process.stdout.write(`  ${out.split("/").pop().padEnd(24)} ${Math.min(w, W)}x${h} css -> ${Math.min(w, W) * 2}x${h * 2} px\n`);
});

ws.close();
process.exit(0);
