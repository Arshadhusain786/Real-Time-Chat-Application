// ==================== STATE ====================
let currentUser = null;
let token = null;
let stompClient = null;
let conversations = [];
let activeConversationId = null;
let typingTimers = {};
let isTyping = false;
let typingTimeout = null;
let replyingTo = null;
let selectedGroupMembers = [];
let mediaRecorder = null;
let audioChunks = [];
let recordingInterval = null;
let recordingStartTime = null;

// ==================== INIT ====================
// Use sessionStorage for auth (tab-isolated) so different tabs can have different users.
// localStorage is shared across tabs and would cause User B to overwrite User A.
document.addEventListener('DOMContentLoaded', () => {
    token = sessionStorage.getItem('chat_token');
    const userData = sessionStorage.getItem('chat_user');
    if (token && userData) {
        currentUser = JSON.parse(userData);
        verifyAndStart();
    }
    // Cross-tab sync for read receipts only (not auth)
    window.addEventListener('storage', handleStorageEvent);
});

function handleStorageEvent(e) {
    // Only handle read sync events - auth is per-tab via sessionStorage
    if (e.key === 'chat_read_sync') {
        try {
            const data = JSON.parse(e.newValue);
            if (data && data.conversationId && data.userId === currentUser?.id) {
                updateConversationUnread(data.conversationId, 0);
            }
        } catch(ignored) {}
    }
}

async function verifyAndStart() {
    try {
        const resp = await apiGet('/api/auth/me');
        if (resp.ok) {
            currentUser = await resp.json();
            sessionStorage.setItem('chat_user', JSON.stringify(currentUser));
            showApp();
        } else {
            logout();
        }
    } catch (e) {
        logout();
    }
}

// ==================== AUTH ====================
function showLogin() {
    document.getElementById('loginForm').style.display = '';
    document.getElementById('registerForm').style.display = 'none';
    document.getElementById('authTitle').textContent = 'Login';
    hideAuthError();
}

function showRegister() {
    document.getElementById('loginForm').style.display = 'none';
    document.getElementById('registerForm').style.display = '';
    document.getElementById('authTitle').textContent = 'Register';
    hideAuthError();
}

async function login() {
    const username = document.getElementById('loginUsername').value.trim();
    const password = document.getElementById('loginPassword').value;
    if (!username || !password) return showAuthError('Please fill all fields');

    try {
        const resp = await fetch('/api/auth/login', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username, password})
        });
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.error || 'Login failed');

        token = data.token;
        currentUser = {id: data.id, username: data.username, email: data.email, displayName: data.displayName};
        // Store in sessionStorage (tab-isolated)
        sessionStorage.setItem('chat_token', token);
        sessionStorage.setItem('chat_user', JSON.stringify(currentUser));
        showApp();
    } catch (e) {
        showAuthError(e.message);
    }
}

async function register() {
    const username = document.getElementById('regUsername').value.trim();
    const email = document.getElementById('regEmail').value.trim();
    const displayName = document.getElementById('regDisplayName').value.trim();
    const password = document.getElementById('regPassword').value;

    if (!username || !email || !password) return showAuthError('Please fill required fields');

    try {
        const resp = await fetch('/api/auth/register', {
            method: 'POST',
            headers: {'Content-Type': 'application/json'},
            body: JSON.stringify({username, email, password, displayName: displayName || username})
        });
        const data = await resp.json();
        if (!resp.ok) throw new Error(data.error || 'Registration failed');

        showLogin();
        document.getElementById('loginUsername').value = username;
        document.getElementById('authError').textContent = 'Registration successful! Please login.';
        document.getElementById('authError').style.display = '';
        document.getElementById('authError').className = 'alert alert-success mt-3';
    } catch (e) {
        showAuthError(e.message);
    }
}

function logout() {
    token = null;
    currentUser = null;
    sessionStorage.removeItem('chat_token');
    sessionStorage.removeItem('chat_user');
    if (stompClient && stompClient.connected) stompClient.disconnect();
    stompClient = null;
    conversations = [];
    activeConversationId = null;
    document.getElementById('appScreen').style.display = 'none';
    document.getElementById('authScreen').style.display = '';
    showLogin();
}

function showAuthError(msg) {
    const el = document.getElementById('authError');
    el.textContent = msg;
    el.style.display = '';
    el.className = 'alert alert-danger mt-3';
}
function hideAuthError() { document.getElementById('authError').style.display = 'none'; }

// ==================== APP INIT ====================
function showApp() {
    document.getElementById('authScreen').style.display = 'none';
    document.getElementById('appScreen').style.display = '';
    const initials = (currentUser.displayName || currentUser.username).charAt(0).toUpperCase();
    document.getElementById('myAvatar').textContent = initials;
    loadConversations();
    connectWebSocket();
    requestNotificationPermission();
    // Start polling fallback for when WebSocket drops (Render free tier)
    startPolling();
}

// Polling fallback: refresh conversations every 15s to catch missed messages
let pollInterval = null;
function startPolling() {
    if (pollInterval) clearInterval(pollInterval);
    pollInterval = setInterval(async () => {
        if (!token) return;
        try {
            await loadConversations();
            // Only reload messages if WebSocket is dead AND conversation is open
            if (activeConversationId && (!stompClient || !stompClient.connected)) {
                await loadMessages(activeConversationId);
            }
        } catch (e) { /* silent */ }
    }, 30000);
}

