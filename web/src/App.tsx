import { useCallback, useEffect, useState } from 'react';
import { api, ApiError, brl, getSession, imageUrl, MAX_DEPOSIT, MIN_DEPOSIT, parseBRL, setSession } from './api';
import { AdminPage } from './Admin';
import type { CatalogItem, DepositQuote, LibraryItem, Order, Quote, Selection, ServerView, Session, WalletView } from './api';

// ---------- carrinho (localStorage, só ids; o preço vem sempre do servidor) ----------
const CART_KEY = 'coreplugins.cart';
function loadCart(): string[] {
  try { const v = JSON.parse(localStorage.getItem(CART_KEY) ?? '[]'); return Array.isArray(v) ? v.filter(x => typeof x === 'string') : []; } catch { return []; }
}
function saveCart(c: string[]) { try { localStorage.setItem(CART_KEY, JSON.stringify(c)); } catch { /* sem storage */ } }

function useHash(): string {
  const [h, setH] = useState(() => window.location.hash || '#/');
  useEffect(() => {
    const f = () => { setH(window.location.hash || '#/'); window.scrollTo(0, 0); };
    window.addEventListener('hashchange', f);
    return () => window.removeEventListener('hashchange', f);
  }, []);
  return h;
}
const go = (h: string) => { window.location.hash = h; };
const safeDecode = (t: string) => { try { return decodeURIComponent(t); } catch { return t; } };
const msg = (e: unknown) => (e instanceof ApiError ? e.message : 'Algo deu errado. Tente de novo.');


// ---------- visual: categoria colorida + ícone pixelado original ----------
const norm = (t: string) => t.normalize('NFD').replace(/[\u0300-\u036f]/g, '').toLowerCase();
/** diamond: otimização/core/segurança · redstone: PvP/combate · amethyst: rankup/economia/caixas · gold: minigames/vip */
function catColor(cat: string | null): string {
  const c = norm(cat ?? '');
  if (/(otimiz|core|segur|perform|admin|anti)/.test(c)) return 'diamond';
  if (/(pvp|faction|combate|guerra|clan)/.test(c)) return 'redstone';
  if (/(rank|econom|caixa|loja|shop|mina|maquina|spawner|armaz)/.test(c)) return 'amethyst';
  if (/(minigame|diversao|vip|evento|jogo)/.test(c)) return 'gold';
  return '';
}
const CAT_HEX: Record<string, string> = { diamond: '#38BDF8', redstone: '#EF4444', amethyst: '#A855F7', gold: '#F59E0B', '': '#10B981' };

function CatTag({ cat }: { cat: string | null }) {
  return cat ? <span className={'cat ' + catColor(cat)}>{cat}</span> : null;
}

/** Ícone 5x5 simétrico derivado do id (arte própria, nada copiado do jogo). */
function PixelIcon({ id, cat }: { id: string; cat: string | null }) {
  let h = 2166136261;
  for (let i = 0; i < id.length; i++) { h ^= id.charCodeAt(i); h = Math.imul(h, 16777619) >>> 0; }
  const cells: JSX.Element[] = [];
  for (let y = 0; y < 5; y++) {
    for (let x = 0; x < 3; x++) {
      h = Math.imul(h ^ (h >>> 15), 2246822519) >>> 0;
      if ((h >>> 7) % 100 < 58) {
        cells.push(<rect key={x + ',' + y} x={x} y={y} width={1} height={1} />);
        if (x < 2) cells.push(<rect key={(4 - x) + ',' + y} x={4 - x} y={y} width={1} height={1} />);
      }
    }
  }
  return (
    <svg className="picon" viewBox="-0.5 -0.5 6 6" aria-hidden="true" fill={CAT_HEX[catColor(cat)]} shapeRendering="crispEdges">{cells}</svg>
  );
}

function Footer() {
  return (
    <footer className="foot">
      <div className="wrap">
        <p><b>© 2026 CorePlugins</b></p>
        <p>Não afiliado à Mojang Studios.</p>
      </div>
    </footer>
  );
}

