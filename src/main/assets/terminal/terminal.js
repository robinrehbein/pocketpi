'use strict';
// The app's TerminalTheme, through /theme.css; an empty value leaves xterm's default.
const themeStyle = getComputedStyle(document.documentElement);
const theme = {};
for (const [key, name] of [['background', '--terminal-background'], ['foreground', '--terminal-foreground']]) {
  const value = themeStyle.getPropertyValue(name).trim();
  if (value) theme[key] = value;
}
const terminal = new Terminal({disableStdin:true, scrollback:5000, fontSize:14, fontFamily:'monospace', theme:theme});
terminal.open(document.getElementById('terminal'));
// xterm sizes its screen element to cols x rows cells; dividing gives its real cell size. Before
// its first measurement, estimate with the font: the width of a W and a 17 px line.
function cellSize() {
  const screen = document.querySelector('.xterm-screen');
  const rect = screen ? screen.getBoundingClientRect() : null;
  if (rect && rect.width > 0 && rect.height > 0) return {width: rect.width / terminal.cols, height: rect.height / terminal.rows};
  const ctx = document.createElement('canvas').getContext('2d');
  ctx.font = '14px monospace';
  return {width: ctx.measureText('W').width, height: 17};
}
function size() {
  const cell = cellSize();
  const cols = Math.max(20, Math.min(500, Math.floor(document.documentElement.clientWidth / cell.width)));
  const rows = Math.max(5, Math.min(300, Math.floor(document.documentElement.clientHeight / cell.height)));
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
// The first call may run before xterm measured its cells; measure again once it has drawn.
requestAnimationFrame(size);
