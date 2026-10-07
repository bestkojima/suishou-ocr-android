import React, { memo, useEffect, useLayoutEffect, useMemo, useReducer, useRef, useState } from 'react';
import { createRoot } from 'react-dom/client';
import { Streamdown, defaultRehypePlugins } from 'streamdown';
import { createMathPlugin } from '@streamdown/math';
import { cjk } from '@streamdown/cjk';
import { initialState, reduceReplay, displayPrefix } from './stream-state.mjs';
import datasets from './fixture-data.json';
import './style.css';

const plugins = { math: createMathPlugin({ singleDollarTextMath: true }), cjk };
const assetUrl = url => {
  const relative = url.replace(/^https:\/\/ocr\.local\//, '');
  return /^assets\/[a-zA-Z0-9_.-]+$/.test(relative) ? relative : '';
};
// Give document-relative paths a controlled origin, including when opened via file://.
// The custom image component resolves these only against the bundled asset manifest.
const [hardenPlugin] = defaultRehypePlugins.harden;
const rehypePlugins = [defaultRehypePlugins.raw, defaultRehypePlugins.sanitize,
  [hardenPlugin, { defaultOrigin: 'https://ocr.local/', allowedImagePrefixes: ['https://ocr.local/assets/'],
    allowedLinkPrefixes: [], allowDataImages: false }]];
const labels = { idle: '准备回放', running: '正在输出', paused: '已暂停', complete: '回放完成' };
const icons = { text: '正文', table: '表格', image: '图片', formula: '公式' };

function StableImage({ asset, name, scenario, openImage }) {
  const [state, setState] = useState('waiting');
  const [attempt, setAttempt] = useState(0);
  const [source, setSource] = useState(null);
  useEffect(() => {
    setState('waiting'); setSource(null);
    const timeout = setTimeout(() => {
      if (!asset || (scenario === 'failure' && attempt === 0)) setState('error');
      else { setState('loading'); setSource(asset.src); }
    }, scenario === 'slow' ? 2400 : 120);
    return () => clearTimeout(timeout);
  }, [asset, scenario, attempt]);
  const width = asset?.width || 400, height = asset?.height || 220;
  return <span className="image-frame" data-image={name} data-image-state={state}
    style={{ aspectRatio: `${width} / ${height}`, maxWidth: `${width}px` }}>
    {state !== 'loaded' && <span className="image-placeholder">{state === 'error' ? <><span>图片加载失败</span><button onClick={() => setAttempt(x => x + 1)}>重试图片</button></> : '图片加载中…'}</span>}
    {source && <img alt="文档插图，点击放大" src={source} width={width} height={height}
      style={{ opacity: state === 'loaded' ? 1 : 0 }} onLoad={() => setState('loaded')}
      onError={() => setState('error')} onClick={() => openImage(asset.src)} />}
    {state === 'loaded' && <button className="image-expand" aria-label="放大文档插图" onClick={() => openImage(asset.src)}>放大</button>}
  </span>;
}

const Block = memo(function Block({ block, components }) {
  const atomic = block.type === 'formula' || block.type === 'table';
  const raw = atomic && !block.done ? '' : displayPrefix(block.raw, block.done);
  return <section className="document-block" data-block-id={block.id} data-done={block.done} data-kind={block.type}>
    <div className="block-debug">{block.id} · {icons[block.type]} · {block.done ? '已固定' : '接收中'}{block.sourceStatus !== 'ok' ? ' · 原始结果待核对' : ''}</div>
    {raw ? <Streamdown plugins={plugins} components={components} controls={false} animated={false}
      isAnimating={!block.done} parseIncompleteMarkdown={true} urlTransform={assetUrl} rehypePlugins={rehypePlugins}
      className="markdown-content">{raw}</Streamdown> : <div className="block-pending">正在接收{icons[block.type]}…</div>}
    {!block.done && <span className="tail-label">{atomic ? '结构完整后显示' : '继续生成中'} · {block.count} 字符</span>}
  </section>;
});

function App() {
  const [sample, setSample] = useState('odb-13');
  const [scenario, setScenario] = useState('normal');
  const [orientation, setOrientation] = useState('portrait');
  const [debug, setDebug] = useState(false);
  const [chunk, setChunk] = useState(8);
  const [viewer, setViewer] = useState(null);
  const [zoom, setZoom] = useState(1);
  const [epoch, setEpoch] = useState(0);
  const [fontsReady, setFontsReady] = useState(false);
  const [following, setFollowing] = useState(true);
  const [state, dispatch] = useReducer(reduceReplay, undefined, initialState);
  const scrollRef = useRef(null), contentRef = useRef(null), frameRef = useRef(null), dialogRef = useRef(null);
  const followRef = useRef(true), anchorRef = useRef(null), touchRef = useRef(0), anchorPending = useRef(false);
  const stable = useRef(new Map()), metrics = useRef({ maxShift: 0, resizeCount: 0 });
  const data = datasets.find(x => x.id === sample);
  const openImage = useMemo(() => src => { setZoom(1); setViewer(src); }, []);
  const components = useMemo(() => ({
    img: props => <StableImage asset={data.assets[props.src]} name={props.src} scenario={scenario} openImage={openImage} />,
    // Keep text and table rendering inside Streamdown. Only resource loading is custom.
    table: ({ children }) => <div className="table-scroll"><table>{children}</table></div>,
    a: ({ children }) => <span>{children}</span>
  }), [data, scenario, openImage]);

  useEffect(() => { Promise.all([...document.fonts].map(font => font.load())).then(() => document.fonts.ready).then(() => setFontsReady(true)); }, []);
  useEffect(() => { if (viewer && dialogRef.current && !dialogRef.current.open) dialogRef.current.showModal(); }, [viewer]);
  useEffect(() => {
    if (state.phase !== 'running' || !fontsReady) return;
    const id = setInterval(() => dispatch({ type: 'tick', source: data.blocks, chunk }), 80);
    return () => clearInterval(id);
  }, [state.phase, data, chunk, fontsReady]);

  function captureAnchor() {
    const pane = scrollRef.current;
    if (!pane) return;
    const top = pane.getBoundingClientRect().top + 8;
    const target = [...pane.querySelectorAll('[data-block-id]')].find(el => el.getBoundingClientRect().bottom > top);
    if (target) {
      const rect = target.getBoundingClientRect();
      anchorRef.current = { id: target.dataset.blockId, ratio: Math.max(0, (top - rect.top) / rect.height), gap: Math.max(0, rect.top - top) };
    }
  }
  function restoreAnchor() {
    const pane = scrollRef.current, a = anchorRef.current;
    if (!pane || !a) return;
    const block = pane.querySelector(`[data-block-id="${a.id}"]`);
    if (!block) return;
    const top = block.getBoundingClientRect().top - (pane.getBoundingClientRect().top + 8);
    pane.scrollTop += top + a.ratio * block.getBoundingClientRect().height - a.gap;
  }
  function syncPosition() {
    const pane = scrollRef.current;
    if (!pane) return;
    if (followRef.current) pane.scrollTop = pane.scrollHeight;
    else if (anchorPending.current) { restoreAnchor(); anchorPending.current = false; }
    captureAnchor();
  }
  function markUserScroll() {
    followRef.current = false; setFollowing(false);
  }
  function onScroll() {
    captureAnchor();
  }
  function changeOrientation(value) {
    captureAnchor(); anchorPending.current = true; stable.current.clear(); setOrientation(value);
  }
  function reset() {
    dispatch({ type: 'reset' }); setEpoch(x => x + 1); stable.current.clear(); metrics.current.maxShift = 0;
    followRef.current = true; setFollowing(true); anchorRef.current = null; anchorPending.current = false;
  }
  useLayoutEffect(() => {
    syncPosition();
    for (const el of contentRef.current?.querySelectorAll('[data-done="true"]') || []) {
      const pos = el.offsetTop, old = stable.current.get(el.dataset.blockId);
      if (old !== undefined) metrics.current.maxShift = Math.max(metrics.current.maxShift, Math.abs(old - pos));
      stable.current.set(el.dataset.blockId, pos);
    }
    const counter = document.getElementById('stable-shift');
    if (counter) counter.textContent = metrics.current.maxShift.toFixed(1) + ' px';
  }, [state, orientation]);
  useEffect(() => {
    const observer = new ResizeObserver(() => {
      stable.current.clear(); metrics.current.resizeCount++;
      anchorPending.current = !followRef.current;
      requestAnimationFrame(syncPosition);
    });
    if (scrollRef.current) observer.observe(scrollRef.current);
    return () => observer.disconnect();
  }, []);
  useEffect(() => {
    window.__ocrPrototype = { state, source: data, scenario, metrics: metrics.current, following: followRef.current,
      reset, step: () => { dispatch({ type: 'play' }); dispatch({ type: 'tick', source: data.blocks, chunk: 99999 }); dispatch({ type: 'pause' }); },
      play: () => dispatch({ type: 'play' }), pause: () => dispatch({ type: 'pause' }) };
  });

  return <main className="workbench">
    <div className="intro"><span className="eyebrow">OCR / STREAMING PROTOTYPE</span><h1>真实文档，逐块呈现</h1><p>用 docprase 的已有识别结果验证流式排版与图片加载。此处回放 JSON，未运行 OCR，速度不代表模型性能。</p></div>
    <div className="controls">
      <label>真实样本<select aria-label="真实样本" value={sample} onChange={e => { reset(); setSample(e.target.value); }}>{datasets.map(d => <option key={d.id} value={d.id}>{d.id} · {d.title}</option>)}</select></label>
      <label>每次输出<select aria-label="每次输出" value={chunk} onChange={e => setChunk(Number(e.target.value))}><option value="3">3 字符 / 80ms</option><option value="8">8 字符 / 80ms</option><option value="48">48 字符 / 80ms</option></select></label>
      <div className="segmented" aria-label="屏幕方向"><button aria-pressed={orientation === 'portrait'} onClick={() => changeOrientation('portrait')}>竖屏</button><button aria-pressed={orientation === 'landscape'} onClick={() => changeOrientation('landscape')}>横屏</button></div>
      <label className="check"><input type="checkbox" checked={debug} onChange={e => { captureAnchor(); anchorPending.current = true; stable.current.clear(); setDebug(e.target.checked); }} />测试模式</label>
    </div>
    <div className="scenarios" role="group" aria-label="回放场景">{[['normal', '正常加载'], ['slow', '图片延迟 2.4 秒'], ['failure', '图片失败与重试']].map(([key, title]) => <button key={key} aria-pressed={scenario === key} onClick={() => { reset(); setScenario(key); }}>{title}</button>)}</div>
    <div className="device-stage"><div className={`device ${orientation} ${debug ? 'debug' : ''}`} ref={frameRef}>
      <header className="app-header"><div><span className="logo">▤</span><strong>识别结果</strong></div><span className="filename">{data.id} · 本地文档</span><button onClick={() => openImage(data.original)}>查看原图</button></header>
      <div className="statusbar"><span className="live-dot" /><span role="status">{!fontsReady ? '准备离线字体…' : labels[state.phase]} · {state.blocks.filter(b => b.done).length} / {data.blocks.length} 区域</span><span className="local">模拟回放</span></div>
      <div className="panes">
        <aside className="source-pane"><div className="pane-title">原始页面 <span>点击放大</span></div><button className="source-image-button" onClick={() => openImage(data.original)}><img src={data.original} alt="真实 OCR 输入页面" /></button><p>{data.provenance.status === 'partial' ? '原始 OCR 结果含待核对内容' : '原始 OCR 作业已完成'}</p></aside>
        <div className="reader-wrap"><div className="reader" ref={scrollRef} onScroll={onScroll}
          onWheel={e => { if (e.deltaY < 0) markUserScroll(); }}
          onTouchStart={e => { touchRef.current = e.touches[0].clientY; }} onTouchMove={e => { if (e.touches[0].clientY > touchRef.current + 3) markUserScroll(); }}
          onPointerDown={() => { captureAnchor(); }} onKeyDown={e => { if (['ArrowUp', 'PageUp', 'Home'].includes(e.key)) markUserScroll(); }} tabIndex={0} aria-label="实时 Markdown 文档">
          <article ref={contentRef} className="document-body" key={`${sample}-${epoch}`}>
            {state.blocks.length === 0 && <div className="empty"><span>▤</span><h2>准备好接收文档</h2><p>点击开始，查看文字、表格、公式与真实图片逐步出现。</p><small>图片按资源尺寸预留空间；表格与公式完整后排版。</small></div>}
            {state.blocks.map(b => <Block key={b.id} block={b} components={components} />)}
            {state.phase === 'complete' && <div className="document-end">— 回放完成 · 原始识别内容未经人工修正 —</div>}
          </article>
        </div>{!following && <button className="latest" onClick={() => { followRef.current = true; setFollowing(true); syncPosition(); }}>↓ 回到最新</button>}</div>
      </div>
      <footer className="app-actions"><button disabled={!fontsReady} className="primary" onClick={() => { if (state.phase === 'complete') reset(); dispatch({ type: state.phase === 'running' ? 'pause' : 'play' }); }}>{state.phase === 'running' ? '暂停输出' : state.phase === 'complete' ? '重新回放' : state.phase === 'paused' ? '继续输出' : '开始回放'}</button><button onClick={reset}>重置</button><span>表格 / 公式完整提交</span></footer>
      {debug && <div className="diagnostics"><div>Streamdown 2.7.0 + KaTeX · DocumentIR {data.provenance.schema_version}</div><div>已完成块顶部最大位移：<b id="stable-shift">{metrics.current.maxShift.toFixed(1)} px</b><small>不含当前块内重排及主动旋转</small></div><pre>{state.events.join('\n') || '等待区域事件…'}</pre></div>}
    </div></div>
    <details className="provenance"><summary>真实数据来源与验证边界</summary><p>{data.provenance.source_json}</p><p>JSON SHA-256：{data.provenance.source_json_sha256}</p><p>按 JSON 阅读顺序回放；图片引用映射至原始资源。正文保留原文，LaTeX 块加数学分隔符。公式样本原本含 partial 状态，回放不会修正识别错误。</p><p>模拟的是识别完成后的内容分片，不证明 C++ 引擎已经具备逐 token 回调。这里测浏览器端，安卓 WebView、JNI 和设备内存仍需后续验证。</p></details>
    <dialog ref={dialogRef} className="image-dialog" onCancel={() => setViewer(null)}><div className="viewer-tools"><span>原图 / 插图</span><button onClick={() => setZoom(x => Math.max(1, x - .5))}>缩小</button><button onClick={() => setZoom(x => Math.min(4, x + .5))}>放大</button><button onClick={() => { dialogRef.current.close(); setViewer(null); }}>关闭</button></div><div className="viewer-body">{viewer && <img src={viewer} style={{ width: `${zoom * 100}%`, maxWidth: 'none' }} alt="文档图片放大查看" />}</div></dialog>
  </main>;
}
createRoot(document.getElementById('app')).render(<App />);