export function App() {
  const hash = useHash();
  const [session, setSess] = useState<Session | null>(getSession());
  const [cart, setCart] = useState<string[]>(loadCart());
  const [cartOpen, setCartOpen] = useState(false);
  const [authMode, setAuthMode] = useState<'login' | 'register' | null>(null);
  const [balance, setBalance] = useState<number | null>(null);
  const openCart = useCallback(() => setCartOpen(true), []);
  // pedido PIX do carrinho: fica no App para sobreviver ao fechar o modal e esvaziar o carrinho quando for aprovado
  const [cartOrder, setCartOrder] = useState<Order | null>(null);
  const [cartOrderItems, setCartOrderItems] = useState<CatalogItem[]>([]);
  const [paidOrder, setPaidOrder] = useState<Order | null>(null);
  const closeCart = useCallback(() => { setCartOpen(false); setCartOrder(o => (o && o.status !== 'PENDING' ? null : o)); }, []);
  const openAuth = useCallback((m: 'login' | 'register') => setAuthMode(m), []);
  const closeAuth = useCallback(() => setAuthMode(null), []);

  useEffect(() => {
    const f = () => setSess(null);
    window.addEventListener('coreplugins-logout', f);
    return () => window.removeEventListener('coreplugins-logout', f);
  }, []);

  // saldo do cabeçalho: atualiza ao entrar, a cada troca de página e ao abrir/fechar o carrinho
  useEffect(() => {
    if (!session) { setBalance(null); return; }
    let alive = true;
    api.me().then(m => { if (alive) setBalance(m.balanceCents); }).catch(() => { /* o 401 já derruba a sessão */ });
    return () => { alive = false; };
  }, [session, hash, cartOpen]);

  const updateCart = (c: string[]) => { setCart(c); saveCart(c); };

  // Enquanto pendente, pergunta de novo a cada 4 s (o servidor consulta o gateway; não depende de webhook).
  useEffect(() => {
    if (!cartOrder || cartOrder.status !== 'PENDING') return;
    const id = cartOrder.id;
    const t = setInterval(() => { api.order(id).then(o => setCartOrder(cur => (cur && cur.id === id ? o : cur))).catch(() => { /* tenta de novo no próximo ciclo */ }); }, 4000);
    return () => clearInterval(t);
  }, [cartOrder?.id, cartOrder?.status]);
  // pagamento aprovado: esvazia o carrinho (mesmo com o modal fechado), fecha o carrinho e abre o modal de confirmação
  useEffect(() => {
    if (cartOrder?.status === 'PAID' && cartOrder.kind === 'PURCHASE') {
      updateCart([]); setCartOpen(false); setPaidOrder(cartOrder); setCartOrder(null);
    }
    /* eslint-disable-next-line */
  }, [cartOrder?.status]);
  const login = (s: Session) => { setSession(s); setSess(s); };
  const logout = () => { setCartOrder(null); setPaidOrder(null); api.logout().catch(() => { /* sem rede: a sessão local é apagada mesmo assim */ }); setSession(null); setSess(null); go('#/'); };

  const path = hash.replace(/^#/, '');

  // link antigo #/carrinho: abre o modal e volta para a home
  useEffect(() => { if (path === '/carrinho') { setCartOpen(true); go('#/'); } }, [path]);
  // links antigos #/entrar e #/cadastro: abrem o modal e voltam para a home
  useEffect(() => { if (path === '/entrar' || path === '/cadastro') { setAuthMode(path === '/entrar' ? 'login' : 'register'); go('#/'); } }, [path]);

  let page;
  if (path.startsWith('/pedido/')) page = session ? <OrderPage id={Number(path.slice(8))} clearCart={() => updateCart([])} /> : <NeedLogin openAuth={openAuth} />;
  else if (path.startsWith('/plugin/')) page = <PluginPage id={safeDecode(path.slice(8))} cart={cart} setCart={updateCart} session={session} openCart={openCart} />;
  else if (path === '/saldo') page = session ? <WalletPage /> : <NeedLogin openAuth={openAuth} />;
  else if (path === '/admin') page = <AdminPage />;
  else if (path === '/area') page = session ? <AreaPage /> : <NeedLogin openAuth={openAuth} />;
  else page = <HomePage cart={cart} setCart={updateCart} session={session} openCart={openCart} openAuth={openAuth} />;

  const on = (p: string) => (path === p ? 'on' : undefined);
  return (
    <>
      <header className="top">
        <div className="wrap">
          <a className="brand" href="#/"><img className="brand-ico" src="/art/portal.svg" alt="" />Core<span>Plugins</span></a>
          <nav className="menu" aria-label="Principal">
            <a href="#/" className={on('/')}>Plugins</a>
          </nav>
          <nav className="menu" aria-label="Conta e carrinho">
            <button type="button" className="cart-btn" onClick={openCart} aria-label={`Abrir carrinho${cart.length ? ` (${cart.length} ${cart.length === 1 ? 'item' : 'itens'})` : ''}`}>
              <i className="fa-solid fa-cart-shopping" aria-hidden="true" />{cart.length > 0 && <span className="badge">{cart.length}</span>}
            </button>
            {session ? (
              <>
                <a href="#/saldo" className={on('/saldo')}><i className="fa-solid fa-wallet" aria-hidden="true" />{balance !== null ? ' ' + brl(balance) : ''}</a>
                <a href="#/area" className={on('/area')}><i className="fa-solid fa-user" aria-hidden="true" /> Área do Cliente</a>
                <a href="#/" onClick={e => { e.preventDefault(); logout(); }}><i className="fa-solid fa-right-from-bracket" aria-hidden="true" /> Sair</a>
              </>
            ) : (
              <>
                <a href="#/entrar" className="login-btn" onClick={e => { e.preventDefault(); openAuth('login'); }}><i className="fa-solid fa-right-to-bracket" aria-hidden="true" /> Entrar</a>
              </>
            )}
          </nav>
        </div>
      </header>
      <main className="wrap">{page}</main>
      <Footer />
      {cartOpen && <CartModal cart={cart} setCart={updateCart} session={session} onClose={closeCart} openAuth={openAuth} order={cartOrder} setOrder={setCartOrder} orderItems={cartOrderItems} setOrderItems={setCartOrderItems} />}
      {paidOrder && <PaidModal order={paidOrder} onClose={() => setPaidOrder(null)} />}
      {authMode && <AuthModal mode={authMode} setMode={setAuthMode} onClose={closeAuth} onDone={s => { login(s); closeAuth(); if (cart.length) setCartOpen(true); }} />}
    </>
  );
}

function NeedLogin({ openAuth }: { openAuth: (m: 'login' | 'register') => void }) {
  return (
    <div className="stack">
      <h2>Entre na sua conta</h2>
      <p className="muted">Você precisa estar logado para ver esta página.</p>
      <div className="row"><button type="button" className="btn primary" onClick={() => openAuth('login')}>Entrar</button><button type="button" className="btn" onClick={() => openAuth('register')}>Criar conta</button></div>
    </div>
  );
}

// ---------- vitrine ----------
function HomePage({ cart, setCart, session, openCart, openAuth }: { cart: string[]; setCart: (c: string[]) => void; session: Session | null; openCart: () => void; openAuth: (m: 'login' | 'register') => void }) {
  const [items, setItems] = useState<CatalogItem[] | null>(null);
  const [owned, setOwned] = useState<Set<string>>(new Set());
  const [err, setErr] = useState('');
  const [filter, setFilter] = useState('');

  useEffect(() => { api.catalog().then(setItems).catch(e => setErr(msg(e))); }, []);
  useEffect(() => {
    if (session) api.library().then(l => setOwned(new Set(l.map(i => i.id)))).catch(() => undefined);
    else setOwned(new Set());
  }, [session]);

  const cats = Array.from(new Set((items ?? []).map(p => p.category).filter((c): c is string => !!c)));
  const shown = (items ?? []).filter(p => !filter || p.category === filter);

  return (
    <div className="stack">
      <section className="sky" aria-label="Apresentação">
        <div className="sky-art" aria-hidden="true">
          <img className="sun" src="/art/sun.svg" alt="" />
          <img className="cloud c1" src="/art/cloud.svg" alt="" />
          <img className="cloud c2" src="/art/cloud2.svg" alt="" />
          <img className="cloud c3" src="/art/cloud.svg" alt="" />
          <img className="isle small" src="/art/island-small.svg" alt="" />
          <img className="isle big" src="/art/island-big.svg" alt="" />
        </div>
        <div className="sky-copy">
          <h1>Plugins para seu servidor de <span>minecraft</span></h1>
          <p className="lead">Plugins para criar um servidor único e personalizado.</p>
          <div className="row"><a className="btn primary" href="#catalogo" onClick={e => { e.preventDefault(); document.getElementById('catalogo')?.scrollIntoView({ behavior: 'smooth' }); }}>Ver plugins</a></div>
        </div>
      </section>

      <ul className="perks" aria-label="Diferenciais">
        <li><b>Entrega automática</b><span>Pagou, liberou. Sem esperar ninguém.</span></li>
        <li><b>Licença por IP</b><span>Seu plugin fica preso ao seu servidor.</span></li>
        <li><b>Atualizações</b><span>Escolha a versão ou acompanhe a mais recente.</span></li>
        <li><b>Pagamento via PIX</b><span>Aprovação na hora, direto no site.</span></li>
      </ul>

      <section className="how" aria-label="Como funciona">
        <h2>Como funciona</h2>
        <ol className="steps">
          <li><h3>Escolha e pague</h3><p className="muted">Adicione os plugins ao carrinho e pague com PIX.</p></li>
          <li><h3>Cadastre o servidor</h3><p className="muted">Na Área do Cliente, informe o IP:porta onde ele roda.</p></li>
          <li><h3>Marque os plugins</h3><p className="muted">Selecione quais plugins cada servidor vai usar e clique em Atualizar.</p></li>
          <li><h3>Instale o corePlugins.jar</h3><p className="muted">Um único jar na pasta plugins. Ele cuida do resto.</p></li>
        </ol>
      </section>

      <h2 id="catalogo" className="section-title">Plugins disponíveis</h2>
      {items !== null && cats.length > 1 && (
        <div className="chips" role="group" aria-label="Filtrar por categoria">
          <button className={'chip' + (filter === '' ? ' on' : '')} onClick={() => setFilter('')}>Todos</button>
          {cats.map(c => <button key={c} className={'chip' + (filter === c ? ' on' : '')} onClick={() => setFilter(c)}>{c}</button>)}
        </div>
      )}
      {err && <p className="notice bad" role="alert">{err}</p>}
      {items === null && !err && <p className="muted">Carregando…</p>}
      {items !== null && items.length === 0 && <p className="muted">Nenhum plugin à venda ainda.</p>}
      <div className="grid">
        {shown.map(p => {
          const inCart = cart.includes(p.id);
          const has = owned.has(p.id);
          return (
            <article className="card" key={p.id}>
              {p.images.length > 0 && (
                <a className="cover" href={'#/plugin/' + encodeURIComponent(p.id)} aria-label={'Ver detalhes de ' + p.name}>
                  <img src={imageUrl(p.id, p.images[0])} alt="" loading="lazy" decoding="async" />
                </a>
              )}
              <div className="card-head">
                <div><h3><a className="card-title" href={'#/plugin/' + encodeURIComponent(p.id)}>{p.name}</a></h3>
                  <p className="desc">{p.category}</p></div>
              </div>
              <div className="row between">
                <span className="price">{brl(p.priceCents)}</span>
                {has ? <span className="status PAID"><i className="fa-solid fa-check" aria-hidden="true" /> Você já tem</span> : (
                  <button className={'btn ' + (inCart ? '' : 'primary')} onClick={() => setCart(inCart ? cart.filter(x => x !== p.id) : [...cart, p.id])}>
                    {inCart ? <><i className="fa-solid fa-trash" aria-hidden="true" /> Remover</> : <><i className="fa-solid fa-cart-plus" aria-hidden="true" /> Adicionar</>}
                  </button>
                )}
              </div>
            </article>
          );
        })}
      </div>

      <section className="cta" aria-label="Chamada final">
        <img className="cta-isle" src="/art/island-house.svg" alt="" />
        <div>
          <h2>Pronto para decolar?</h2>
          <p className="muted">Crie sua conta, compre o primeiro plugin e deixe o servidor rodando em minutos.</p>
          <div className="row" style={{ marginTop: 14 }}>{!session && <button type="button" className="btn primary" onClick={() => openAuth('register')}><i className="fa-solid fa-user-plus" aria-hidden="true" /> Criar conta grátis</button>}<button type="button" className="btn" onClick={openCart}><i className="fa-solid fa-cart-shopping" aria-hidden="true" /> Ver carrinho</button></div>
        </div>
      </section>
    </div>
  );
}

// ---------- página do plugin: galeria de imagens + vídeo de showcase ----------
const YT_RE = /^[A-Za-z0-9_-]{11}$/;

/** Vídeo do YouTube só carrega quando a pessoa clica (rápido e sem cookies do YouTube antes disso). */
function YouTubeEmbed({ id, title }: { id: string; title: string }) {
  const [on, setOn] = useState(false);
  if (!YT_RE.test(id)) return null;
  if (on) {
    return (
      <iframe className="yt" src={'https://www.youtube-nocookie.com/embed/' + id + '?autoplay=1&rel=0'} title={'Vídeo de showcase: ' + title}
        allow="accelerometer; autoplay; encrypted-media; gyroscope; picture-in-picture; fullscreen" allowFullScreen referrerPolicy="strict-origin-when-cross-origin" />
    );
  }
  return (
    <button type="button" className="yt yt-facade" onClick={() => setOn(true)} aria-label={'Assistir ao vídeo de ' + title}
      style={{ backgroundImage: 'url(https://i.ytimg.com/vi/' + id + '/hqdefault.jpg)' }}>
      <span className="yt-play"><i className="fa-solid fa-play" aria-hidden="true" /></span>
    </button>
  );
}

function Gallery({ productId, name, images }: { productId: string; name: string; images: number[] }) {
  const [sel, setSel] = useState(0);
  useEffect(() => { setSel(0); }, [productId]);
  const i = Math.min(sel, images.length - 1);
  const go1 = (d: number) => setSel((i + d + images.length) % images.length);
  return (
    <div className="gallery">
      <div className="gallery-main">
        <img src={imageUrl(productId, images[i])} alt={'Imagem ' + (i + 1) + ' de ' + images.length + ' do plugin ' + name} decoding="async" />
        {images.length > 1 && (
          <>
            <button type="button" className="gallery-nav prev" onClick={() => go1(-1)} aria-label="Imagem anterior"><i className="fa-solid fa-chevron-left" aria-hidden="true" /></button>
            <button type="button" className="gallery-nav next" onClick={() => go1(1)} aria-label="Próxima imagem"><i className="fa-solid fa-chevron-right" aria-hidden="true" /></button>
          </>
        )}
      </div>
      {images.length > 1 && (
        <div className="thumbs" role="group" aria-label="Imagens do plugin">
          {images.map((id, n) => (
            <button type="button" key={id} className="thumb" aria-pressed={n === i} aria-label={'Ver imagem ' + (n + 1) + ' de ' + images.length} onClick={() => setSel(n)}>
              <img src={imageUrl(productId, id)} alt="" loading="lazy" decoding="async" />
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

function PluginPage({ id, cart, setCart, session, openCart }: { id: string; cart: string[]; setCart: (c: string[]) => void; session: Session | null; openCart: () => void }) {
  const [items, setItems] = useState<CatalogItem[] | null>(null);
  const [owned, setOwned] = useState(false);
  const [err, setErr] = useState('');

  useEffect(() => { api.catalog().then(setItems).catch(e => setErr(msg(e))); }, []);
  useEffect(() => {
    if (session) api.library().then(l => setOwned(l.some(i => i.id === id))).catch(() => undefined);
    else setOwned(false);
  }, [session, id]);

  const p = items?.find(x => x.id === id);
  useEffect(() => {
    if (!p) return;
    document.title = p.name + ' — CorePlugins';
    return () => { document.title = 'CorePlugins — plugins para Minecraft'; };
  }, [p]);

  const inCart = !!p && cart.includes(p.id);
  return (
    <div className="stack">
      <p><a href="#/"><i className="fa-solid fa-arrow-left" aria-hidden="true" /> Todos os plugins</a></p>
      {err && <p className="notice bad" role="alert">{err}</p>}
      {items === null && !err && <p className="muted">Carregando…</p>}
      {items !== null && !p && <p className="notice" role="status">Plugin não encontrado. <a href="#/">Ver o catálogo</a></p>}
      {p && (
        <div className="plugin-page">
          <div className="plugin-media">
            {p.images.length > 0 && <Gallery productId={p.id} name={p.name} images={p.images} />}
            {p.youtubeId && <YouTubeEmbed id={p.youtubeId} title={p.name} />}
            {p.images.length === 0 && !p.youtubeId && <div className="card"><div className="card-head"><PixelIcon id={p.id} cat={p.category} /><p className="muted">Este plugin ainda não tem imagens nem vídeo.</p></div></div>}
          </div>
          <aside className="card plugin-buy" aria-label="Comprar">
            <CatTag cat={p.category} />
            <h1>{p.name}</h1>
            {p.description && <p className="desc">{p.description}</p>}
            {p.command && <p><code className="cmd">{p.command}</code></p>}
            <p className="muted">versão {p.version}</p>
            <div className="row between">
              <span className="price">{brl(p.priceCents)}</span>
              {owned ? <span className="status PAID"><i className="fa-solid fa-check" aria-hidden="true" /> Você já tem</span> : (
                <button className={'btn ' + (inCart ? '' : 'primary')} onClick={() => setCart(inCart ? cart.filter(x => x !== p.id) : [...cart, p.id])}>
                  {inCart ? <><i className="fa-solid fa-trash" aria-hidden="true" /> Remover</> : <><i className="fa-solid fa-cart-plus" aria-hidden="true" /> Adicionar</>}
                </button>
              )}
            </div>
            {inCart && !owned && <button type="button" className="btn primary" onClick={openCart}><i className="fa-solid fa-cart-shopping" aria-hidden="true" /> Ver carrinho</button>}
          </aside>
        </div>
      )}
    </div>
  );
}

// ---------- conta ----------
function AuthModal({ mode, setMode, onClose, onDone }: { mode: 'login' | 'register'; setMode: (m: 'login' | 'register') => void; onClose: () => void; onDone: (s: Session) => void }) {
  const [name, setName] = useState('');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);
  const reg = mode === 'register';

  // Esc fecha, rolagem da página travada enquanto aberto
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = prev; };
  }, [onClose]);
  useEffect(() => { setErr(''); }, [mode]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(''); setBusy(true);
    try { onDone(reg ? await api.register(name, email, password) : await api.login(email, password)); }
    catch (x) { setErr(msg(x)); setBusy(false); }
  };

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal" role="dialog" aria-modal="true" aria-labelledby="auth-title">
        {reg ? (
          <>
            <img className="deco deco-tl" src="/art/island-house.svg" alt="" aria-hidden="true" />
            <img className="deco deco-tr" src="/art/gem.svg" alt="" aria-hidden="true" />
            <img className="deco deco-br" src="/art/chest.svg" alt="" aria-hidden="true" />
          </>
        ) : (
          <>
            <img className="deco deco-tl" src="/art/island-mid.svg" alt="" aria-hidden="true" />
            <img className="deco deco-tr" src="/art/cloud.svg" alt="" aria-hidden="true" />
            <img className="deco deco-br" src="/art/torch.svg" alt="" aria-hidden="true" />
          </>
        )}
        <div className="modal-head">
          <h2 id="auth-title"> {reg ? 'Criar conta' : 'Entrar'}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Fechar"><i className="fa-solid fa-xmark" aria-hidden="true" /></button>
        </div>
        <form onSubmit={submit}>
          <div className="modal-body">
            {reg && <label className="f">Nome<input type="text" value={name} onChange={e => setName(e.target.value)} autoComplete="name" autoFocus required /></label>}
            <label className="f">E-mail<input type="email" value={email} onChange={e => setEmail(e.target.value)} autoComplete="email" autoFocus={!reg} required /></label>
            <label className="f">Senha{reg && <span className="muted"> (mínimo 8 caracteres)</span>}
              <input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete={reg ? 'new-password' : 'current-password'} minLength={reg ? 8 : undefined} required />
            </label>
            {err && <p className="notice bad" role="alert">{err}</p>}
          </div>
          <div className="modal-foot">
            <button className="btn primary" disabled={busy}>{busy ? 'Aguarde…' : reg ? 'Criar conta' : 'Entrar'}</button>
            <p className="muted" style={{ textAlign: 'center' }}>{reg
              ? <>Já tem conta? <a href="#/entrar" onClick={e => { e.preventDefault(); setMode('login'); }}>Entrar</a></>
              : <>Primeira vez? <a href="#/cadastro" onClick={e => { e.preventDefault(); setMode('register'); }}>Criar conta</a></>}</p>
          </div>
        </form>
      </div>
    </div>
  );
}
function CartModal({ cart, setCart, session, onClose, openAuth, order, setOrder, orderItems, setOrderItems }: { cart: string[]; setCart: (c: string[]) => void; session: Session | null; onClose: () => void; openAuth: (m: 'login' | 'register') => void; order: Order | null; setOrder: (o: Order | null) => void; orderItems: CatalogItem[]; setOrderItems: (i: CatalogItem[]) => void }) {
  const [catalog, setCatalog] = useState<CatalogItem[] | null>(null);
  const [quote, setQuote] = useState<Quote | null>(null);
  const [couponInput, setCouponInput] = useState('');
  const [coupon, setCoupon] = useState('');
  const [couponErr, setCouponErr] = useState('');
  const [err, setErr] = useState('');
  const [wantBalance, setWantBalance] = useState(false);
  const [busy, setBusy] = useState(false);
  // pedido criado (vem do App): o PIX (QR + copia e cola) aparece no side-box do modal
  useEffect(() => { api.catalog().then(setCatalog).catch(e => setErr(msg(e))); }, []);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = prev; };
  }, [onClose]);

  useEffect(() => {
    if (!session || cart.length === 0) { setQuote(null); return; }
    let alive = true;
    api.quote(cart, coupon || null).then(q => { if (alive) { setQuote(q); setErr(''); } }).catch(e => {
      if (!alive) return;
      if (coupon) { setCouponErr(msg(e)); setCoupon(''); } else setErr(msg(e));
    });
    return () => { alive = false; };
  }, [cart, coupon, session]);

  const cartItems = (catalog ?? []).filter(p => cart.includes(p.id));
  // com pedido criado, a coluna de itens continua mostrando o que foi comprado (mesmo depois de o carrinho esvaziar)
  const items = order && order.status === 'PENDING' ? orderItems : cartItems;
  // pagamento aprovado: o carrinho já foi esvaziado, então a coluna de itens mostra o carrinho vazio
  const paid = order?.status === 'PAID' || order?.status === 'CREDITED';
  const localTotal = items.reduce((s, p) => s + p.priceCents, 0);
  const subtotal = quote?.subtotalCents ?? localTotal;
  const discount = quote?.discountCents ?? 0;
  const total = quote?.totalCents ?? localTotal;
  const balance = quote?.balanceCents ?? 0;
  const canBalance = !!session && total > 0 && balance > 0;
  const useBalance = wantBalance && canBalance;
  const balanceUsed = useBalance ? Math.min(balance, total) : 0;
  const pixPart = total - balanceUsed;
  const fullBalance = useBalance && pixPart === 0;

  const applyCoupon = () => { const c = couponInput.trim(); if (!c) return; setCouponErr(''); setCoupon(c); };
  const clearCoupon = () => { setCoupon(''); setCouponInput(''); setCouponErr(''); };

  const pay = async () => {
    if (!session) { onClose(); openAuth('login'); return; }
    setErr(''); setBusy(true);
    try { const o = await api.checkout(items.map(p => p.id), coupon || null, useBalance); setOrderItems(items); setOrder(o); }
    catch (x) { setErr(msg(x)); }
    finally { setBusy(false); }
  };

  const empty = catalog !== null && items.length === 0 && !order;

  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className={'modal cart-modal' + (empty && !order ? ' is-empty' : '')} role="dialog" aria-modal="true" aria-labelledby="cart-title">
        <div className="modal-head">
          <div className="cart-headline">
            <span className="cart-headico"><i className="fa-solid fa-cart-shopping" aria-hidden="true" /></span>
            <div>
              <h2 id="cart-title">Carrinho</h2>
            </div>
          </div>
          <button className="icon-btn" onClick={onClose} aria-label="Fechar carrinho" autoFocus><i className="fa-solid fa-xmark" aria-hidden="true" /></button>
        </div>

        <div className="modal-body">
          {err && <p className="notice bad" role="alert"><i className="fa-solid fa-circle-exclamation" aria-hidden="true" /> {err}</p>}
          {catalog === null && !err && <p className="muted"><i className="fa-solid fa-spinner fa-spin" aria-hidden="true" /> Carregando…</p>}


          {empty && !order && (
            <div className="cart-empty">
              <i className="fa-solid fa-cart-shopping" aria-hidden="true" />
              <p className="muted">Seu carrinho está vazio.</p>
              <button className="btn primary" onClick={() => { onClose(); go('#/'); }}><i className="fa-solid fa-puzzle-piece" aria-hidden="true" /> Ver plugins</button>
            </div>
          )}

          {(items.length > 0 || order) && (
            <div className="cart-layout">
              {/* ---------- coluna esquerda: itens ---------- */}
              <div className="cart-main">
                {paid ? (
                  <div className="cart-empty">
                    <i className="fa-solid fa-cart-shopping" aria-hidden="true" />
                    <p className="muted">Seu carrinho está vazio.</p>
                  </div>
                ) : (<>
                  <div className="cart-bar">
                    <strong>{items.length === 0 ? 'Carrinho vazio' : items.length + (items.length === 1 ? ' item' : ' itens') + ' no carrinho'}</strong>
                    <button type="button" className="link-danger" disabled={!!order} onClick={() => setCart([])}>Limpar tudo</button>
                  </div>

                  <header className="cart-group-head">
                    <h3>CorePlugins</h3>
                    <span className="tag">Plugins oficiais</span>
                  </header>
                  <section className="cart-group" aria-label="Itens do carrinho">
                    <ul className="cart-list">
                      {items.map(p => (
                        <li key={p.id}>
                          <span className="cart-thumb">
                            {p.images.length > 0
                              ? <img src={imageUrl(p.id, p.images[0])} alt="" loading="lazy" decoding="async" />
                              : <PixelIcon id={p.id} cat={p.category} />}
                          </span>
                          <div className="cart-info">
                            <span className="cart-name">{p.name}</span>
                            {p.category && <span className="muted cart-cat">Plugin - {p.category}</span>}
                          </div>
                          <span className="price">{brl(p.priceCents)}</span>
                          <button className="icon-btn danger" disabled={!!order} onClick={() => setCart(cart.filter(x => x !== p.id))} aria-label={'Remover ' + p.name} title="Remover"><i className="fa-solid fa-trash" aria-hidden="true" /></button>
                        </li>
                      ))}
                    </ul>
                  </section>
                </>)}
              </div>

              {/* ---------- coluna direita: pagamento ---------- */}
              <aside className="cart-side" aria-label="Pagamento">
                {order ? (
                  <PixSide order={order} setOrder={setOrder} onClose={onClose} onBack={() => setOrder(null)} />
                ) : (<>
                  {total > 0 && (
                    <section className="side-box">
                      <h3>Forma de pagamento</h3>



                      <div className="pay-grid">
                        <button type="button" className={'pay-opt balance' + (useBalance ? ' on' : '')} role="switch" aria-checked={useBalance}
                          disabled={!canBalance} onClick={() => setWantBalance(v => !v)}>
                          <span><b>Usar saldo</b><small>{session ? brl(balance) + (balance > 0 && balance < total ? ' · o resto vai no PIX' : '') : 'Entre para usar'}</small></span>
                          <span className="radio" aria-hidden="true" />
                        </button>
                        <button type="button" className={'pay-opt' + (pixPart > 0 ? ' on' : '')} disabled={fullBalance || total === 0} aria-pressed={pixPart > 0} onClick={() => setWantBalance(false)}>
                          <i className="fa-brands fa-pix" aria-hidden="true" /> PIX{balanceUsed > 0 && pixPart > 0 ? ' (restante)' : ''}
                        </button>
                      </div>

                      {total === 0 && <p className="muted small-note">Total zerado: não é preciso pagar, é só finalizar o pedido.</p>}
                    </section>
                  )}

                  {session && (
                    <section className="side-box">
                      <h3>Cupom de desconto</h3>
                      {quote?.couponCode ? (
                        <div className="coupon-applied"><span><i className="fa-solid fa-ticket" aria-hidden="true" /> Cupom <b>{quote.couponCode}</b> aplicado</span>
                          <button className="icon-btn" onClick={clearCoupon} aria-label="Remover cupom" title="Remover cupom"><i className="fa-solid fa-xmark" aria-hidden="true" /></button></div>
                      ) : (
                        <div className="row nowrap">
                          <input type="text" value={couponInput} onChange={e => setCouponInput(e.target.value)} onKeyDown={e => { if (e.key === 'Enter') applyCoupon(); }} placeholder="Digite seu cupom" aria-label="Cupom de desconto" maxLength={32} autoCapitalize="characters" style={{ flex: 1, minWidth: 0 }} />
                          <button className="btn small" onClick={applyCoupon} disabled={!couponInput.trim()}>Aplicar</button>
                        </div>
                      )}
                      {couponErr && <p className="notice bad" role="alert">{couponErr}</p>}
                    </section>
                  )}

                  <section className="side-box summary">
                    <h3>Resumo</h3>
                    <div className="row between"><span className="muted">Subtotal loja</span><span>{brl(subtotal)}</span></div>
                    {discount > 0 && <div className="row between"><span className="muted">Desconto ({quote?.couponCode})</span><span className="disc">− {brl(discount)}</span></div>}
                    <div className="row between"><span className="muted">Total</span><span>{brl(total)}</span></div>
                    {balanceUsed > 0 && <div className="row between"><span className="muted">Pago com saldo</span><span className="disc">− {brl(balanceUsed)}</span></div>}
                    <div className="row between total-line"><strong>{balanceUsed > 0 ? 'Total no PIX' : 'Total a pagar'}</strong><span className="price big">{brl(pixPart)}</span></div>
                  </section>

                  <button className="btn primary block" onClick={pay} disabled={busy}>
                    {busy
                      ? <><i className="fa-solid fa-spinner fa-spin" aria-hidden="true" /> Finalizando…</>
                      : !session ? <><i className="fa-solid fa-right-to-bracket" aria-hidden="true" /> Entrar para pagar</>
                        : total === 0 ? <><i className="fa-solid fa-check" aria-hidden="true" /> Finalizar pedido</>
                          : <>Finalizar compra</>}
                  </button>
                  <button type="button" className="link-continue" onClick={() => { onClose(); go('#/'); }}>Continuar comprando</button>
                </>)}
              </aside>
            </div>
          )}
        </div>
      </div>
    </div>
  );
}

