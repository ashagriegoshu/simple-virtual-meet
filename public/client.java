// Minimal WebRTC multi-peer mesh demo using Socket.IO
const socket = io();

const roomInput = document.getElementById('roomId');
const nameInput = document.getElementById('name');
const joinBtn = document.getElementById('joinBtn');
const leaveBtn = document.getElementById('leaveBtn');
const videos = document.getElementById('videos');
const statusEl = document.getElementById('status');
const chatLog = document.getElementById('chatLog');
const chatText = document.getElementById('chatText');
const sendChat = document.getElementById('sendChat');
const muteBtn = document.getElementById('muteBtn');
const camBtn = document.getElementById('camBtn');
const shareBtn = document.getElementById('shareBtn');

let localStream = null;
let pcs = {}; // peerId -> RTCPeerConnection
let roomId = null;
let userName = null;

const STUN_SERVERS = { iceServers: [{ urls: 'stun:stun.l.google.com:19302' }] };

// helpers
function logStatus(txt){ statusEl.textContent = txt; }
function addMessage(msg){ 
  const el = document.createElement('div');
  el.innerHTML = `<strong>${escapeHtml(msg.name||msg.from||'System')}</strong>: ${escapeHtml(msg.text||msg)} <div style="font-size:11px;color:#888">${msg.ts?new Date(msg.ts).toLocaleTimeString():''}</div>`;
  chatLog.appendChild(el);
  chatLog.scrollTop = chatLog.scrollHeight;
}
function escapeHtml(s){ if(!s) return ''; return s.toString().replace(/[&<>"']/g, c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c])); }

async function getLocalMedia() {
  try {
    localStream = await navigator.mediaDevices.getUserMedia({ video: true, audio: true });
    addLocalVideo();
  } catch (e) {
    alert('Could not get media: ' + e.message);
    throw e;
  }
}

function addLocalVideo() {
  const existing = document.getElementById('localVideo');
  if (existing) return;
  const wrap = document.createElement('div'); wrap.className='videoWrap';
  const v = document.createElement('video');
  v.id = 'localVideo';
  v.autoplay = true; v.muted = true; v.playsInline = true;
  v.srcObject = localStream;
  wrap.appendChild(v);
  const lbl = document.createElement('div'); lbl.className='peerLabel'; lbl.innerText = 'You';
  wrap.appendChild(lbl);
  videos.prepend(wrap);
}

function removeVideo(peerId) {
  const el = document.getElementById('video-' + peerId);
  if (el && el.parentNode) el.parentNode.removeChild(el);
}

function createRemoteVideo(peerId, label) {
  removeVideo(peerId);
  const wrap = document.createElement('div'); wrap.className='videoWrap';
  const v = document.createElement('video');
  v.id = 'video-' + peerId;
  v.autoplay = true; v.playsInline = true;
  wrap.appendChild(v);
  const lbl = document.createElement('div'); lbl.className='peerLabel'; lbl.innerText = label || peerId;
  wrap.appendChild(lbl);
  videos.appendChild(wrap);
  return v;
}

async function createPeerConnection(peerId, polite=false) {
  if (pcs[peerId]) return pcs[peerId];
  const pc = new RTCPeerConnection(STUN_SERVERS);
  pcs[peerId] = pc;

  // add local tracks
  if (localStream) {
    for (const t of localStream.getTracks()) pc.addTrack(t, localStream);
  }

  pc.onicecandidate = e => {
    if (e.candidate) {
      socket.emit('signal', peerId, { type: 'ice', payload: e.candidate });
    }
  };

  pc.ontrack = e => {
    const el = createRemoteVideo(peerId, 'Peer ' + peerId);
    el.srcObject = e.streams[0];
  };

  pc.onconnectionstatechange = () => {
    if (pc.connectionState === 'disconnected' || pc.connectionState === 'failed' || pc.connectionState === 'closed') {
      removeVideo(peerId);
      try { pc.close(); } catch {}
      delete pcs[peerId];
    }
  };

  return pc;
}

// signaling handlers
socket.on('connect', () => logStatus('Connected to signaling server'));

socket.on('existing-peers', async (peers) => {
  // when we join we get existing peer ids; create offers to them
  for (const peerId of peers) {
    await prepareOffer(peerId);
  }
});

socket.on('peer-joined', async ({peerId, userName}) => {
  addMessage({ name: 'System', text: `${userName || peerId} joined`});
  // do nothing — the joining peer will create offers to existing peers,
  // but to be robust we can create an offer if needed
});

socket.on('peer-left', (peerId) => {
  addMessage({ name: 'System', text: `${peerId} left`});
  removeVideo(peerId);
  if (pcs[peerId]) {
    try { pcs[peerId].close(); } catch {}
    delete pcs[peerId];
  }
});

socket.on('signal', async (fromId, message) => {
  // message: {type, payload}
  const {type, payload} = message;
  if (!pcs[fromId]) await createPeerConnection(fromId);

  const pc = pcs[fromId];
  if (type === 'offer') {
    await pc.setRemoteDescription(new RTCSessionDescription(payload));
    const answer = await pc.createAnswer();
    await pc.setLocalDescription(answer);
    socket.emit('signal', fromId, { type: 'answer', payload: pc.localDescription });
  } else if (type === 'answer') {
    await pc.setRemoteDescription(new RTCSessionDescription(payload));
  } else if (type === 'ice') {
    try {
      await pc.addIceCandidate(new RTCIceCandidate(payload));
    } catch (err) {
      console.warn('Failed to add ICE candidate', err);
    }
  }
});

// create offer to a given peer
async function prepareOffer(peerId) {
  const pc = await createPeerConnection(peerId);
  const offer = await pc.createOffer();
  await pc.setLocalDescription(offer);
  socket.emit('signal', peerId, { type: 'offer', payload: pc.localDescription });
}

// UI actions
joinBtn.onclick = async () => {
  if (!roomInput.value) return alert('Enter room id');
  roomId = roomInput.value;
  userName = nameInput.value || 'Anonymous';
  await getLocalMedia();
  socket.emit('join-room', roomId, userName);
  logStatus(`Joined room ${roomId} as ${userName}`);
  joinBtn.disabled = true;
};

leaveBtn.onclick = () => {
  if (!roomId) return;
  socket.emit('leave-room');
  for (const id in pcs) {
    try { pcs[id].close(); } catch {}
    delete pcs[id];
  }
  // remove remote videos
  Array.from(document.querySelectorAll('[id^="video-"]')).forEach(n => n.parentNode.remove());
  const local = document.getElementById('localVideo');
  if (local && local.srcObject) {
    local.srcObject.getTracks().forEach(t => t.stop());
    local.srcObject = null;
  }
  localStream = null;
  roomId = null;
  joinBtn.disabled = false;
  logStatus('Left room');
};

sendChat.onclick = () => {
  const text = chatText.value.trim();
  if (!text || !roomId) return;
  socket.emit('room-chat', text);
  chatText.value = '';
};

socket.on('room-chat', (msg) => {
  addMessage(msg);
});

// simple controls
muteBtn.onclick = () => {
  if (!localStream) return;
  const audioTrack = localStream.getAudioTracks()[0];
  if (!audioTrack) return;
  audioTrack.enabled = !audioTrack.enabled;
  muteBtn.textContent = audioTrack.enabled ? 'Mute' : 'Unmute';
};

camBtn.onclick = () => {
  if (!localStream) return;
  const videoTrack = localStream.getVideoTracks()[0];
  if (!videoTrack) return;
  videoTrack.enabled = !videoTrack.enabled;
  camBtn.textContent = videoTrack.enabled ? 'Stop Camera' : 'Start Camera';
};

shareBtn.onclick = async () => {
  try {
    const screenStream = await navigator.mediaDevices.getDisplayMedia({ video: true });
    // replace local video track for each peer connection
    const screenTrack = screenStream.getVideoTracks()[0];
    for (const id in pcs) {
      const pc = pcs[id];
      const sender = pc.getSenders().find(s => s.track && s.track.kind === 'video');
      if (sender) await sender.replaceTrack(screenTrack);
    }
    // show local screen in local video element
    const localVideo = document.getElementById('localVideo');
    if (localVideo) localVideo.srcObject = screenStream;
    // when screen sharing ends, restore camera
    screenTrack.onended = async () => {
      if (!localStream) return;
      for (const id in pcs) {
        const pc = pcs[id];
        const sender = pc.getSenders().find(s => s.track && s.track.kind === 'video');
        if (sender) await sender.replaceTrack(localStream.getVideoTracks()[0]);
      }
      if (localVideo) localVideo.srcObject = localStream;
    };
  } catch (e) {
    console.warn('Screen share failed', e);
  }
};
