const express = require('express');
const http = require('http');
const path = require('path');
const { Server } = require('socket.io');

const app = express();
const server = http.createServer(app);
const io = new Server(server);

// serve static files from ./public
app.use(express.static(path.join(__dirname, 'public')));

// simple health check
app.get('/ping', (req, res) => res.send('pong'));

// rooms: socket.io events provide room join/leave and relay SDP/ICE
io.on('connection', (socket) => {
  console.log('socket connected:', socket.id);

  socket.on('join-room', (roomId, userName) => {
    socket.join(roomId);
    socket.data.userName = userName || 'Anonymous';
    console.log(`${socket.id} joined ${roomId} as ${socket.data.userName}`);

    // notify existing clients in room about new peer
    socket.to(roomId).emit('peer-joined', { peerId: socket.id, userName: socket.data.userName });

    // send back list of existing peers to the joining client
    const socketsInRoom = Array.from(io.sockets.adapter.rooms.get(roomId) || []);
    const otherPeers = socketsInRoom.filter(id => id !== socket.id);
    socket.emit('existing-peers', otherPeers);

    // relay signaling messages
    socket.on('signal', (toPeerId, message) => {
      // message is {type: 'offer'|'answer'|'ice', payload: ...}
      io.to(toPeerId).emit('signal', socket.id, message);
    });

    // chat messages
    socket.on('room-chat', (msg) => {
      // broadcast to room
      io.to(roomId).emit('room-chat', {
        from: socket.id,
        name: socket.data.userName,
        text: msg,
        ts: Date.now()
      });
    });

    socket.on('disconnect', () => {
      console.log(`${socket.id} disconnected`);
      socket.to(roomId).emit('peer-left', socket.id);
    });

    socket.on('leave-room', () => {
      socket.leave(roomId);
      socket.to(roomId).emit('peer-left', socket.id);
    });
  });
});

const PORT = process.env.PORT || 3000;
server.listen(PORT, () => console.log(`Server listening on port ${PORT}`));