// ==================== API HELPERS ====================
function apiGet(url) { return fetch(url, {headers: {'Authorization': 'Bearer ' + token}}); }
function apiPost(url, body) {
    return fetch(url, {method:'POST', headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'}, body: body ? JSON.stringify(body) : undefined});
}
function apiPut(url, body) {
    return fetch(url, {method:'PUT', headers:{'Authorization':'Bearer '+token,'Content-Type':'application/json'}, body: JSON.stringify(body)});
}
function apiDelete(url) { return fetch(url, {method:'DELETE', headers:{'Authorization':'Bearer '+token}}); }

// ==================== WEBSOCKET ====================
function connectWebSocket() {
    if (stompClient && stompClient.connected) return;

    const socket = new SockJS('/ws/chat');
    stompClient = Stomp.over(socket);
    stompClient.debug = null; // disable verbose STOMP debug logs

    // A1: Enable heartbeat for dead connection detection
    stompClient.heartbeat.outgoing = 10000; // send heartbeat every 10s
    stompClient.heartbeat.incoming = 10000; // expect heartbeat every 10s

    stompClient.connect({'Authorization': 'Bearer ' + token}, () => {
        console.log('WebSocket connected for user:', currentUser.username);
        // Subscribe to private queues (user-specific, per-session via Spring)
        stompClient.subscribe('/user/queue/messages', onMessageReceived);
        stompClient.subscribe('/user/queue/typing', onTypingReceived);
        stompClient.subscribe('/user/queue/read', onReadReceived);
        stompClient.subscribe('/user/queue/conversations', onConversationReceived);
        stompClient.subscribe('/topic/presence', onPresenceReceived);

        // A3: After (re)connect, resync state from server
        resyncAfterReconnect();
    }, (error) => {
        console.warn('WebSocket disconnected, reconnecting in 3s...');
        stompClient = null;
        showToast('Connection lost. Reconnecting...', 'warning');
        setTimeout(connectWebSocket, 3000);
    });
}

// A3: Resync conversations and active chat after reconnect
async function resyncAfterReconnect() {
    try {
        await loadConversations();
        if (activeConversationId) {
            await loadMessages(activeConversationId);
        }
        // Flush any queued messages
        flushMessageQueue();
    } catch (e) {
        console.error('Resync failed:', e);
    }
}

function onMessageReceived(payload) {
    const msg = JSON.parse(payload.body);
    const convId = msg.conversation_id;

    if (msg.action === 'EDIT') {
        updateMessageInUI(msg);
        return;
    }
    if (msg.action === 'DELETE') {
        updateMessageInUI(msg);
        return;
    }
    if (msg.action === 'REACTION_ADD' || msg.action === 'REACTION_REMOVE') {
        if (convId === activeConversationId) {
            updateReactionInUI(msg);
        }
        return;
    }

    // Regular new message
    if (convId == activeConversationId) {
        // Skip if it's our own message (already shown optimistically)
        if (msg.sender_id === currentUser.id) {
            // Remove the temp optimistic message and replace with server-confirmed one
            const tempMsgs = document.querySelectorAll('[id^="msg-temp-"]');
            if (tempMsgs.length > 0) {
                tempMsgs[0].remove();
            }
        }
        appendMessage(msg); // dedup built into appendMessage
        scrollToBottom();
        // Auto mark as read if window focused and not our own message
        if (document.hasFocus() && msg.sender_id !== currentUser.id) {
            markAsRead(convId);
        }
    } else {
        // Not viewing this conversation - increase unread
        const conv = conversations.find(c => c.id == convId);
        if (conv) {
            conv.unread_count = (conv.unread_count || 0) + 1;
            conv.last_message = msg;
        }
        // Browser notification if tab is hidden
        if (msg.sender_id !== currentUser.id) {
            showBrowserNotification(
                msg.sender_name || 'New message',
                msg.message || '📎 Attachment'
            );
        }
    }

    // Move conversation to top
    moveConversationToTop(convId, msg);
    renderConversationList();
}

function onTypingReceived(payload) {
    const data = JSON.parse(payload.body);
    if (data.conversation_id === activeConversationId) {
        const indicator = document.getElementById('typingIndicator');
        if (data.typing) {
            indicator.textContent = (data.sender_name || 'Someone') + ' is typing...';
            clearTimeout(typingTimers[data.sender_id]);
            typingTimers[data.sender_id] = setTimeout(() => {
                indicator.textContent = '';
            }, 3000);
        } else {
            indicator.textContent = '';
        }
    }
}

function onReadReceived(payload) {
    const data = JSON.parse(payload.body);
    if (data.conversation_id === activeConversationId) {
        // Update all my sent messages' delivery status to read
        document.querySelectorAll('.message-status').forEach(el => {
            if (el.dataset.senderId == currentUser.id) {
                el.innerHTML = '<i class="bi bi-check2-all"></i>';
                el.classList.add('read');
            }
        });
    }
    // If this is our own read-ack echoed back, clear unread
    if (data.sender_id === currentUser.id) {
        updateConversationUnread(data.conversation_id, 0);
    }
}

function onConversationReceived(payload) {
    const conv = JSON.parse(payload.body);
    const existingIdx = conversations.findIndex(c => c.id === conv.id);
    if (existingIdx >= 0) {
        // Update sidebar data only (NOT messages — those come via /queue/messages)
        const existing = conversations[existingIdx];
        if (conv.last_message) existing.last_message = conv.last_message;
        if (conv.updated_at) existing.updated_at = conv.updated_at;
        if (conv.members) existing.members = conv.members;
        // Move to top
        if (existingIdx > 0) {
            conversations.splice(existingIdx, 1);
            conversations.unshift(existing);
        }
    } else {
        // Brand new conversation (first DM or new group)
        conversations.unshift(conv);
    }
    renderConversationList();
}

function onPresenceReceived(payload) {
    const data = JSON.parse(payload.body);
    // Update conversation member online status
    conversations.forEach(conv => {
        if (conv.members) {
            const member = conv.members.find(m => m.id === data.userId);
            if (member) member.is_online = data.isOnline;
        }
    });

    // Update online dots in sidebar
    document.querySelectorAll(`[data-user-id="${data.userId}"]`).forEach(el => {
        const dot = el.querySelector('.online-dot');
        if (data.isOnline) {
            if (!dot) {
                const d = document.createElement('div');
                d.className = 'online-dot';
                el.appendChild(d);
            }
        } else {
            if (dot) dot.remove();
        }
    });

    // Update chat header if active
    if (activeConversationId) {
        updateChatHeaderStatus();
    }
}

// ==================== CONVERSATIONS ====================
async function loadConversations() {
    try {
        const resp = await apiGet('/api/conversations');
        if (resp.ok) {
            conversations = await resp.json();
            renderConversationList();
        }
    } catch (e) { console.error('Failed to load conversations', e); }
}

function renderConversationList() {
    const list = document.getElementById('conversationList');
    const search = document.getElementById('searchInput').value.toLowerCase();

    let filtered = conversations;
    if (search) {
        filtered = conversations.filter(c => (c.name || '').toLowerCase().includes(search));
    }

    list.innerHTML = filtered.map(conv => {
        const isActive = conv.id == activeConversationId;
        const lastMsg = conv.last_message;
        const unread = conv.unread_count || 0;
        const initials = (conv.name || '?').charAt(0).toUpperCase();
        const otherMember = conv.type === 'DIRECT' && conv.members ? conv.members.find(m => m.id !== currentUser.id) : null;
        const isOnline = otherMember && otherMember.is_online;
        const timeStr = lastMsg && lastMsg.sent_at ? formatTime(lastMsg.sent_at) : '';
        let lastMsgText = '';
        if (lastMsg) {
            const prefix = lastMsg.sender_id === currentUser.id ? 'You: ' : '';
            if (lastMsg.message_type && lastMsg.message_type !== 'TEXT') {
                lastMsgText = prefix + '📎 ' + lastMsg.message_type.toLowerCase();
            } else {
                lastMsgText = prefix + (lastMsg.message || '');
            }
        }
        const avatarUserId = otherMember ? otherMember.id : '';
        const avatarImg = otherMember && otherMember.profile_picture
            ? otherMember.profile_picture
            : (conv.avatar_url || null);

        return `<div class="conv-item ${isActive?'active':''}" onclick="openConversation(${conv.id})">
            <div class="conv-avatar" data-user-id="${avatarUserId}">
                ${avatarImg ? `<img src="${escapeAttr(avatarImg)}" alt="">` : initials}
                ${isOnline ? '<div class="online-dot"></div>' : ''}
            </div>
            <div class="conv-info">
                <div class="conv-name">${escapeHtml(conv.name || 'Chat')}</div>
                <div class="conv-last-msg">${escapeHtml(lastMsgText)}</div>
            </div>
            <div class="conv-meta">
                <span class="conv-time">${timeStr}</span>
                ${unread > 0 ? `<span class="unread-badge">${unread > 99 ? '99+' : unread}</span>` : ''}
            </div>
        </div>`;
    }).join('');
}

function filterConversations() { renderConversationList(); }

function moveConversationToTop(convId, lastMsg) {
    const idx = conversations.findIndex(c => c.id == convId);
    if (idx > 0) {
        const [conv] = conversations.splice(idx, 1);
        conv.last_message = lastMsg;
        conversations.unshift(conv);
    } else if (idx === 0) {
        conversations[0].last_message = lastMsg;
    }
}

function updateConversationUnread(convId, count) {
    const conv = conversations.find(c => c.id == convId);
    if (conv) {
        conv.unread_count = count;
        renderConversationList();
    }
}

// ==================== OPEN CONVERSATION ====================
async function openConversation(convId) {
    activeConversationId = Number(convId);
    const conv = conversations.find(c => c.id == convId);
    if (!conv) return;

    // Show chat view
    document.getElementById('emptyState').style.display = 'none';
    document.getElementById('activeChatView').style.display = 'flex';
    document.getElementById('activeChatView').style.flexDirection = 'column';
    document.getElementById('activeChatView').style.height = '100%';
    document.querySelector('.app-container').classList.add('chat-open');

    // Update header
    const initials = (conv.name || '?').charAt(0).toUpperCase();
    const otherMemberChat = conv.type === 'DIRECT' && conv.members ? conv.members.find(m => m.id !== currentUser.id) : null;
    const headerAvatar = (otherMemberChat && otherMemberChat.profile_picture)
        ? otherMemberChat.profile_picture
        : (conv.avatar_url || null);
    document.getElementById('chatAvatar').innerHTML = headerAvatar
        ? `<img src="${escapeAttr(headerAvatar)}" alt="">`
        : initials;
    document.getElementById('chatName').textContent = conv.name || 'Chat';
    document.getElementById('groupInfoBtn').style.display = conv.type === 'GROUP' ? '' : 'none';
    updateChatHeaderStatus();

    // Load messages
    await loadMessages(convId);

    // Mark as read
    if (conv.unread_count > 0) {
        markAsRead(convId);
        conv.unread_count = 0;
        // Notify other tabs of same user via localStorage
        localStorage.setItem('chat_read_sync', JSON.stringify({conversationId: convId, userId: currentUser.id, t: Date.now()}));
    }

    renderConversationList();
    document.getElementById('messageInput').focus();
}

function updateChatHeaderStatus() {
    const conv = conversations.find(c => c.id == activeConversationId);
    if (!conv) return;
    const statusEl = document.getElementById('chatStatus');
    if (conv.type === 'DIRECT' && conv.members) {
        const other = conv.members.find(m => m.id !== currentUser.id);
        if (other) {
            statusEl.textContent = other.is_online ? 'online' : 'offline';
            statusEl.style.color = other.is_online ? '#28a745' : '';
        }
    } else if (conv.type === 'GROUP' && conv.members) {
        const onlineCount = conv.members.filter(m => m.is_online).length;
        statusEl.textContent = `${conv.members.length} members` + (onlineCount > 0 ? `, ${onlineCount} online` : '');
        statusEl.style.color = '';
    }
}

async function loadMessages(convId) {
    const container = document.getElementById('chatMessages');
    container.innerHTML = '<div style="text-align:center;padding:20px;color:#999">Loading...</div>';

    try {
        const resp = await apiGet(`/api/messages/${convId}?page=0`);
        if (resp.ok) {
            const messages = await resp.json();
            container.innerHTML = '';
            messages.forEach(msg => appendMessage(msg));
            scrollToBottom();
        } else {
            container.innerHTML = '<div style="text-align:center;padding:20px;color:#999">Failed to load messages</div>';
        }
    } catch (e) {
        console.error('Failed to load messages', e);
        container.innerHTML = '<div style="text-align:center;padding:20px;color:#999">Connection error. Messages will load on reconnect.</div>';
    }
}

// ==================== MESSAGE RENDERING ====================
function appendMessage(msg) {
    const container = document.getElementById('chatMessages');
    // DEDUP: Skip if message with this ID already exists in DOM
    if (msg.id && document.getElementById('msg-' + msg.id)) return;
    const isOwn = msg.sender_id === currentUser.id;

    const div = document.createElement('div');
    div.className = 'message-group';
    div.id = 'msg-' + msg.id;

    let content = '';

    // Reply preview
    if (msg.reply_to_id) {
        content += `<div class="reply-preview">
            <div class="reply-sender">${escapeHtml(msg.reply_to_sender_name || '')}</div>
            <div class="reply-text">${escapeHtml(msg.reply_to_message || '')}</div>
        </div>`;
    }

    // Sender name (in groups, for incoming)
    const conv = conversations.find(c => c.id == activeConversationId);
    if (conv && conv.type === 'GROUP' && !isOwn) {
        content += `<div class="message-sender">${escapeHtml(msg.sender_name || '')}</div>`;
    }

    // Deleted message
    if (msg.is_deleted) {
        content += `<div class="message-text message-deleted"><i class="bi bi-slash-circle"></i> This message was deleted</div>`;
    } else {
        // Attachment
        if (msg.attachment_url) {
            const type = (msg.message_type || '').toUpperCase();
            if (type === 'IMAGE') {
                content += `<div class="attachment-preview"><img src="${escapeAttr(msg.attachment_url)}" onclick="openLightbox('${escapeAttr(msg.attachment_url)}')" loading="lazy"></div>`;
            } else if (type === 'VIDEO') {
                content += `<div class="attachment-preview"><video src="${escapeAttr(msg.attachment_url)}" controls preload="metadata"></video></div>`;
            } else if (type === 'AUDIO') {
                content += `<div class="attachment-preview"><audio src="${escapeAttr(msg.attachment_url)}" controls preload="metadata"></audio></div>`;
            } else if (type === 'FILE') {
                content += `<div class="file-attachment" onclick="window.open('${escapeAttr(msg.attachment_url)}')">
                    <i class="bi bi-file-earmark"></i>
                    <div><div>${escapeHtml(msg.attachment_name || 'File')}</div><small>${formatFileSize(msg.attachment_size)}</small></div>
                </div>`;
            }
        }

        // Text (don't show filename as text for non-text types)
        if (msg.message && (msg.message_type === 'TEXT' || !msg.message_type)) {
            content += `<div class="message-text">${escapeHtml(msg.message)}</div>`;
        }
    }

    // Meta line (time + status on same line, always bottom-right)
    const timeStr = msg.sent_at ? formatMessageTime(msg.sent_at) : '';
    let statusIcon = '';
    if (isOwn && !msg.is_deleted) {
        const ds = msg.delivery_status || 'SENT';
        if (ds === 'READ') statusIcon = '<i class="bi bi-check2-all"></i>';
        else if (ds === 'DELIVERED') statusIcon = '<i class="bi bi-check2-all"></i>';
        else statusIcon = '<i class="bi bi-check2"></i>';
    }

    content += `<div class="message-meta">
        ${msg.is_edited ? '<span class="message-edited">edited</span>' : ''}
        <span class="message-time">${timeStr}</span>
        ${isOwn ? `<span class="message-status ${msg.delivery_status === 'READ' ? 'read' : ''}" data-sender-id="${msg.sender_id}">${statusIcon}</span>` : ''}
    </div>`;

    // Hover actions
    if (!msg.is_deleted) {
        let actions = `<button class="msg-action-btn" onclick="replyToMessage(${msg.id}, '${escapeAttr(msg.sender_name || '')}', '${escapeAttr((msg.message||'').substring(0,50))}')" title="Reply"><i class="bi bi-reply"></i></button>`;
        actions += `<button class="msg-action-btn" onclick="showEmojiForMsg(event, ${msg.id})" title="React"><i class="bi bi-emoji-smile"></i></button>`;
        if (isOwn) {
            if (msg.message_type === 'TEXT' || !msg.message_type) {
                actions += `<button class="msg-action-btn" onclick="editMessage(${msg.id})" title="Edit"><i class="bi bi-pencil"></i></button>`;
            }
            actions += `<button class="msg-action-btn" onclick="deleteMessage(${msg.id})" title="Delete"><i class="bi bi-trash"></i></button>`;
        }
        content = `<div class="message-actions">${actions}</div>` + content;
    }

    div.innerHTML = `<div class="message-bubble ${isOwn ? 'outgoing' : 'incoming'}">${content}</div>`;
    container.appendChild(div);
}

function updateMessageInUI(msg) {
    const el = document.getElementById('msg-' + msg.id);
    if (el) {
        el.remove();
        appendMessage(msg);
    }
}

function updateReactionInUI(msg) {
    // Could show a toast or update badge - simplified for now
}

function scrollToBottom() {
    const container = document.getElementById('chatMessages');
    requestAnimationFrame(() => { container.scrollTop = container.scrollHeight; });
}

// ==================== SEND MESSAGE ====================
function sendMessage() {
    const input = document.getElementById('messageInput');
    const text = input.value.trim();
    if (!text || !activeConversationId) return;

    const dto = {
        conversation_id: activeConversationId,
        message: text,
        message_type: 'TEXT'
    };

    if (replyingTo) {
        dto.reply_to_id = replyingTo.id;
    }

    // Optimistic UI: show message immediately before server confirms
    const tempId = 'temp-' + Date.now();
    const optimisticMsg = {
        id: tempId,
        message: text,
        sender_id: currentUser.id,
        sender_name: currentUser.displayName || currentUser.username,
        conversation_id: activeConversationId,
        sent_at: new Date().toISOString(),
        message_type: 'TEXT',
        delivery_status: 'SENT',
        is_read: false,
        reply_to_id: replyingTo ? replyingTo.id : null,
        reply_to_sender_name: replyingTo ? replyingTo.senderName : null,
        reply_to_message: replyingTo ? replyingTo.text : null
    };
    appendMessage(optimisticMsg);
    scrollToBottom();

    // Send via WebSocket if connected, otherwise queue
    if (stompClient && stompClient.connected) {
        stompClient.send('/app/sendmessage', {'content-type': 'application/json'}, JSON.stringify(dto));
    } else {
        messageQueue.push(dto);
        showToast('Message queued. Reconnecting...', 'warning');
    }

    input.value = '';
    autoResize(input);
    cancelReply();
    sendTypingStop();
}

function handleKeyDown(e) {
    if (e.key === 'Enter' && !e.shiftKey) {
        e.preventDefault();
        sendMessage();
    }
}

// ==================== TYPING ====================
function sendTyping() {
    if (!activeConversationId || !stompClient || !stompClient.connected) return;
    if (!isTyping) {
        isTyping = true;
        stompClient.send('/app/typing', {'content-type': 'application/json'}, JSON.stringify({conversation_id: activeConversationId, typing: true}));
    }
    clearTimeout(typingTimeout);
    typingTimeout = setTimeout(sendTypingStop, 2000);
}

function sendTypingStop() {
    if (isTyping && stompClient && stompClient.connected && activeConversationId) {
        isTyping = false;
        stompClient.send('/app/typing', {'content-type': 'application/json'}, JSON.stringify({conversation_id: activeConversationId, typing: false}));
    }
}

// ==================== READ RECEIPTS ====================
function markAsRead(convId) {
    if (stompClient && stompClient.connected) {
        stompClient.send('/app/readreceipt', {'content-type': 'application/json'}, JSON.stringify({conversation_id: convId}));
    }
    apiPost(`/api/messages/${convId}/read`);
}

// ==================== REPLY ====================
function replyToMessage(msgId, senderName, text) {
    replyingTo = {id: msgId, senderName, text};
    document.getElementById('replyBar').style.display = 'flex';
    document.getElementById('replySender').textContent = senderName;
    document.getElementById('replyText').textContent = text;
    document.getElementById('messageInput').focus();
}

function cancelReply() {
    replyingTo = null;
    document.getElementById('replyBar').style.display = 'none';
}

// ==================== EDIT / DELETE ====================
function editMessage(msgId) {
    const el = document.getElementById('msg-' + msgId);
    if (!el) return;
    const textEl = el.querySelector('.message-text');
    if (!textEl) return;
    const currentText = textEl.textContent;
    const newText = prompt('Edit message:', currentText);
    if (newText !== null && newText.trim() && newText.trim() !== currentText) {
        stompClient.send('/app/editmessage', {'content-type': 'application/json'}, JSON.stringify({id: msgId, message: newText.trim()}));
    }
}

function deleteMessage(msgId) {
    if (confirm('Delete this message?')) {
        stompClient.send('/app/deletemessage', {'content-type': 'application/json'}, JSON.stringify({id: msgId}));
    }
}

// ==================== REACTIONS ====================
const EMOJIS = ['👍', '❤️', '😂', '😮', '😢', '🙏'];

function showEmojiForMsg(event, msgId) {
    event.stopPropagation();
    const picker = document.getElementById('emojiPicker');
    const rect = event.target.getBoundingClientRect();
    picker.style.left = Math.min(rect.left, window.innerWidth - 200) + 'px';
    picker.style.top = (rect.top - 50) + 'px';
    picker.style.display = 'block';
    picker.innerHTML = EMOJIS.map(e => `<span style="cursor:pointer;font-size:20px;padding:4px" onclick="sendReaction(${msgId},'${e}')">${e}</span>`).join('');
    setTimeout(() => document.addEventListener('click', closeEmojiPicker, {once: true}), 10);
}

function closeEmojiPicker() { document.getElementById('emojiPicker').style.display = 'none'; }

function sendReaction(msgId, emoji) {
    if (!stompClient || !stompClient.connected) return;
    stompClient.send('/app/reaction', {'content-type': 'application/json'}, JSON.stringify({id: msgId, conversation_id: activeConversationId, reaction: emoji}));
    closeEmojiPicker();
}

// ==================== FILE UPLOAD ====================
async function handleFileSelect(event) {
    const file = event.target.files[0];
    if (!file || !activeConversationId) return;
    event.target.value = '';

    try {
        const formData = new FormData();
        formData.append('file', file);

        const resp = await fetch('/api/upload', {
            method: 'POST',
            headers: {'Authorization': 'Bearer ' + token},
            body: formData
        });

        if (!resp.ok) {
            const err = await resp.json();
            alert(err.error || 'Upload failed');
            return;
        }

        const data = await resp.json();

        const dto = {
            conversation_id: activeConversationId,
            message: data.originalName || file.name,
            message_type: data.fileType,
            attachment_url: data.url,
            attachment_name: data.originalName,
            attachment_size: data.size
        };

        if (replyingTo) {
            dto.reply_to_id = replyingTo.id;
            cancelReply();
        }

        stompClient.send('/app/sendmessage', {'content-type': 'application/json'}, JSON.stringify(dto));
    } catch (e) {
        alert('Upload failed: ' + e.message);
    }
}

// ==================== VOICE RECORDING ====================
async function startRecording() {
    if (!activeConversationId) return;
    try {
        const stream = await navigator.mediaDevices.getUserMedia({audio: true});
        mediaRecorder = new MediaRecorder(stream, {mimeType: 'audio/webm'});
        audioChunks = [];

        mediaRecorder.ondataavailable = e => { if (e.data.size > 0) audioChunks.push(e.data); };
        mediaRecorder.onstop = async () => {
            stream.getTracks().forEach(t => t.stop());
            if (audioChunks.length === 0) return;

            const blob = new Blob(audioChunks, {type: 'audio/webm'});
            const file = new File([blob], 'voice-note.webm', {type: 'audio/webm'});

            const formData = new FormData();
            formData.append('file', file);
            try {
                const resp = await fetch('/api/upload', {
                    method: 'POST',
                    headers: {'Authorization': 'Bearer ' + token},
                    body: formData
                });
                if (resp.ok) {
                    const data = await resp.json();
                    stompClient.send('/app/sendmessage', {'content-type': 'application/json'}, JSON.stringify({
                        conversation_id: activeConversationId,
                        message: 'Voice note',
                        message_type: 'AUDIO',
                        attachment_url: data.url,
                        attachment_name: 'voice-note.webm',
                        attachment_size: data.size
                    }));
                }
            } catch (e) { console.error('Voice upload failed', e); }
        };

        mediaRecorder.start();
        recordingStartTime = Date.now();
        document.getElementById('inputWrapper').style.display = 'none';
        document.getElementById('voiceRecordingUI').style.display = 'flex';
        recordingInterval = setInterval(updateRecordingTime, 1000);
    } catch (e) {
        alert('Microphone access denied');
    }
}

function stopRecording() {
    if (mediaRecorder && mediaRecorder.state === 'recording') {
        mediaRecorder.stop();
        clearInterval(recordingInterval);
        document.getElementById('inputWrapper').style.display = '';
        document.getElementById('voiceRecordingUI').style.display = 'none';
    }
}

function cancelRecording() {
    if (mediaRecorder && mediaRecorder.state === 'recording') {
        audioChunks = [];
        mediaRecorder.stop();
        clearInterval(recordingInterval);
        document.getElementById('inputWrapper').style.display = '';
        document.getElementById('voiceRecordingUI').style.display = 'none';
    }
}

function updateRecordingTime() {
    const elapsed = Math.floor((Date.now() - recordingStartTime) / 1000);
    const m = Math.floor(elapsed / 60);
    const s = elapsed % 60;
    document.getElementById('recordingTime').textContent = m + ':' + (s < 10 ? '0' : '') + s;
}

// ==================== NEW CHAT ====================
function showNewChatModal() {
    document.getElementById('newChatSearch').value = '';
    document.getElementById('newChatUserList').innerHTML = '<div style="padding:16px;text-align:center;color:#999">Type a username to search</div>';
    new bootstrap.Modal(document.getElementById('newChatModal')).show();
}

async function searchUsersForChat() {
    const query = document.getElementById('newChatSearch').value.trim();
    const list = document.getElementById('newChatUserList');

    // Only search when user types at least 2 characters
    if (query.length < 2) {
        list.innerHTML = '<div style="padding:16px;text-align:center;color:#999">Type at least 2 characters to search</div>';
        return;
    }

    try {
        const resp = await apiGet('/api/users/search?query=' + encodeURIComponent(query));
        if (resp.ok) {
            const users = await resp.json();
            renderUserList(list, users, 'startDirectChat');
        }
    } catch (e) { console.error(e); }
}

function renderUserList(container, users, onClickFn) {
    if (users.length === 0) {
        container.innerHTML = '<div style="padding:16px;text-align:center;color:#999">No users found</div>';
        return;
    }
    container.innerHTML = users.map(u => {
        const avatarContent = u.profilePicture
            ? `<img src="${escapeAttr(u.profilePicture)}" alt="" style="width:100%;height:100%;border-radius:50%;object-fit:cover">`
            : (u.displayName||u.username).charAt(0).toUpperCase();
        return `<div class="modal-user-item" onclick="${onClickFn}(${u.id})">
            <div class="conv-avatar" style="width:36px;height:36px;font-size:14px;margin-right:10px">${avatarContent}</div>
            <div>
                <div style="font-weight:600">${escapeHtml(u.displayName || u.username)}</div>
                <div style="font-size:12px;color:#999">@${escapeHtml(u.username)}</div>
            </div>
            ${u.isOnline ? '<div class="ms-auto"><span style="color:#28a745;font-size:10px">● online</span></div>' : ''}
        </div>`;
    }).join('');
}

async function startDirectChat(userId) {
    bootstrap.Modal.getInstance(document.getElementById('newChatModal')).hide();
    try {
        const resp = await apiPost(`/api/conversations/direct/${userId}`);
        if (resp.ok) {
            const conv = await resp.json();
            // Add to list if not already present
            const existing = conversations.find(c => c.id == conv.id);
            if (!existing) {
                conversations.unshift(conv);
            }
            renderConversationList();
            openConversation(conv.id);
        }
    } catch (e) { console.error(e); }
}

// ==================== GROUP CHAT ====================
function showNewGroupModal() {
    selectedGroupMembers = [];
    document.getElementById('groupNameInput').value = '';
    document.getElementById('groupDescInput').value = '';
    document.getElementById('groupMemberSearch').value = '';
    document.getElementById('selectedMembers').innerHTML = '';
    document.getElementById('groupUserList').innerHTML = '<div style="padding:16px;text-align:center;color:#999">Search users to add</div>';
    new bootstrap.Modal(document.getElementById('newGroupModal')).show();
}

async function searchUsersForGroup() {
    const query = document.getElementById('groupMemberSearch').value.trim();
    const list = document.getElementById('groupUserList');

    if (query.length < 2) {
        list.innerHTML = '<div style="padding:16px;text-align:center;color:#999">Type at least 2 characters to search</div>';
        return;
    }

    try {
        const resp = await apiGet('/api/users/search?query=' + encodeURIComponent(query));
        if (resp.ok) {
            const users = await resp.json();
            list.innerHTML = users.map(u => {
                const selected = selectedGroupMembers.find(m => m.id === u.id);
                return `<div class="modal-user-item ${selected?'selected':''}" onclick="toggleGroupMember(${u.id}, '${escapeAttr(u.displayName||u.username)}')">
                    <div class="conv-avatar" style="width:32px;height:32px;font-size:12px;margin-right:10px">${(u.displayName||u.username).charAt(0).toUpperCase()}</div>
                    <div style="font-weight:500;flex:1">${escapeHtml(u.displayName || u.username)}</div>
                    ${selected ? '<i class="bi bi-check-circle-fill text-primary"></i>' : '<i class="bi bi-circle text-muted"></i>'}
                </div>`;
            }).join('') || '<div style="padding:16px;text-align:center;color:#999">No users found</div>';
        }
    } catch (e) { console.error(e); }
}

function toggleGroupMember(id, name) {
    const idx = selectedGroupMembers.findIndex(m => m.id === id);
    if (idx >= 0) selectedGroupMembers.splice(idx, 1);
    else selectedGroupMembers.push({id, name});
    renderSelectedMembers();
    searchUsersForGroup(); // re-render to update checkmarks
}

function renderSelectedMembers() {
    document.getElementById('selectedMembers').innerHTML = selectedGroupMembers.length > 0
        ? selectedGroupMembers.map(m =>
            `<span class="badge bg-primary me-1 mb-1">${escapeHtml(m.name)} <span style="cursor:pointer" onclick="toggleGroupMember(${m.id},'${escapeAttr(m.name)}')">&times;</span></span>`
        ).join('')
        : '';
}

async function createGroup() {
    const name = document.getElementById('groupNameInput').value.trim();
    const desc = document.getElementById('groupDescInput').value.trim();
    if (!name) return alert('Group name is required');
    if (selectedGroupMembers.length === 0) return alert('Add at least one member');

    try {
        const resp = await apiPost('/api/conversations/group', {
            name, description: desc,
            memberIds: selectedGroupMembers.map(m => m.id)
        });
        if (resp.ok) {
            const conv = await resp.json();
            conversations.unshift(conv);
            renderConversationList();
            bootstrap.Modal.getInstance(document.getElementById('newGroupModal')).hide();
            openConversation(conv.id);
        } else {
            const err = await resp.json();
            alert(err.error || 'Failed to create group');
        }
    } catch (e) { alert('Error creating group'); }
}

function showGroupInfo() {
    const conv = conversations.find(c => c.id == activeConversationId);
    if (!conv || conv.type !== 'GROUP') return;

    document.getElementById('groupPanel').classList.add('open');
    document.getElementById('groupOverlay').classList.add('open');

    const initials = (conv.name || '?').charAt(0).toUpperCase();
    document.getElementById('groupAvatarLarge').textContent = initials;
    document.getElementById('groupPanelName').textContent = conv.name;
    document.getElementById('groupPanelDesc').textContent = conv.description || 'No description';
    document.getElementById('groupMemberCount').textContent = conv.members ? conv.members.length : 0;

    const memberList = document.getElementById('groupMemberList');
    if (!conv.members) { memberList.innerHTML = '<p class="text-muted">No members</p>'; return; }

    memberList.innerHTML = conv.members.map(m => {
        const isMe = m.id === currentUser.id;
        const roleLabel = m.role === 'OWNER' ? 'Owner' : m.role === 'ADMIN' ? 'Admin' : '';
        const roleBadge = roleLabel ? `<span class="member-role">${roleLabel}</span>` : '';
        const removeBtn = (m.role !== 'OWNER' && !isMe)
            ? `<button class="btn btn-sm btn-outline-danger" style="font-size:11px;padding:2px 6px" onclick="removeMemberFromGroup(${m.id})">Remove</button>`
            : '';
        return `<div class="member-item">
            <div class="conv-avatar">${(m.displayName || m.username).charAt(0).toUpperCase()}</div>
            <div class="member-info">
                <div class="member-name">${escapeHtml(m.displayName || m.username)}${isMe ? ' (You)' : ''}</div>
                ${roleBadge}
            </div>
            ${removeBtn}
        </div>`;
    }).join('');
}

function closeGroupPanel() {
    document.getElementById('groupPanel').classList.remove('open');
    document.getElementById('groupOverlay').classList.remove('open');
}

async function removeMemberFromGroup(userId) {
    if (!confirm('Remove this member from the group?')) return;
    try {
        const resp = await apiDelete(`/api/conversations/${activeConversationId}/members/${userId}`);
        if (resp.ok) {
            showToast('Member removed', 'success');
            await loadConversations();
            showGroupInfo();
        } else {
            const err = await resp.json();
            showToast(err.error || 'Failed to remove', 'error');
        }
    } catch (e) { showToast('Error removing member', 'error'); }
}

async function leaveGroup() {
    if (!confirm('Leave this group? You won\'t receive messages anymore.')) return;
    try {
        const resp = await apiDelete(`/api/conversations/${activeConversationId}/members/${currentUser.id}`);
        if (resp.ok) {
            closeGroupPanel();
            conversations = conversations.filter(c => c.id != activeConversationId);
            activeConversationId = null;
            document.getElementById('activeChatView').style.display = 'none';
            document.getElementById('emptyState').style.display = '';
            renderConversationList();
            showToast('Left group', 'success');
        } else {
            const err = await resp.json();
            showToast(err.error || 'Failed to leave', 'error');
        }
    } catch (e) { showToast('Error leaving group', 'error'); }
}

// ==================== DELETE / BLOCK ACTIONS ====================
async function deleteConversation() {
    if (!activeConversationId) return;
    if (!confirm('Delete this entire conversation? All messages will be permanently removed.')) return;
    try {
        const resp = await apiDelete(`/api/conversations/${activeConversationId}`);
        if (resp.ok) {
            conversations = conversations.filter(c => c.id != activeConversationId);
            activeConversationId = null;
            document.getElementById('activeChatView').style.display = 'none';
            document.getElementById('emptyState').style.display = '';
            renderConversationList();
            showToast('Conversation deleted', 'success');
        } else {
            const err = await resp.json();
            showToast(err.error || 'Failed to delete', 'error');
        }
    } catch (e) { showToast('Error deleting conversation', 'error'); }
}

async function clearChat() {
    if (!activeConversationId) return;
    if (!confirm('Clear all messages in this chat? This cannot be undone.')) return;
    try {
        const resp = await apiDelete(`/api/conversations/${activeConversationId}/messages`);
        if (resp.ok) {
            document.getElementById('chatMessages').innerHTML = '<div style="text-align:center;padding:20px;color:#999">No messages yet</div>';
            showToast('Chat cleared', 'success');
        } else {
            showToast('Failed to clear chat', 'error');
        }
    } catch (e) { showToast('Error clearing chat', 'error'); }
}

async function blockUserFromChat() {
    const conv = conversations.find(c => c.id == activeConversationId);
    if (!conv || conv.type !== 'DIRECT' || !conv.members) return;
    const other = conv.members.find(m => m.id !== currentUser.id);
    if (!other) return;
    if (!confirm(`Block ${other.displayName || other.username}? They won't be able to message you.`)) return;
    try {
        const resp = await apiPost(`/api/users/${other.id}/block`);
        if (resp.ok) {
            showToast(`${other.displayName || other.username} blocked`, 'success');
        } else {
            const err = await resp.json();
            showToast(err.error || 'Failed to block', 'error');
        }
    } catch (e) { showToast('Error blocking user', 'error'); }
}

async function unblockUserFromChat() {
    const conv = conversations.find(c => c.id == activeConversationId);
    if (!conv || conv.type !== 'DIRECT' || !conv.members) return;
    const other = conv.members.find(m => m.id !== currentUser.id);
    if (!other) return;
    try {
        const resp = await apiDelete(`/api/users/${other.id}/block`);
        if (resp.ok) {
            showToast(`${other.displayName || other.username} unblocked`, 'success');
        } else {
            const err = await resp.json();
            showToast(err.error || 'Failed to unblock', 'error');
        }
    } catch (e) { showToast('Error unblocking user', 'error'); }
}

// ==================== UTILITIES ====================
function backToList() {
    document.querySelector('.app-container').classList.remove('chat-open');
    activeConversationId = null;
}

function autoResize(el) {
    el.style.height = 'auto';
    el.style.height = Math.min(el.scrollHeight, 120) + 'px';
}

function escapeHtml(str) {
    if (!str) return '';
    return str.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;').replace(/"/g,'&quot;');
}

function escapeAttr(str) {
    if (!str) return '';
    return str.replace(/\\/g, '\\\\').replace(/'/g, "\\'").replace(/"/g, '&quot;');
}

// Format time for conversation list (today/yesterday/date)
function formatTime(dateStr) {
    if (!dateStr) return '';
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return '';
    const now = new Date();
    const isToday = d.toDateString() === now.toDateString();
    if (isToday) return d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
    const yesterday = new Date(now); yesterday.setDate(yesterday.getDate()-1);
    if (d.toDateString() === yesterday.toDateString()) return 'Yesterday';
    return d.toLocaleDateString([], {month:'short', day:'numeric'});
}

// Format time specifically for message bubbles (always show HH:MM)
function formatMessageTime(dateStr) {
    if (!dateStr) return '';
    const d = new Date(dateStr);
    if (isNaN(d.getTime())) return '';
    return d.toLocaleTimeString([], {hour:'2-digit', minute:'2-digit'});
}

function formatFileSize(bytes) {
    if (!bytes) return '';
    if (bytes < 1024) return bytes + ' B';
    if (bytes < 1024*1024) return (bytes/1024).toFixed(1) + ' KB';
    return (bytes/1024/1024).toFixed(1) + ' MB';
}

function openLightbox(url) {
    document.getElementById('lightboxImg').src = url;
    document.getElementById('lightbox').classList.add('active');
}
function closeLightbox() { document.getElementById('lightbox').classList.remove('active'); }

// Auto-read when tab becomes visible
document.addEventListener('visibilitychange', () => {
    if (!document.hidden && activeConversationId) {
        const conv = conversations.find(c => c.id == activeConversationId);
        if (conv && conv.unread_count > 0) {
            markAsRead(activeConversationId);
            conv.unread_count = 0;
            renderConversationList();
        }
    }
});

// ==================== TOAST NOTIFICATIONS (A3) ====================
function showToast(message, type = 'info') {
    const container = document.getElementById('toastContainer') || createToastContainer();
    const toast = document.createElement('div');
    toast.className = `toast-notification toast-${type}`;
    toast.textContent = message;
    container.appendChild(toast);
    setTimeout(() => { toast.classList.add('show'); }, 10);
    setTimeout(() => {
        toast.classList.remove('show');
        setTimeout(() => toast.remove(), 300);
    }, 4000);
}

function createToastContainer() {
    const c = document.createElement('div');
    c.id = 'toastContainer';
    c.style.cssText = 'position:fixed;top:20px;right:20px;z-index:10000;display:flex;flex-direction:column;gap:8px;';
    document.body.appendChild(c);
    return c;
}

// ==================== MESSAGE QUEUE (A3 retry) ====================
let messageQueue = [];

function flushMessageQueue() {
    if (!stompClient || !stompClient.connected || messageQueue.length === 0) return;
    const queued = [...messageQueue];
    messageQueue = [];
    queued.forEach(dto => {
        try {
            stompClient.send('/app/sendmessage', {'content-type': 'application/json'}, JSON.stringify(dto));
        } catch (e) {
            messageQueue.push(dto); // re-queue if still failing
        }
    });
    if (messageQueue.length > 0) {
        showToast(`${messageQueue.length} message(s) still pending...`, 'warning');
    }
}

// ==================== BROWSER NOTIFICATIONS ====================
function requestNotificationPermission() {
    if ('Notification' in window && Notification.permission === 'default') {
        Notification.requestPermission();
    }
}

function showBrowserNotification(title, body) {
    if ('Notification' in window && Notification.permission === 'granted' && document.hidden) {
        new Notification(title, { body, icon: '/static/images/chat-icon.png' });
    }
}

// Request notification permission after login
if (currentUser) requestNotificationPermission();


// ==================== PROFILE / SETTINGS PANEL ====================
function openProfilePanel() {
    document.getElementById('profilePanel').classList.add('open');
    document.getElementById('profileOverlay').classList.add('open');
    const initials = (currentUser.displayName || currentUser.username).charAt(0).toUpperCase();
    document.getElementById('profileAvatarLarge').innerHTML = currentUser.profilePicture
        ? `<img src="${currentUser.profilePicture}" alt=""><div class="avatar-upload-btn" onclick="document.getElementById('avatarInput').click()"><i class="bi bi-camera"></i></div>`
        : `${initials}<div class="avatar-upload-btn" onclick="document.getElementById('avatarInput').click()"><i class="bi bi-camera"></i></div>`;
    document.getElementById('profileDisplayName').textContent = currentUser.displayName || currentUser.username;
    document.getElementById('profileUsername').textContent = '@' + currentUser.username;
    document.getElementById('settingsDisplayName').textContent = currentUser.displayName || currentUser.username;
    document.getElementById('settingsStatus').textContent = currentUser.status || 'Available';
    document.getElementById('darkModeStatus').textContent = document.documentElement.getAttribute('data-theme') === 'dark' ? 'On' : 'Off';
}

function closeProfilePanel() {
    document.getElementById('profilePanel').classList.remove('open');
    document.getElementById('profileOverlay').classList.remove('open');
}

async function editDisplayName() {
    const newName = prompt('Enter new display name:', currentUser.displayName || '');
    if (newName && newName.trim()) {
        try {
            const resp = await apiPut('/api/users/profile', { displayName: newName.trim() });
            if (resp.ok) {
                const data = await resp.json();
                currentUser.displayName = data.displayName;
                sessionStorage.setItem('chat_user', JSON.stringify(currentUser));
                document.getElementById('profileDisplayName').textContent = data.displayName;
                document.getElementById('settingsDisplayName').textContent = data.displayName;
                document.getElementById('myAvatar').textContent = data.displayName.charAt(0).toUpperCase();
                showToast('Display name updated', 'success');
            }
        } catch (e) { showToast('Failed to update', 'error'); }
    }
}

async function editStatus() {
    const newStatus = prompt('Enter status message:', currentUser.status || 'Available');
    if (newStatus !== null) {
        try {
            const resp = await apiPut('/api/users/profile', { status: newStatus.trim() || 'Available' });
            if (resp.ok) {
                const data = await resp.json();
                currentUser.status = data.status;
                sessionStorage.setItem('chat_user', JSON.stringify(currentUser));
                document.getElementById('settingsStatus').textContent = data.status;
                showToast('Status updated', 'success');
            }
        } catch (e) { showToast('Failed to update', 'error'); }
    }
}

async function uploadAvatar(event) {
    const file = event.target.files[0];
    if (!file) return;
    event.target.value = '';
    try {
        const formData = new FormData();
        formData.append('file', file);
        const uploadResp = await fetch('/api/upload', { method: 'POST', headers: {'Authorization': 'Bearer ' + token}, body: formData });
        if (!uploadResp.ok) { showToast('Upload failed', 'error'); return; }
        const uploadData = await uploadResp.json();
        const resp = await apiPut('/api/users/profile', { profilePicture: uploadData.url });
        if (resp.ok) {
            currentUser.profilePicture = uploadData.url;
            sessionStorage.setItem('chat_user', JSON.stringify(currentUser));
            openProfilePanel();
            showToast('Avatar updated', 'success');
        }
    } catch (e) { showToast('Failed to upload avatar', 'error'); }
}

async function viewBlockedUsers() {
    try {
        const resp = await apiGet('/api/users/blocked');
        if (resp.ok) {
            const blocked = await resp.json();
            if (blocked.length === 0) { alert('No blocked users'); return; }
            const list = blocked.map(b => `• ${b.displayName || b.username}`).join('\n');
            alert('Blocked Users:\n' + list);
        }
    } catch (e) { showToast('Failed to load', 'error'); }
}

// ==================== DARK MODE ====================
function toggleDarkMode() {
    const html = document.documentElement;
    const isDark = html.getAttribute('data-theme') === 'dark';
    html.setAttribute('data-theme', isDark ? 'light' : 'dark');
    localStorage.setItem('chat_theme', isDark ? 'light' : 'dark');
    document.getElementById('darkModeStatus').textContent = isDark ? 'Off' : 'On';
}

// Apply saved theme on load
(function() {
    const saved = localStorage.getItem('chat_theme');
    if (saved === 'dark') document.documentElement.setAttribute('data-theme', 'dark');
})();