// ---------- modal de pagamento aprovado ----------
function PaidModal({ order, onClose }: { order: Order; onClose: () => void }) {
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => { if (e.key === 'Escape') onClose(); };
    window.addEventListener('keydown', onKey);
    const prev = document.body.style.overflow;
    document.body.style.overflow = 'hidden';
    return () => { window.removeEventListener('keydown', onKey); document.body.style.overflow = prev; };
  }, [onClose]);
  return (
    <div className="modal-backdrop" onMouseDown={e => { if (e.target === e.currentTarget) onClose(); }}>
      <div className="modal paid-modal" role="dialog" aria-modal="true" aria-labelledby="paid-title">
        <img className="deco deco-br" src="/art/island-big.svg" alt="" aria-hidden="true" />
        <div className="modal-head">
          <h2 id="paid-title"><i className="fa-solid fa-circle-check" aria-hidden="true" /> {order.paidWithBalance ? 'Compra concluída!' : 'Pagamento confirmado!'}</h2>
          <button type="button" className="icon-btn" onClick={onClose} aria-label="Fechar" autoFocus><i className="fa-solid fa-xmark" aria-hidden="true" /></button>
        </div>
        <div className="modal-body">
          <p>{order.paidWithBalance ? 'Sua compra foi paga com o saldo.' : 'Recebemos o seu PIX.'} Seus plugins já estão na sua conta.</p>
          <p className="muted">Pedido #{order.id}</p>
        </div>
        <div className="modal-foot">
          <a className="btn primary" href="#/area" onClick={onClose}>Ir para a Área do Cliente</a>
          <button type="button" className="link-continue" onClick={() => { onClose(); go('#/'); }}>Continuar comprando</button>
        </div>
      </div>
    </div>
  );
}

