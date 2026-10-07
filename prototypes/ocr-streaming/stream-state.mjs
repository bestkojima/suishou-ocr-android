// Prototype replay state: synthetic character chunks, not real inference events.
export const initialState = () => ({ phase: 'idle', index: 0, blocks: [], events: [], ticks: 0 });
export function reduceReplay(state, action) {
  if (action.type === 'reset') return initialState();
  if (action.type === 'pause') return state.phase === 'running' ? { ...state, phase: 'paused' } : state;
  if (action.type === 'play') return state.phase === 'complete' ? state : { ...state, phase: 'running' };
  if (action.type !== 'tick' || state.phase !== 'running') return state;
  const source = action.source[state.index];
  if (!source) return { ...state, phase: 'complete' };
  const existing = state.blocks[state.index];
  const chars = Array.from(source.markdown);
  const count = Math.min(chars.length, (existing?.count || 0) + action.chunk);
  const done = count === chars.length;
  const next = { ...source, raw: chars.slice(0, count).join(''), count, done };
  const blocks = state.blocks.slice();
  blocks[state.index] = next;
  const event = `${source.id} ${!existing ? 'block_start' : done ? 'block_commit' : 'text_delta'} ${count}/${chars.length}`;
  return { phase: done && state.index + 1 === action.source.length ? 'complete' : 'running',
    index: state.index + (done ? 1 : 0), blocks, ticks: state.ticks + 1,
    events: [...state.events, event].slice(-8) };
}

// Do not ask the math renderer to repeatedly typeset an unfinished expression.
// Keep committed source intact; this is a transient display buffer only.
export function displayPrefix(text, done) {
  if (done) return text;
  let open = -1, delimiter = '';
  for (let i = 0; i < text.length; i++) {
    if (text[i] === '\\') { i++; continue; }
    if (text[i] !== '$') continue;
    const token = text[i + 1] === '$' ? '$$' : '$';
    if (open < 0) { open = i; delimiter = token; }
    else if (delimiter === token) { open = -1; delimiter = ''; }
    if (token === '$$') i++;
  }
  return open < 0 ? text : text.slice(0, open);
}
