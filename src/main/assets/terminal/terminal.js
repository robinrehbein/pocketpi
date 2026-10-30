'use strict';
const terminal = new Terminal({disableStdin:true, scrollback:5000, fontSize:14, fontFamily:'monospace', theme:{background:'#101010'}});
terminal.open(document.getElementById('terminal'));
function size() {
  const canvas = document.createElement('canvas');
  const ctx = canvas.getContext('2d');
  ctx.font = '14px monospace';
  const cols = Math.max(20, Math.min(500, Math.floor(document.documentElement.clientWidth / ctx.measureText('W').width)));
  const rows = Math.max(5, Math.min(300, Math.floor(document.documentElement.clientHeight / 17)));
  if (cols !== terminal.cols || rows !== terminal.rows) terminal.resize(cols, rows);
  terminalBridge.postMessage(JSON.stringify({type:'size',cols:cols,rows:rows}));
}
window.addEventListener('message', event => {
  if (typeof event.data !== 'string' || event.data.length > 16384) return;
  terminal.write(event.data, () => terminalBridge.postMessage('{"type":"written"}'));
});
new ResizeObserver(size).observe(document.getElementById('terminal'));
terminalBridge.postMessage('{"type":"ready"}');
size();