// ---------- side-box do PIX (dentro do modal do carrinho) ----------
function PixSide({ order, setOrder, onClose, onBack }: { order: Order; setOrder: (o: Order) => void; onClose: () => void; onBack: () => void }) {
  const [copied, setCopied] = useState(false);
  const [err, setErr] = useState('');

  const copy = async () => {
    try { await navigator.clipboard.writeText(order.pixCode ?? ''); setCopied(true); setTimeout(() => setCopied(false), 2000); } catch { /* selecione manualmente */ }
  };
  const simulate = async () => { try { setOrder(await api.simulatePay(order.id)); } catch (x) { setErr(msg(x)); } };

  if (order.status === 'PENDING' && (order.pixCode || order.pixQrBase64)) {
    return (
      <section className="side-box pix-side">
        <h3>Pedido #{order.id}</h3>
        {order.pixQrBase64 && <img className="qr" alt="QR Code PIX" src={'data:image/png;base64,' + order.pixQrBase64} />}
        {order.pixCode && <div className="pix" tabIndex={0}>{order.pixCode}</div>}
        <button type="button" className="btn primary block" onClick={copy}><i className={copied ? 'fa-solid fa-check' : 'fa-regular fa-copy'} aria-hidden="true" /> {copied ? 'Copiado!' : 'Copiar código'}</button>
        {order.simulated && <button type="button" className="btn block" onClick={simulate}>Simular pagamento (modo teste)</button>}
        {err && <p className="notice bad" role="alert">{err}</p>}
      </section>
    );
  }
  // sem PIX para mostrar: convertido em saldo, expirado ou cancelado (o pago abre o PaidModal)
  return (
    <section className="side-box pix-side">
      <h3>Pedido #{order.id}</h3>
      {order.status === 'CREDITED' && (
        <><p><strong>Valor convertido em saldo.</strong></p>
          <a className="btn primary block" href="#/saldo" onClick={onClose}>Ver meu saldo</a></>
      )}
      {(order.status === 'EXPIRED' || order.status === 'CANCELLED') && (
        <><p className="muted">Este pedido não está mais ativo.</p>
          <button type="button" className="btn primary block" onClick={onBack}>Voltar ao carrinho</button></>
      )}
      {order.status === 'PENDING' && <p className="muted">Pedido registrado.</p>}
    </section>
  );
}

