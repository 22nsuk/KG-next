import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { test } from 'node:test';
import vm from 'node:vm';

const source = readFileSync(new URL('../src/main/resources/web/board.js', import.meta.url), 'utf8');
function storage(initial = {}) {
  const data = new Map(Object.entries(initial));
  return { getItem: k => data.get(k) ?? null, setItem: (k, v) => data.set(k, v), removeItem: k => data.delete(k) };
}
function browser({ local = storage({ webBoardClientId: 'owner' }), session = storage() } = {}) {
  const sockets = [], timers = [], elements = new Map();
  const element = id => {
    if (!elements.has(id)) elements.set(id, {
      style: {}, classList: { toggle() {} }, clientWidth: 0, clientHeight: 0,
      events: {}, addEventListener(type, callback) { this.events[type] = callback; },
      getContext() { return {}; }
    });
    return elements.get(id);
  };
  class Socket {
    static OPEN = 1;
    constructor(url) { this.url = url; this.sent = []; this.readyState = 0; sockets.push(this); }
    send(text) { this.sent.push(JSON.parse(text)); }
    open() { this.readyState = 1; this.onopen(); }
    receive(msg) { this.onmessage({ data: JSON.stringify(msg) }); }
    close() { this.readyState = 3; this.onclose(); }
  }
  const context = { localStorage: local, sessionStorage: session,
    document: { getElementById: element }, location: { hostname: 'localhost', port: '9998' },
    WebSocket: Socket, window: { WS_PORT: 9999, addEventListener() {} }, alert() {},
    setTimeout(fn) { timers.push(fn); return timers.length; }, clearTimeout() {}, console };
  vm.runInNewContext(source, context, { filename: 'board.js' });
  return { sockets, session, local, elements, click(id) { element(id).events.click(); },
    reconnect() { timers.shift()(); sockets.at(-1).open(); return sockets.at(-1); } };
}
const active = (sessionId = 'session-1') => ({ type: 'trial_state', active: true,
  ownerClientId: 'owner', sessionId, canBack: true, canForward: true });
const grant = (resumeToken = 'private-token') => ({ type: 'trial_granted', sessionId: 'session-1', resumeToken });

test('public owner identity alone never enables controls or emits a mutation', () => {
  const b = browser(), socket = b.sockets[0]; socket.open(); socket.receive(active());
  for (const action of ['exit', 'back', 'forward', 'reset']) b.click(`trial-${action}-btn`);
  assert.equal(socket.sent.length, 0);
  assert.equal(b.elements.get('trial-active-controls').style.display, 'none');
  assert.equal(b.session.getItem('webBoardTrialGrant'), null);
});

test('private grant enables commands bearing the session id, not the secret token', () => {
  const b = browser(), socket = b.sockets[0]; socket.open();
  b.click('trial-enter-btn'); assert.equal(socket.sent[0].type, 'enter_trial');
  socket.receive(active()); socket.receive(grant());
  b.click('trial-reset-btn');
  assert.deepEqual(socket.sent.at(-1), { type: 'trial_reset', clientId: 'owner', sessionId: 'session-1' });
  assert.equal(b.local.getItem('webBoardTrialGrant'), null);
  assert.equal(JSON.parse(b.session.getItem('webBoardTrialGrant')).resumeToken, 'private-token');
  assert.equal(socket.url.includes('private-token'), false);
});

test('reconnect is unauthorized until the private resume reply, even after a cached public state', () => {
  const b = browser(), old = b.sockets[0]; old.open(); old.receive(active()); old.receive(grant());
  old.close(); b.click('trial-reset-btn'); assert.equal(old.sent.length, 0);
  const current = b.reconnect();
  assert.deepEqual(current.sent[0], { type: 'resume_trial', clientId: 'owner', sessionId: 'session-1', resumeToken: 'private-token' });
  current.receive({ type: 'trial_state', active: false });
  b.click('trial-reset-btn'); assert.equal(current.sent.length, 1);
  assert.notEqual(b.session.getItem('webBoardTrialGrant'), null);
  current.receive(active()); current.receive(grant('rotated-token'));
  old.receive({ type: 'trial_state', active: false }); // stale socket callbacks cannot clear new rights
  b.click('trial-reset-btn'); assert.equal(current.sent.at(-1).type, 'trial_reset');
  assert.equal(JSON.parse(b.session.getItem('webBoardTrialGrant')).resumeToken, 'rotated-token');
});

test('reload can resume but another tab sharing localStorage cannot inherit ownership', () => {
  const owner = browser(); owner.sockets[0].open(); owner.sockets[0].receive(active()); owner.sockets[0].receive(grant());
  const observer = browser({ local: owner.local }); observer.sockets[0].open(); observer.sockets[0].receive(active());
  observer.click('trial-exit-btn'); assert.equal(observer.sockets[0].sent.length, 0);
  const reloaded = browser({ local: owner.local, session: owner.session }); reloaded.sockets[0].open();
  assert.equal(reloaded.sockets[0].sent[0].type, 'resume_trial');
});

for (const invalidation of [{ type: 'trial_denied', reason: 'expired' }, { type: 'trial_state', active: false }, active('new-session')]) {
  test(`a ${invalidation.type}/${invalidation.reason ?? invalidation.sessionId ?? 'idle'} message revokes the old grant`, () => {
    const b = browser(), socket = b.sockets[0]; socket.open(); socket.receive(active()); socket.receive(grant());
    socket.receive(invalidation); b.click('trial-reset-btn');
    assert.equal(socket.sent.length, 0); assert.equal(b.session.getItem('webBoardTrialGrant'), null);
  });
}
