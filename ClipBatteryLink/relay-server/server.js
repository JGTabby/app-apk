"use strict";

// Experimental line-delimited JSON relay for 喵喵連接.
// Deploy only behind TLS/VPN or a trusted private network; it is not encrypted.
const net = require("net");

const port = Number(process.env.PORT || 7000);
const maxLineLength = 16_384;
const rooms = new Map();

function leave(socket) {
  if (!socket.room) return;
  const members = rooms.get(socket.room);
  members?.delete(socket);
  if (members?.size === 0) rooms.delete(socket.room);
  socket.room = null;
}

function join(socket, room) {
  leave(socket);
  if (typeof room !== "string" || room.length < 6 || room.length > 64) {
    socket.write('{"type":"error","message":"配對代碼長度必須介於 6 到 64 個字元"}\n');
    return;
  }
  if (!rooms.has(room)) rooms.set(room, new Set());
  rooms.get(room).add(socket);
  socket.room = room;
  socket.write('{"type":"joined"}\n');
}

const server = net.createServer((socket) => {
  socket.setEncoding("utf8");
  socket.setTimeout(120_000, () => socket.destroy());
  let pending = "";

  socket.on("data", (chunk) => {
    pending += chunk;
    if (pending.length > maxLineLength * 2) return socket.destroy();
    let ending;
    while ((ending = pending.indexOf("\n")) !== -1) {
      const line = pending.slice(0, ending).trim();
      pending = pending.slice(ending + 1);
      if (!line || line.length > maxLineLength) return socket.destroy();
      let message;
      try {
        message = JSON.parse(line);
      } catch {
        return socket.destroy();
      }
      if (message.type === "join") {
        join(socket, message.room);
      } else if (socket.room) {
        for (const member of rooms.get(socket.room) || []) {
          if (member !== socket && !member.destroyed) member.write(line + "\n");
        }
      }
    }
  });

  socket.on("close", () => leave(socket));
  socket.on("error", () => leave(socket));
});

server.listen(port, "0.0.0.0", () => {
  console.log(`喵喵連接測試中繼正在連接埠 ${port} 運行`);
});