// ---------- pedido / PIX ----------
function OrderPage({ id, clearCart, initial, embedded }: { id: number; clearCart: () => void; initial?: Order; embedded?: boolean }) {
  const [order, setOrder] = useState<Order | null>(initial ?? null);
  const [err, setErr] = useState('');
  const [copied, setCopied] = useState(false);

  const load = useCallback(() => api.order(id).then(setOrder).catch(e => setErr(msg(e))), [id]);
  useEffect(() => { load(); }, [load]);
  // Enquanto pendente, pergunta de novo a cada 4 s (o servidor consulta o gateway; não depende de webhook).
  useEffect(() => {
    if (order?.status !== 'PENDING') return;
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, [order?.status, load]);
  // só a compra esvazia o carrinho; depósito não mexe nele
  useEffect(() => { if (order?.status === 'PAID' && order.kind === 'PURCHASE') clearCart(); /* eslint-disable-next-line */ }, [order?.status]);

  if (err) return <p className="notice bad" role="alert">{err}</p>;
  if (!order) return <p className="muted">Carregando…</p>;

  const deposit = order.kind === 'DEPOSIT';
  const copy = async () => {
    try { await navigator.clipboard.writeText(order.pixCode ?? ''); setCopied(true); setTimeout(() => setCopied(false), 2000); } catch { /* selecione manualmente */ }
  };
  const simulate = async () => { try { setOrder(await api.simulatePay(order.id)); } catch (x) { setErr(msg(x)); } };

  return (
    <div className="stack">
      <div className="row between">{embedded ? <h3>{deposit ? 'Depósito' : 'Pedido'} #{order.id}</h3> : <h2>{deposit ? 'Depósito' : 'Pedido'} #{order.id}</h2>}<span className={'status ' + order.status}>{label(order.status)}</span></div>
      <div className="scroll"><table><tbody>
        {deposit ? (
          <>
            <tr><td>Valor do depósito</td><td className="price">{brl(order.totalCents)}</td></tr>
            {order.bonusCents > 0 && <tr><td>Bônus{order.couponCode ? ' (cupom ' + order.couponCode + ')' : ''}</td><td className="price disc">+ {brl(order.bonusCents)}</td></tr>}
            <tr><td><strong>Saldo a receber</strong></td><td className="price">{brl(order.totalCents + order.bonusCents)}</td></tr>
          </>
        ) : (
          <>
            {order.items.map(i => <tr key={i.productId}><td>{i.name}</td><td className="price">{brl(i.priceCents)}</td></tr>)}
            {order.discountCents > 0 && (
              <>
                <tr><td className="muted">Subtotal</td><td className="price">{brl(order.subtotalCents)}</td></tr>
                <tr><td>Desconto{order.couponCode ? ' (cupom ' + order.couponCode + ')' : ''}</td><td className="price disc">− {brl(order.discountCents)}</td></tr>
              </>
            )}
            {order.balanceCents > 0 && !order.paidWithBalance && <tr><td>Pago com saldo</td><td className="price disc">− {brl(order.balanceCents)}</td></tr>}
            <tr><td><strong>{order.balanceCents > 0 && !order.paidWithBalance ? 'Total no PIX' : 'Total' + (order.paidWithBalance ? ' (pago com saldo)' : '')}</strong></td><td className="price">{brl(order.totalCents)}</td></tr>
          </>
        )}
      </tbody></table></div>

      {order.status === 'PENDING' && (
        <section className="card">
          <h3>Pague com PIX</h3>
          {order.pixQrBase64 && <img className="qr" alt="QR Code PIX" src={'data:image/png;base64,' + order.pixQrBase64} />}
          {order.pixCode && <><p className="muted">Copia e cola:</p><div className="pix" tabIndex={0}>{order.pixCode}</div></>}
          <div className="row">
            <button className="btn primary" onClick={copy}>{copied ? 'Copiado!' : 'Copiar código'}</button>
            {order.simulated && <button className="btn" onClick={simulate}>Simular pagamento (modo teste)</button>}
          </div>
          <p className="muted">Esta página confirma sozinha assim que o pagamento cair. O pedido expira em {new Date(order.expiresAt).toLocaleTimeString('pt-BR', { hour: '2-digit', minute: '2-digit' })}.</p>
          {order.simulated && <p className="notice warn">Modo de teste: nenhum dinheiro é cobrado.</p>}
        </section>
      )}
      {order.status === 'PAID' && (
        <section className="notice">
          {deposit ? (
            <>
              <p><strong>Depósito confirmado!</strong> O saldo já está na sua carteira.</p>
              <p style={{ marginTop: 10 }}><a className="btn primary" href="#/saldo"><i className="fa-solid fa-wallet" aria-hidden="true" /> Ver meu saldo</a></p>
            </>
          ) : (
            <>
              <p><strong>{order.paidWithBalance ? 'Compra concluída com seu saldo!' : 'Pagamento confirmado!'}</strong> Seus plugins já estão na sua conta.</p>
              <p style={{ marginTop: 10 }}><a className="btn primary" href="#/area">Ir para a Área do Cliente</a></p>
            </>
          )}
        </section>
      )}
      {order.status === 'CREDITED' && (
        <section className="notice warn"><p>Seu PIX caiu depois de o pedido expirar e o saldo usado nele já tinha sido gasto, então o valor pago foi para o seu saldo. <a href="#/saldo">Ver meu saldo</a></p></section>
      )}
      {(order.status === 'EXPIRED' || order.status === 'CANCELLED') && (
        <section className="notice bad"><p>Este pedido não está mais ativo.{order.balanceCents > 0 && !order.paidWithBalance ? ' O saldo reservado voltou para a sua carteira.' : ''} {deposit
          ? <a href="#/saldo"><i className="fa-solid fa-arrow-left" aria-hidden="true" /> Fazer outro depósito</a>
          : <a href="#/carrinho"><i className="fa-solid fa-arrow-left" aria-hidden="true" /> Voltar ao carrinho</a>}</p></section>
      )}
    </div>
  );
}
const label = (s: Order['status']) => ({ PENDING: 'Aguardando pagamento', PAID: 'Pago', EXPIRED: 'Expirado', CANCELLED: 'Cancelado', CREDITED: 'Convertido em saldo' }[s]);

// ---------- saldo / depósito ----------
const TX_LABEL: Record<string, string> = { DEPOSIT: 'Depósito', BONUS: 'Bônus', PURCHASE: 'Compra', REFUND: 'Estorno', ADMIN_CREDIT: 'Crédito', ADMIN_DEBIT: 'Débito' };
const PRESETS = [10, 25, 50, 100];

function WalletPage() {
  const [wallet, setWallet] = useState<WalletView | null>(null);
  const [err, setErr] = useState('');
  const [amount, setAmount] = useState('');
  const [couponInput, setCouponInput] = useState('');
  const [dq, setDq] = useState<DepositQuote | null>(null);
  const [couponErr, setCouponErr] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => { api.wallet().then(setWallet).catch(e => setErr(msg(e))); }, []);

  const cents = parseBRL(amount);
  const valid = cents !== null && cents >= MIN_DEPOSIT && cents <= MAX_DEPOSIT;

  // prévia do bônus (o servidor calcula; aqui só mostramos)
  useEffect(() => {
    setDq(null); setCouponErr('');
    if (!valid || cents === null) return;
    let alive = true;
    const t = setTimeout(() => {
      api.depositQuote(cents, couponInput.trim() || null).then(q => { if (alive) setDq(q); }).catch(e => { if (alive) setCouponErr(msg(e)); });
    }, 400);
    return () => { alive = false; clearTimeout(t); };
  }, [cents, valid, couponInput]);

  const deposit = async () => {
    if (!valid || cents === null) return;
    setErr(''); setBusy(true);
    try { const o = await api.deposit(cents, couponInput.trim() || null); go('#/pedido/' + o.id); }
    catch (x) { setErr(msg(x)); }
    finally { setBusy(false); }
  };

  return (
    <div className="stack">
      <h2><i className="fa-solid fa-wallet" aria-hidden="true" /> Meu saldo</h2>
      {err && <p className="notice bad" role="alert">{err}</p>}
      <section className="card balance-card">
        <span className="muted">Saldo disponível</span>
        <span className="balance-amount">{wallet ? brl(wallet.balanceCents) : '…'}</span>
        <span className="muted">Use o saldo para pagar plugins no carrinho, sem gerar PIX a cada compra.</span>
      </section>

      <section className="card">
        <h3>Depositar</h3>
        <div className="chips" role="group" aria-label="Valores rápidos">
          {PRESETS.map(v => <button key={v} className={'chip' + (cents === v * 100 ? ' on' : '')} onClick={() => setAmount(String(v))}>{brl(v * 100)}</button>)}
        </div>
        <label className="f">Valor (R$ {MIN_DEPOSIT / 100} a {MAX_DEPOSIT / 100})
          <input type="text" inputMode="decimal" value={amount} onChange={e => setAmount(e.target.value)} placeholder="Ex.: 30,00" />
        </label>
        <label className="f">Cupom de bônus <span className="muted">(opcional)</span>
          <input type="text" value={couponInput} onChange={e => setCouponInput(e.target.value)} maxLength={32} autoCapitalize="characters" />
        </label>
        {couponErr && <p className="notice bad" role="alert">{couponErr}</p>}
        {dq && !couponErr && (
          <p className="notice" role="status">
            Você paga <b>{brl(dq.amountCents)}</b>{dq.bonusCents > 0 ? <> e recebe <b>{brl(dq.creditCents)}</b> de saldo (bônus de {brl(dq.bonusCents)} do cupom {dq.couponCode})</> : <> e recebe <b>{brl(dq.creditCents)}</b> de saldo</>}.
          </p>
        )}
        <div className="row">
          <button className="btn primary" onClick={deposit} disabled={!valid || busy || !!couponErr}>
            {busy ? <><i className="fa-solid fa-spinner fa-spin" aria-hidden="true" /> Gerando PIX…</> : <><i className="fa-brands fa-pix" aria-hidden="true" /> Depositar com PIX</>}
          </button>
        </div>
        <p className="muted">O saldo entra assim que o PIX for confirmado. Não é reembolsável em dinheiro.</p>
      </section>

      <section className="stack">
        <h3>Extrato</h3>
        {wallet && wallet.transactions.length === 0 && <p className="muted">Nenhuma movimentação ainda.</p>}
        {wallet && wallet.transactions.length > 0 && <div className="scroll"><table>
          <thead><tr><th>Data</th><th>Tipo</th><th>Descrição</th><th>Valor</th></tr></thead>
          <tbody>{wallet.transactions.map(t => (
            <tr key={t.id}>
              <td>{new Date(t.createdAt).toLocaleString('pt-BR')}</td>
              <td>{TX_LABEL[t.type] ?? t.type}</td>
              <td>{t.orderId ? <a href={'#/pedido/' + t.orderId}>{t.description ?? '—'}</a> : (t.description ?? '—')}</td>
              <td className={'price ' + (t.amountCents >= 0 ? 'disc' : 'neg')}>{t.amountCents >= 0 ? '+ ' : '− '}{brl(Math.abs(t.amountCents))}</td>
            </tr>))}</tbody>
        </table></div>}
      </section>
    </div>
  );
}

// ---------- Área do Cliente ----------
function AreaPage() {
  const [library, setLibrary] = useState<LibraryItem[]>([]);
  const [servers, setServers] = useState<ServerView[]>([]);
  const [orders, setOrders] = useState<Order[]>([]);
  const [err, setErr] = useState('');
  const [ready, setReady] = useState(false);

  const reload = useCallback(async () => {
    try {
      const [l, s, o] = await Promise.all([api.library(), api.servers(), api.orders()]);
      setLibrary(l); setServers(s); setOrders(o); setErr('');
    } catch (e) { setErr(msg(e)); }
    setReady(true);
  }, []);
  useEffect(() => { reload(); }, [reload]);
  const replace = (s: ServerView) => setServers(prev => prev.map(x => (x.id === s.id ? s : x)));

  return (
    <div className="stack">
      <h2>Área do Cliente</h2>
      {err && <p className="notice bad" role="alert">{err}</p>}
      {!ready && <p className="muted">Carregando…</p>}

      <section className="stack">
        <h3>Meus plugins</h3>
        {ready && library.length === 0 && <p className="muted">Você ainda não comprou nenhum plugin. <a href="#/">Ver catálogo</a></p>}
        <div className="grid">
          {library.map(p => (
            <article className="card" key={p.id}>
              <div className="card-head">
                <PixelIcon id={p.id} cat={p.category} />
                <div><CatTag cat={p.category} /><h3>{p.name}</h3></div>
              </div>
              <p className="muted">versão {p.version}{p.command ? <> · <code className="cmd">{p.command}</code></> : null}</p>
            </article>
          ))}
        </div>
      </section>

      <section className="stack">
        <h3>Meus servidores</h3>
        <p className="muted">Informe o IP e a porta exatos do servidor (a porta do <code>server.properties</code>). A licença vale para esse IP:porta.</p>
        {servers.map(s => <ServerCard key={s.id} server={s} library={library} onChange={replace} />)}
        <AddServer onAdded={s => setServers(prev => [...prev, s])} />
      </section>

      <section className="stack">
        <h3>Pedidos</h3>
        {ready && orders.length === 0 && <p className="muted">Nenhum pedido.</p>}
        {orders.length > 0 && <div className="scroll"><table>
          <thead><tr><th>#</th><th>Itens</th><th>Total</th><th>Status</th></tr></thead>
          <tbody>{orders.map(o => (
            <tr key={o.id}>
              <td><a href={'#/pedido/' + o.id}>#{o.id}</a></td>
              <td>{o.kind === 'DEPOSIT' ? 'Depósito de saldo' : o.items.map(i => i.name).join(', ')}</td>
              <td>{brl(o.totalCents)}</td>
              <td><span className={'status ' + o.status}>{label(o.status)}</span></td>
            </tr>))}</tbody>
        </table></div>}
      </section>
    </div>
  );
}

function AddServer({ onAdded }: { onAdded: (s: ServerView) => void }) {
  const [ip, setIp] = useState('');
  const [port, setPort] = useState('25565');
  const [lbl, setLbl] = useState('');
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setErr(''); setBusy(true);
    try { onAdded(await api.createServer(ip, Number(port), lbl)); setIp(''); setLbl(''); }
    catch (x) { setErr(msg(x)); }
    finally { setBusy(false); }
  };

  return (
    <form className="card" onSubmit={submit} aria-label="Adicionar servidor">
      <h3>Adicionar servidor</h3>
      <div className="row">
        <label className="f" style={{ flex: '2 1 180px' }}>IP<input type="text" value={ip} onChange={e => setIp(e.target.value)} placeholder="127.0.0.1" required /></label>
        <label className="f" style={{ flex: '1 1 100px' }}>Porta<input type="number" min={1} max={65535} value={port} onChange={e => setPort(e.target.value)} required /></label>
        <label className="f" style={{ flex: '2 1 180px' }}>Apelido (opcional)<input type="text" value={lbl} onChange={e => setLbl(e.target.value)} maxLength={60} /></label>
      </div>
      {err && <p className="notice bad" role="alert">{err}</p>}
      <div><button className="btn primary" disabled={busy}>{busy ? 'Adicionando…' : 'Adicionar'}</button></div>
    </form>
  );
}

