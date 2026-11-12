#!/usr/bin/env node
// Arin MCP bridge: runs a local MCP server that speaks stdio (like `rea mcp`) and makes it
// reachable over HTTP at http://127.0.0.1:<port>/mcp, so the Arin app can use its tools.
//
// Usage: node mcp-bridge.mjs --port 8765 -- rea mcp
// No dependencies. Only listens on 127.0.0.1 (this phone).

import { spawn } from "node:child_process";
import http from "node:http";

const argv = process.argv.slice(2);
const sep = argv.indexOf("--");
if (sep < 0 || sep === argv.length - 1) {
  console.error("Usage: node mcp-bridge.mjs --port 8765 -- <command> [args...]");
  process.exit(2);
}
const opts = argv.slice(0, sep);
const [cmd, ...cmdArgs] = argv.slice(sep + 1);
const portAt = opts.indexOf("--port");
const port = portAt >= 0 ? Number(opts[portAt + 1]) : 8765;
const REQUEST_TIMEOUT_MS = 10 * 60 * 1000;

let child = null;
let buffer = "";
const waiting = new Map(); // id -> {resolve, timer}

function start() {
  child = spawn(cmd, cmdArgs, { stdio: ["pipe", "pipe", "pipe"], env: process.env });
  buffer = "";
  child.stdout.setEncoding("utf8");
  child.stdout.on("data", (chunk) => {
    buffer += chunk;
    let nl;
    while ((nl = buffer.indexOf("\n")) >= 0) {
      const line = buffer.slice(0, nl).trim();
      buffer = buffer.slice(nl + 1);
      if (line) onMessage(line);
    }
  });
  child.stderr.on("data", (d) => process.stderr.write(d));
  child.on("exit", (code, signal) => {
    console.error(`[bridge] server exited (${code ?? signal}); it restarts on the next request`);
    for (const [id, w] of waiting) {
      clearTimeout(w.timer);
      w.resolve({ jsonrpc: "2.0", id, error: { code: -32000, message: "MCP server stopped; reconnect and try again" } });
    }
    waiting.clear();
    child = null;
  });
  child.on("error", (e) => console.error(`[bridge] couldn't start ${cmd}: ${e.message}`));
}

function send(msg) {
  if (!child) start();
  child.stdin.write(JSON.stringify(msg) + "\n");
}

function onMessage(line) {
  let msg;
  try {
    msg = JSON.parse(line);
  } catch {
    return; // not JSON-RPC (stray log line)
  }
  // A request from the server to us (it has a method and an id): answer simply.
  if (msg.method && msg.id !== undefined) {
    if (msg.method === "roots/list") send({ jsonrpc: "2.0", id: msg.id, result: { roots: [] } });
    else if (msg.method === "ping") send({ jsonrpc: "2.0", id: msg.id, result: {} });
    else send({ jsonrpc: "2.0", id: msg.id, error: { code: -32601, message: "Not supported by the Arin bridge" } });
    return;
  }
  if (msg.id === undefined) return; // a notification from the server: progress, logs
  const w = waiting.get(String(msg.id));
  if (w) {
    clearTimeout(w.timer);
    waiting.delete(String(msg.id));
    w.resolve(msg);
  }
}

function call(msg) {
  return new Promise((resolve) => {
    const key = String(msg.id);
    const timer = setTimeout(() => {
      waiting.delete(key);
      resolve({ jsonrpc: "2.0", id: msg.id, error: { code: -32001, message: "The MCP server took too long to answer" } });
    }, REQUEST_TIMEOUT_MS);
    waiting.set(key, { resolve, timer });
    send(msg);
  });
}

const server = http.createServer(async (req, res) => {
  if (req.method === "GET" && (req.url === "/health" || req.url === "/")) {
    res.writeHead(200, { "content-type": "application/json" });
    res.end(JSON.stringify({ ok: true, server: [cmd, ...cmdArgs].join(" "), running: child !== null }));
    return;
  }
  if (req.method === "DELETE") {
    res.writeHead(200).end();
    return;
  }
  if (req.method !== "POST") {
    res.writeHead(405).end();
    return;
  }
  let body = "";
  req.setEncoding("utf8");
  for await (const chunk of req) body += chunk;
  let parsed;
  try {
    parsed = JSON.parse(body);
  } catch {
    res.writeHead(400, { "content-type": "application/json" });
    res.end(JSON.stringify({ jsonrpc: "2.0", id: null, error: { code: -32700, message: "Parse error" } }));
    return;
  }
  const batch = Array.isArray(parsed) ? parsed : [parsed];
  const replies = [];
  for (const msg of batch) {
    if (msg.id === undefined || msg.id === null) send(msg); // notification
    else replies.push(await call(msg));
  }
  if (replies.length === 0) {
    res.writeHead(202).end();
    return;
  }
  res.writeHead(200, { "content-type": "application/json", "mcp-session-id": "arin-bridge" });
  res.end(JSON.stringify(Array.isArray(parsed) ? replies : replies[0]));
});

server.listen(port, "127.0.0.1", () => {
  console.error(`[bridge] ${[cmd, ...cmdArgs].join(" ")} → http://127.0.0.1:${port}/mcp`);
  start();
});