/** productId -> versão escolhida ('' = acompanhar a mais recente). Só os plugins marcados têm entrada. */
type Picks = Record<string, string>;
const picksOf = (s: ServerView): Picks => Object.fromEntries(s.products.map(p => [p.productId, p.version ?? '']));

function ServerCard({ server, library, onChange }: { server: ServerView; library: LibraryItem[]; onChange: (s: ServerView) => void }) {
  const [sel, setSel] = useState<Picks>(picksOf(server));
  const [ip, setIp] = useState(server.ip);
  const [port, setPort] = useState(String(server.port));
  const [note, setNote] = useState<{ ok: boolean; text: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => { setSel(picksOf(server)); setIp(server.ip); setPort(String(server.port)); }, [server]);

  const toggle = (id: string) => setSel(s => {
    const n = { ...s };
    if (id in n) delete n[id]; else n[id] = '';
    return n;
  });
  const pick = (id: string, version: string) => setSel(s => ({ ...s, [id]: version }));
  const selections = (): Selection[] => Object.entries(sel).map(([productId, v]) => ({ productId, version: v || null }));
  const run = async (fn: () => Promise<ServerView>, ok: string) => {
    setNote(null); setBusy(true);
    try { onChange(await fn()); setNote({ ok: true, text: ok }); }
    catch (x) { setNote({ ok: false, text: msg(x) }); }
    finally { setBusy(false); }
  };

  const addrChanged = ip !== server.ip || Number(port) !== server.port;
  return (
    <article className="card">
      <div className="row between">
        <h3>{server.label || 'Servidor'} <span className="mono muted">{server.ip}:{server.port}</span></h3>
        {!server.active && <span className="status EXPIRED">Suspenso</span>}
      </div>
      <p className="muted">{server.lastSeenAt ? 'Última conexão: ' + new Date(server.lastSeenAt).toLocaleString('pt-BR') : 'Ainda não conectou.'}</p>

      <div className="checks" role="group" aria-label="Plugins deste servidor">
        {library.length === 0 && <p className="muted">Compre um plugin para poder marcá-lo aqui.</p>}
        {library.map(p => (
          <div className="pick" key={p.id}>
            <label><input type="checkbox" checked={p.id in sel} onChange={() => toggle(p.id)} />{p.name}</label>
            {p.id in sel && (
              <select aria-label={'Versão de ' + p.name} value={sel[p.id]} onChange={e => pick(p.id, e.target.value)}>
                <option value="">Sempre a mais recente ({p.version})</option>
                {p.versions.map(v => <option key={v.version} value={v.version}>{v.version}</option>)}
              </select>
            )}
          </div>
        ))}
      </div>
      <div className="row">
        <button className="btn primary" disabled={busy} onClick={() => run(() => api.setProducts(server.id, selections()), 'Atualizado! Ligar/desligar plugins vale em até 30 min. Para aplicar já (inclusive troca de versão), rode /coreplugins reload no servidor.')}>Atualizar</button>
      </div>

      <details>
        <summary>Trocar IP / porta</summary>
        <div className="row" style={{ marginTop: 10 }}>
          <input type="text" aria-label="IP" value={ip} onChange={e => setIp(e.target.value)} style={{ flex: '2 1 160px' }} />
          <input type="number" aria-label="Porta" min={1} max={65535} value={port} onChange={e => setPort(e.target.value)} style={{ flex: '1 1 90px' }} />
          <button className="btn" disabled={busy || !addrChanged} onClick={() => run(() => api.changeAddress(server.id, ip, Number(port)), 'Endereço alterado.')}>Salvar</button>
        </div>
      </details>
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.text}</p>}
    </article>
  );
}