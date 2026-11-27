import { useCallback, useEffect, useState } from 'react';
import { brl } from './api';
import type { ServerView } from './api';

// ---------- API de administração (login de admin + 2FA; sessão Bearer curta, só na aba, em sessionStorage) ----------
interface AProduct { id: string; name: string; description: string | null; active: boolean; latestVersion: string | null; priceCents: number; category: string | null; command: string | null; youtubeId: string | null }
interface AImage { id: number; position: number; contentType: string; sizeBytes: number }
interface AVersion { id: number; productId: string; version: string; sha256: string; sizeBytes: number; createdAt: string; changelog: string | null }
interface ALoader { available: boolean; sizeBytes: number; sha256: string | null; updatedAt: string | null; source: 'database' | 'disk' | null }
interface ACheckin { enabled: boolean; rewardsCents: number[]; resetOnMiss: boolean }
interface ACustomer { id: number; name: string; email: string; createdAt: string; balanceCents: number }
interface AOrder { id: number; customerId: number; customerEmail: string; status: string; totalCents: number; createdAt: string; paidAt: string | null; items: string[]; kind: string; discountCents: number; bonusCents: number; couponCode: string | null; paidWithBalance: boolean; balanceCents: number }
interface ACoupon { id: number; code: string; kind: 'DISCOUNT' | 'DEPOSIT_BONUS'; valueType: 'PERCENT' | 'FIXED'; value: number; minCents: number; capCents: number | null; maxUses: number | null; maxUsesPerCustomer: number | null; usedCount: number; expiresAt: string | null; active: boolean; createdAt: string }
interface AEntitlement { customerId: number; productId: string; createdAt: string }
interface AuthResult { step: 'totp' | 'enroll' | 'done'; challenge: string | null; secret: string | null; otpauthUri: string | null; token: string | null; expiresAt: number | null; name: string | null; email: string | null }
interface AdminSession { token: string; expiresAt: number; name: string; email: string }

const SESSION_KEY = 'coreplugins.adminsession';
const LOGOUT_EVENT = 'coreplugins-admin-logout';
const getSession = (): AdminSession | null => {
  try {
    const s = JSON.parse(sessionStorage.getItem(SESSION_KEY) ?? 'null') as AdminSession | null;
    return s && typeof s.token === 'string' && typeof s.expiresAt === 'number' && s.expiresAt > Date.now() ? s : null;
  } catch { return null; }
};
const setSession = (s: AdminSession | null) => { try { if (s) sessionStorage.setItem(SESSION_KEY, JSON.stringify(s)); else sessionStorage.removeItem(SESSION_KEY); } catch { /* sem storage */ } };
try { sessionStorage.removeItem('coreplugins.admintoken'); } catch { /* o ADMIN_TOKEN antigo não é mais usado: apaga o que sobrou na aba */ }

class AdminError extends Error { constructor(public status: number, m: string) { super(m); } }

async function acall<T>(method: string, path: string, body?: unknown, form?: FormData): Promise<T> {
  const headers: Record<string, string> = {};
  const session = getSession();
  if (session) headers['Authorization'] = 'Bearer ' + session.token;
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  let res: Response;
  try {
    res = await fetch('/api/admin' + path, { method, headers, body: form ?? (body === undefined ? undefined : JSON.stringify(body)) });
  } catch { throw new AdminError(0, 'Não foi possível falar com o servidor.'); }
  let data: unknown = null;
  try { data = await res.json(); } catch { /* sem corpo */ }
  if (!res.ok) {
    const d = data as { error?: string; message?: string } | null;
    const isLogin = path === '/auth/login' || path === '/auth/verify';
    if (res.status === 401 && !isLogin) { setSession(null); window.dispatchEvent(new Event(LOGOUT_EVENT)); }
    const text = res.status === 401 && !isLogin ? 'Sessão expirada. Entre novamente.'
      : res.status === 429 ? 'Muitas tentativas. Aguarde alguns minutos.'
      : d?.error && d.error !== 'Bad Request' ? d.error : d?.message || 'Erro (' + res.status + ').';
    throw new AdminError(res.status, text);
  }
  return data as T;
}
const emsg = (e: unknown) => (e instanceof Error ? e.message : 'Algo deu errado.');

/** "29,90" ou "29.90" -> 2990 (centavos). null se inválido. */
function parseBRL(t: string): number | null {
  let x = t.trim().replace(/^R\$\s*/, '');
  if (!/^\d[\d.,]*$/.test(x)) return null;
  x = x.includes(',') ? x.replace(/\./g, '').replace(',', '.') : x; // "1.234,50" -> "1234.50"; "29.90" fica
  const n = Number(x);
  return Number.isFinite(n) && n >= 0 ? Math.round(n * 100) : null;
}
const fmtDate = (s: string | null) => (s ? new Date(s).toLocaleString('pt-BR') : '—');
const MAX_IMG_BYTES = 5 * 1024 * 1024; // igual ao limite do servidor
const MAX_IMGS = 10;
const fmtSize = (b: number) => (b > 1048576 ? (b / 1048576).toFixed(1) + ' MB' : Math.max(1, Math.round(b / 1024)) + ' KB');

// ---------- página ----------
type Tab = 'resumo' | 'produtos' | 'pedidos' | 'clientes' | 'cupons' | 'checkin' | 'servidores';
const TABS: [Tab, string][] = [['resumo', 'Resumo'], ['produtos', 'Produtos'], ['pedidos', 'Pedidos'], ['clientes', 'Clientes'], ['cupons', 'Cupons'], ['checkin', 'Check-in'], ['servidores', 'Servidores']];

export function AdminPage() {
  const [authed, setAuthed] = useState(false);
  const [tab, setTab] = useState<Tab>('resumo');

  useEffect(() => { // sessão da aba ainda válida no servidor?
    if (getSession()) acall('GET', '/auth/me').then(() => setAuthed(true)).catch(() => setSession(null));
  }, []);

  useEffect(() => { // sessão derrubada (401) ou vencida: volta para o login
    const out = () => setAuthed(false);
    window.addEventListener(LOGOUT_EVENT, out);
    const s = getSession();
    const timer = authed && s ? window.setTimeout(() => { setSession(null); setAuthed(false); }, Math.max(0, s.expiresAt - Date.now())) : undefined;
    return () => { window.removeEventListener(LOGOUT_EVENT, out); if (timer) window.clearTimeout(timer); };
  }, [authed]);

  if (!authed) return <AdminLogin onDone={() => setAuthed(true)} />;
  return (
    <div className="stack">
      <div className="row between">
        <h2>Administração</h2>
        <span className="row">
          <span className="muted">{getSession()?.email}</span>
          <button className="btn small" onClick={() => { acall('POST', '/auth/logout').catch(() => { /* a sessão local é apagada mesmo assim */ }); setSession(null); setAuthed(false); }}>Sair do painel</button>
        </span>
      </div>
      <div className="row" role="tablist" aria-label="Seções">
        {TABS.map(([k, l]) => <button key={k} role="tab" aria-selected={tab === k} className={'btn small' + (tab === k ? ' primary' : '')} onClick={() => setTab(k)}>{l}</button>)}
      </div>
      {tab === 'resumo' && <Summary />}
      {tab === 'produtos' && <Products />}
      {tab === 'pedidos' && <Orders />}
      {tab === 'clientes' && <Customers />}
      {tab === 'cupons' && <Coupons />}
      {tab === 'checkin' && <CheckinAdmin />}
      {tab === 'servidores' && <Servers />}
    </div>
  );
}

function AdminLogin({ onDone }: { onDone: () => void }) {
  const [step, setStep] = useState<'cred' | 'totp' | 'enroll'>('cred');
  const [email, setEmail] = useState('');
  const [password, setPassword] = useState('');
  const [code, setCode] = useState('');
  const [challenge, setChallenge] = useState('');
  const [enroll, setEnroll] = useState<{ secret: string; uri: string } | null>(null);
  const [err, setErr] = useState('');
  const [busy, setBusy] = useState(false);

  const handle = (r: AuthResult) => {
    if (r.step === 'done' && r.token && r.expiresAt) {
      setSession({ token: r.token, expiresAt: r.expiresAt, name: r.name ?? '', email: r.email ?? '' });
      onDone();
      return;
    }
    setPassword(''); setCode(''); setChallenge(r.challenge ?? '');
    if (r.step === 'enroll' && r.secret && r.otpauthUri) { setEnroll({ secret: r.secret, uri: r.otpauthUri }); setStep('enroll'); }
    else { setEnroll(null); setStep('totp'); }
  };
  const restart = () => { setStep('cred'); setPassword(''); setCode(''); setChallenge(''); setEnroll(null); };

  const submitCred = async (e: React.FormEvent) => {
    e.preventDefault(); setErr(''); setBusy(true);
    try { handle(await acall<AuthResult>('POST', '/auth/login', { email: email.trim(), password })); }
    catch (x) { setErr(emsg(x)); }
    finally { setBusy(false); }
  };
  const submitCode = async (e: React.FormEvent) => {
    e.preventDefault(); setErr(''); setBusy(true);
    try { handle(await acall<AuthResult>('POST', '/auth/verify', { challenge, code: code.trim() })); }
    catch (x) {
      if (x instanceof AdminError && x.status === 401 && /expirou/.test(x.message)) restart(); // desafio de 5 min venceu
      setErr(emsg(x)); setCode('');
    }
    finally { setBusy(false); }
  };

  if (step === 'cred') return (
    <div className="stack">
      <h2>Administração</h2>
      <form className="form" onSubmit={submitCred}>
        <label className="f">E-mail<input type="email" value={email} onChange={e => setEmail(e.target.value)} autoComplete="username" required /></label>
        <label className="f">Senha<input type="password" value={password} onChange={e => setPassword(e.target.value)} autoComplete="current-password" required /></label>
        {err && <p className="notice bad" role="alert">{err}</p>}
        <button className="btn primary" disabled={busy}>Continuar</button>
        <p className="muted">Conta de administrador. A sessão dura 1 hora e fica só nesta aba do navegador.</p>
      </form>
    </div>
  );

  const grouped = enroll ? enroll.secret.replace(/(.{4})/g, '$1 ').trim() : '';
  return (
    <div className="stack">
      <h2>Verificação em duas etapas</h2>
      <form className="form" onSubmit={submitCode}>
        {step === 'enroll' && enroll && (
          <div className="stack">
            <p>Primeiro acesso: cadastre esta conta no seu app de autenticação (Google Authenticator, Authy, 1Password…).</p>
            <p>Adicione uma conta e informe a chave manualmente:</p>
            <p><code>{grouped}</code></p>
            {enroll.uri.startsWith('otpauth://') && <p><a href={enroll.uri}>Abrir no app de autenticação (celular)</a></p>}
            <p className="muted">Guarde a chave em local seguro: ela só aparece enquanto o cadastro não for confirmado. Depois, digite abaixo o código de 6 dígitos que o app mostrar.</p>
          </div>
        )}
        <label className="f">Código de 6 dígitos
          <input value={code} onChange={e => setCode(e.target.value)} inputMode="numeric" autoComplete="one-time-code" pattern="[0-9 ]*" maxLength={7} autoFocus required />
        </label>
        {err && <p className="notice bad" role="alert">{err}</p>}
        <div className="row">
          <button className="btn primary" disabled={busy}>{step === 'enroll' ? 'Confirmar e entrar' : 'Entrar'}</button>
          <button type="button" className="btn small" onClick={() => { restart(); setErr(''); }}>Voltar</button>
        </div>
      </form>
    </div>
  );
}

function useLoad<T>(path: string, initial: T) {
  const [data, setData] = useState<T>(initial);
  const [err, setErr] = useState('');
  const [ready, setReady] = useState(false);
  const reload = useCallback(() => acall<T>('GET', path).then(d => { setData(d); setErr(''); setReady(true); }).catch(e => { setErr(emsg(e)); setReady(true); }), [path]);
  useEffect(() => { reload(); }, [reload]);
  return { data, err, ready, reload };
}

// ---------- corePlugins.jar (o loader que o cliente baixa) ----------
function LoaderAdmin() {
  const info = useLoad<ALoader | null>('/loader', null);
  const [file, setFile] = useState<File | null>(null);
  const [fileKey, setFileKey] = useState(0);
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<{ ok: boolean; t: string } | null>(null);

  const send = async (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    setNote(null); setBusy(true);
    try {
      const fd = new FormData(); fd.append('file', file);
      await acall('POST', '/loader', undefined, fd);
      setNote({ ok: true, t: 'corePlugins.jar enviado. Os clientes já baixam esta versão.' });
      setFile(null); setFileKey(k => k + 1); info.reload();
    } catch (x) { setNote({ ok: false, t: emsg(x) }); }
    finally { setBusy(false); }
  };

  const d = info.data;
  return (
    <form className="stack card" onSubmit={send}>
      <h3>corePlugins.jar para download</h3>
      {info.err && <p className="notice bad" role="alert">{info.err}</p>}
      {d && d.available && <p className="muted">Atual: {fmtSize(d.sizeBytes)} · {fmtDate(d.updatedAt)}{d.sha256 ? <> · SHA-256 <code title={d.sha256}>{d.sha256.slice(0, 12)}…</code></> : null}{d.source === 'disk' ? ' · vindo de um arquivo em disco (só para desenvolvimento): envie o jar para guardá-lo no banco.' : ''}</p>}
      {d && !d.available && <p className="muted">Nenhum jar enviado: o botão de download aparece indisponível para os clientes.</p>}
      <div className="row">
        <label className="f" style={{ flex: '2 1 240px' }}>Arquivo corePlugins.jar<input key={fileKey} type="file" accept=".jar" onChange={e => setFile(e.target.files?.[0] ?? null)} required /></label>
        <button className="btn primary" disabled={busy || !file}>{busy ? 'Enviando…' : 'Enviar'}</button>
      </div>
      <p className="muted">Use o jar do build final (o que tem a URL da API e as chaves públicas embutidas). Fica guardado no banco, como os jars dos produtos; envie de novo a cada nova versão do loader.</p>
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.t}</p>}
    </form>
  );
}

// ---------- resumo ----------
function Summary() {
  const orders = useLoad<AOrder[]>('/orders', []);
  const customers = useLoad<ACustomer[]>('/customers', []);
  const servers = useLoad<ServerView[]>('/servers', []);
  const paid = orders.data.filter(o => o.status === 'PAID');
  // Receita = dinheiro que entrou de verdade (PIX): depósitos + compras pagas no PIX.
  // Compra paga com saldo não conta: o dinheiro já entrou como depósito (e o bônus também não é dinheiro).
  const revenue = paid.filter(o => !o.paidWithBalance).reduce((s, o) => s + o.totalCents, 0);
  const sales = paid.filter(o => o.kind !== 'DEPOSIT');
  const err = orders.err || customers.err || servers.err;
  const stat = (label: string, value: string) => <div className="card"><span className="tag">{label}</span><span className="price">{value}</span></div>;
  return (
    <div className="stack">
      {err && <p className="notice bad" role="alert">{err}</p>}
      <div className="grid">
        {stat('Receita (dinheiro recebido)', brl(revenue))}
        {stat('Compras pagas', String(sales.length))}
        {stat('Aguardando pagamento', String(orders.data.filter(o => o.status === 'PENDING').length))}
        {stat('Clientes', String(customers.data.length))}
        {stat('Servidores ativos', String(servers.data.filter(s => s.active).length))}
        {stat('Servidores suspensos', String(servers.data.filter(s => !s.active).length))}
      </div>
      <LoaderAdmin />
    </div>
  );
}

// ---------- produtos ----------
function Products() {
  const { data, err, ready, reload } = useLoad<AProduct[]>('/products', []);
  const [open, setOpen] = useState<string | null>(null);
  return (
    <div className="stack">
      {err && <p className="notice bad" role="alert">{err}</p>}
      <NewProduct onCreated={reload} />
      {ready && data.length === 0 && <p className="muted">Nenhum produto ainda.</p>}
      {data.map(p => (
        <article className="card" key={p.id}>
          <div className="row between">
            <h3>{p.name} <span className="mono muted">{p.id}</span></h3>
            <div className="row">
              <span className={'status ' + (p.active ? 'PAID' : 'EXPIRED')}>{p.active ? 'À venda' : 'Oculto'}</span>
              <button className="btn small" onClick={() => setOpen(open === p.id ? null : p.id)}>{open === p.id ? 'Fechar' : 'Gerenciar'}</button>
            </div>
          </div>
          <p className="muted">{brl(p.priceCents)} · {p.category ?? 'sem categoria'} · versão atual: {p.latestVersion ?? 'nenhuma (suba um jar para vender)'}</p>
          {open === p.id && <ProductEditor product={p} onChanged={reload} />}
        </article>
      ))}
    </div>
  );
}

function NewProduct({ onCreated }: { onCreated: () => void }) {
  const [f, setF] = useState({ id: '', name: '', description: '', price: '', category: '', command: '' });
  const [err, setErr] = useState('');
  const [ok, setOk] = useState('');
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });
  const submit = async (e: React.FormEvent) => {
    e.preventDefault(); setErr(''); setOk('');
    const cents = parseBRL(f.price);
    if (cents === null) { setErr('Preço inválido. Exemplo: 29,90'); return; }
    try {
      await acall('POST', '/products', { id: f.id.trim(), name: f.name.trim(), description: f.description, priceCents: cents, category: f.category.trim() || null, command: f.command.trim() || null });
      setOk('Produto criado. Agora gerencie-o e envie o jar da primeira versão.');
      setF({ id: '', name: '', description: '', price: '', category: '', command: '' }); onCreated();
    } catch (x) { setErr(emsg(x)); }
  };
  return (
    <details className="card">
      <summary><strong>Novo produto</strong></summary>
      <form onSubmit={submit} className="stack" style={{ marginTop: 12 }}>
        <div className="row">
          <label className="f" style={{ flex: '1 1 160px' }}>Id (slug, sem espaços)<input type="text" value={f.id} onChange={set('id')} pattern="[A-Za-z0-9._-]{1,64}" placeholder="minas" required /></label>
          <label className="f" style={{ flex: '2 1 200px' }}>Nome<input type="text" value={f.name} onChange={set('name')} required /></label>
          <label className="f" style={{ flex: '1 1 120px' }}>Preço (R$)<input type="text" value={f.price} onChange={set('price')} placeholder="29,90" required /></label>
        </div>
        <label className="f">Descrição<input type="text" value={f.description} onChange={set('description')} maxLength={1000} /></label>
        <div className="row">
          <label className="f" style={{ flex: '1 1 200px' }}>Categoria (define a cor)<input type="text" value={f.category} onChange={set('category')} placeholder="PvP, Economia, Otimização, Minigames" /></label>
          <label className="f" style={{ flex: '1 1 140px' }}>Comando principal<input type="text" value={f.command} onChange={set('command')} placeholder="/minas" /></label>
        </div>
        {err && <p className="notice bad" role="alert">{err}</p>}
        {ok && <p className="notice" role="status">{ok}</p>}
        <div><button className="btn primary">Criar produto</button></div>
      </form>
    </details>
  );
}

function ProductEditor({ product, onChanged }: { product: AProduct; onChanged: () => void }) {
  const versions = useLoad<AVersion[]>('/products/' + product.id + '/versions', []);
  const images = useLoad<AImage[]>('/products/' + product.id + '/images', []);
  const [yt, setYt] = useState(product.youtubeId ? 'https://youtu.be/' + product.youtubeId : '');
  const [f, setF] = useState({ name: product.name, description: product.description ?? '', price: (product.priceCents / 100).toFixed(2).replace('.', ','), category: product.category ?? '', command: product.command ?? '' });
  const [note, setNote] = useState<{ ok: boolean; t: string } | null>(null);
  const [busy, setBusy] = useState(false);
  const [ver, setVer] = useState('');
  const [latest, setLatest] = useState(true);
  const [file, setFile] = useState<File | null>(null);
  const [fileKey, setFileKey] = useState(0); // recria o <input type=file> depois do envio
  const [notes, setNotes] = useState(''); // notas da versão nova (aparecem em Novidades no site)
  const [editing, setEditing] = useState<string | null>(null); // versão em edição
  const [editVer, setEditVer] = useState('');
  const [editFile, setEditFile] = useState<File | null>(null);
  const [editNotes, setEditNotes] = useState('');
  const [editKey, setEditKey] = useState(0);
  const set = (k: keyof typeof f) => (e: React.ChangeEvent<HTMLInputElement>) => setF({ ...f, [k]: e.target.value });

  const run = async (fn: () => Promise<unknown>, okText: string, after?: () => void) => {
    setNote(null); setBusy(true);
    try { await fn(); setNote({ ok: true, t: okText }); onChanged(); versions.reload(); after?.(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
    finally { setBusy(false); }
  };
  const save = () => {
    const cents = parseBRL(f.price);
    if (cents === null) { setNote({ ok: false, t: 'Preço inválido. Exemplo: 29,90' }); return; }
    run(() => acall('PUT', '/products/' + product.id, { name: f.name, description: f.description, priceCents: cents, category: f.category, command: f.command }), 'Produto salvo.');
  };
  const saveVideo = () => run(() => acall('PUT', '/products/' + product.id, { youtubeUrl: yt.trim() }), yt.trim() ? 'Vídeo salvo.' : 'Vídeo removido.');
  const pickImages = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const files = Array.from(e.target.files ?? []);
    e.target.value = ''; // permite escolher o mesmo arquivo de novo
    if (files.length === 0) return;
    setNote(null); setBusy(true);
    let done = 0;
    try {
      for (const f of files) {
        if (f.size > MAX_IMG_BYTES) throw new Error('"' + f.name + '" passa de ' + MAX_IMG_BYTES / 1048576 + ' MB.');
        const fd = new FormData(); fd.append('file', f);
        await acall('POST', '/products/' + product.id + '/images', undefined, fd);
        done++;
      }
      setNote({ ok: true, t: done === 1 ? 'Imagem enviada.' : done + ' imagens enviadas.' });
    } catch (x) { setNote({ ok: false, t: (done ? done + ' enviada(s). ' : '') + emsg(x) }); }
    finally { await images.reload(); onChanged(); setBusy(false); }
  };
  const moveImage = (i: number, d: -1 | 1) => {
    const ids = images.data.map(x => x.id); const j = i + d;
    if (j < 0 || j >= ids.length) return;
    [ids[i], ids[j]] = [ids[j], ids[i]];
    run(async () => { await acall('PUT', '/products/' + product.id + '/images/order', { ids }); await images.reload(); }, 'Ordem atualizada. A primeira imagem é a capa.');
  };
  const delImage = (id: number) => {
    if (!window.confirm('Apagar esta imagem?')) return;
    run(async () => { await acall('DELETE', '/products/' + product.id + '/images/' + id); await images.reload(); }, 'Imagem apagada.');
  };
  const upload = (e: React.FormEvent) => {
    e.preventDefault();
    if (!file) return;
    const fd = new FormData(); fd.append('file', file);
    if (notes.trim()) fd.append('changelog', notes.trim());
    run(() => acall('POST', '/products/' + product.id + '/versions?version=' + encodeURIComponent(ver.trim()) + '&latest=' + latest, undefined, fd),
      'Versão ' + ver.trim() + ' enviada.', () => { setVer(''); setNotes(''); setFile(null); setFileKey(k => k + 1); });
  };
  const startEdit = (v: string) => { setEditing(v); setEditVer(v); setEditFile(null); setEditNotes(versions.data.find(x => x.version === v)?.changelog ?? ''); setEditKey(k => k + 1); setNote(null); };
  const saveEdit = (e: React.FormEvent) => {
    e.preventDefault();
    if (!editing) return;
    const newVer = editVer.trim();
    const notesChanged = editNotes.trim() !== (versions.data.find(x => x.version === editing)?.changelog ?? '');
    const changed = newVer !== editing || !!editFile;
    if (!changed && !notesChanged) { setNote({ ok: false, t: 'Nada para alterar: mude o número da versão, as notas e/ou escolha um novo jar.' }); return; }
    const fd = new FormData();
    if (newVer !== editing) fd.append('newVersion', newVer);
    if (editFile) fd.append('file', editFile);
    run(async () => {
      // as notas vão primeiro, com o número antigo; depois a troca de número/jar
      if (notesChanged) await acall('PUT', '/products/' + product.id + '/versions/' + encodeURIComponent(editing) + '/changelog', { changelog: editNotes });
      if (changed) await acall('POST', '/products/' + product.id + '/versions/' + encodeURIComponent(editing), undefined, fd);
    }, 'Versão ' + editing + ' atualizada.', () => { setEditing(null); setEditFile(null); });
  };
  const delVersion = (v: string) => {
    if (!window.confirm('Excluir a versão ' + v + ' de "' + product.name + '"?\n\nO jar será apagado do servidor. Quem tinha essa versão escolhida passa a receber a mais recente.')) return;
    run(() => acall('DELETE', '/products/' + product.id + '/versions/' + encodeURIComponent(v)), 'Versão ' + v + ' excluída.', () => { if (editing === v) setEditing(null); });
  };
  const delProduct = () => {
    if (!window.confirm('EXCLUIR o plugin "' + product.name + '" (' + product.id + ')?\n\nIsso apaga todas as versões e jars, remove o plugin dos servidores dos clientes e tira o acesso de quem comprou. O histórico de pedidos é mantido. Não dá para desfazer.')) return;
    if (window.prompt('Para confirmar, digite o id do plugin: ' + product.id) !== product.id) { setNote({ ok: false, t: 'Id não confere; nada foi excluído.' }); return; }
    run(() => acall('DELETE', '/products/' + product.id), 'Plugin excluído.');
  };

  return (
    <div className="stack">
      <div className="row">
        <label className="f" style={{ flex: '2 1 180px' }}>Nome<input type="text" value={f.name} onChange={set('name')} /></label>
        <label className="f" style={{ flex: '1 1 110px' }}>Preço (R$)<input type="text" value={f.price} onChange={set('price')} /></label>
        <label className="f" style={{ flex: '1 1 160px' }}>Categoria<input type="text" value={f.category} onChange={set('category')} /></label>
        <label className="f" style={{ flex: '1 1 120px' }}>Comando<input type="text" value={f.command} onChange={set('command')} /></label>
      </div>
      <label className="f">Descrição<input type="text" value={f.description} onChange={set('description')} maxLength={1000} /></label>
      <div className="row">
        <button className="btn primary" disabled={busy} onClick={save}>Salvar</button>
        <button className="btn" disabled={busy} onClick={() => run(() => acall('PUT', '/products/' + product.id, { active: !product.active }), product.active ? 'Produto ocultado da loja.' : 'Produto à venda.')}>
          {product.active ? 'Ocultar da loja' : 'Colocar à venda'}
        </button>
        <button className="btn" disabled={busy} onClick={delProduct} style={{ marginLeft: 'auto', color: 'var(--bad, #e5484d)' }}>Excluir plugin</button>
      </div>

      <h3>Imagens e vídeo</h3>
      <section className="media-admin" aria-label="Imagens e vídeo do plugin">
        <div>
          <strong>Galeria</strong> <span className="muted">({images.data.length}/{MAX_IMGS}) · PNG, JPG, WebP ou GIF, até {MAX_IMG_BYTES / 1048576} MB cada · a primeira é a capa do card</span>
        </div>
        {images.err && <p className="notice bad" role="alert">{images.err}</p>}
        {images.data.length > 0 && <div className="media-grid">
          {images.data.map((img, i) => (
            <div className="media-item" key={img.id}>
              <img src={'/api/store/products/' + encodeURIComponent(product.id) + '/images/' + img.id} alt={'Imagem ' + (i + 1) + ' do plugin'} loading="lazy" />
              <span className="muted">{i === 0 ? 'Capa · ' : ''}{fmtSize(img.sizeBytes)}</span>
              <div className="row">
                <button className="btn small" disabled={busy || i === 0} onClick={() => moveImage(i, -1)} aria-label={'Mover imagem ' + (i + 1) + ' para antes'}>←</button>
                <button className="btn small" disabled={busy || i === images.data.length - 1} onClick={() => moveImage(i, 1)} aria-label={'Mover imagem ' + (i + 1) + ' para depois'}>→</button>
                <button className="btn small" disabled={busy} onClick={() => delImage(img.id)} style={{ color: 'var(--bad, #e5484d)' }}>Apagar</button>
              </div>
            </div>))}
        </div>}
        <label className="f" style={{ maxWidth: 420 }}>Adicionar imagens
          <input type="file" accept="image/png,image/jpeg,image/webp,image/gif" multiple disabled={busy || images.data.length >= MAX_IMGS} onChange={pickImages} />
        </label>
        <div className="row">
          <label className="f" style={{ flex: '1 1 320px' }}>Vídeo de showcase (link do YouTube)
            <input type="text" value={yt} onChange={e => setYt(e.target.value)} placeholder="https://www.youtube.com/watch?v=… ou https://youtu.be/…" maxLength={200} />
          </label>
          <button className="btn primary" disabled={busy} onClick={saveVideo} style={{ alignSelf: 'end' }}>Salvar vídeo</button>
        </div>
        <p className="muted">{product.youtubeId ? <>Vídeo atual: <code>{product.youtubeId}</code>. Deixe o campo vazio e salve para remover.</> : 'Sem vídeo. Aceita link completo, link curto (youtu.be) ou só o ID de 11 caracteres.'}</p>
      </section>

      <h3>Versões</h3>
      {versions.err && <p className="notice bad" role="alert">{versions.err}</p>}
      {versions.data.length > 0 && <div className="scroll"><table>
        <thead><tr><th>Versão</th><th>Enviada em</th><th>Tamanho</th><th>SHA-256</th><th /></tr></thead>
        <tbody>{versions.data.map(v => (
          <tr key={v.id}>
            <td><strong>{v.version}</strong>{product.latestVersion === v.version && <> <span className="status PAID">mais recente</span></>}</td>
            <td>{fmtDate(v.createdAt)}</td><td>{fmtSize(v.sizeBytes)}</td>
            <td className="mono" title={v.sha256}>{v.sha256.slice(0, 12)}…</td>
            <td><div className="row" style={{ gap: 6 }}>
              {product.latestVersion !== v.version && <button className="btn small" disabled={busy} onClick={() => run(() => acall('PUT', '/products/' + product.id + '/latest', { version: v.version }), 'A versão ' + v.version + ' agora é a mais recente.')}>Tornar a mais recente</button>}
              <button className="btn small" disabled={busy} onClick={() => startEdit(v.version)}>Editar</button>
              <button className="btn small" disabled={busy} onClick={() => delVersion(v.version)} style={{ color: 'var(--bad, #e5484d)' }}>Excluir</button>
            </div></td>
          </tr>))}</tbody>
      </table></div>}

      {editing && <form className="stack card" onSubmit={saveEdit}>
        <h3>Editar versão {editing}</h3>
        <div className="row">
          <label className="f" style={{ flex: '1 1 140px' }}>Número da versão<input type="text" value={editVer} onChange={e => setEditVer(e.target.value)} pattern="[A-Za-z0-9._-]{1,64}" required /></label>
          <label className="f" style={{ flex: '2 1 220px' }}>Trocar o jar (opcional)<input key={editKey} type="file" accept=".jar" onChange={e => setEditFile(e.target.files?.[0] ?? null)} /></label>
        </div>
        <label className="f">Notas da versão (aparecem em Novidades para quem comprou)<textarea value={editNotes} onChange={e => setEditNotes(e.target.value)} rows={4} maxLength={4000} placeholder="O que mudou nesta versão?" /></label>
        <p className="muted">Deixe o arquivo vazio para só renomear. Com um jar novo, os servidores baixam o arquivo atualizado no próximo login do loader. Quem tinha esta versão escolhida continua nela.</p>
        <div className="row">
          <button className="btn primary" disabled={busy}>{busy ? 'Salvando…' : 'Salvar alterações'}</button>
          <button type="button" className="btn" disabled={busy} onClick={() => setEditing(null)}>Cancelar</button>
        </div>
      </form>}

      <form className="stack" onSubmit={upload}>
        <h3>Enviar nova versão</h3>
        <div className="row">
          <label className="f" style={{ flex: '1 1 140px' }}>Versão<input type="text" value={ver} onChange={e => setVer(e.target.value)} placeholder="1.0.0" pattern="[A-Za-z0-9._-]{1,64}" required /></label>
          <label className="f" style={{ flex: '2 1 220px' }}>Arquivo .jar<input key={fileKey} type="file" accept=".jar" onChange={e => setFile(e.target.files?.[0] ?? null)} required /></label>
        </div>
        <label className="f">Notas da versão (opcional; aparecem em Novidades para quem comprou)<textarea value={notes} onChange={e => setNotes(e.target.value)} rows={4} maxLength={4000} placeholder="O que mudou nesta versão?" /></label>
        <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={latest} onChange={e => setLatest(e.target.checked)} style={{ width: 18, height: 18, accentColor: 'var(--accent)' }} />Tornar a mais recente (quem acompanha a mais recente passa a receber esta)</label>
        <p className="muted">Para corrigir uma versão já enviada, use Editar na tabela acima (troca o número e/ou o jar). O jar precisa ter <code>plugin.yml</code> e a classe principal estender <code>ProductPlugin</code>.</p>
        <div><button className="btn primary" disabled={busy || !file}>{busy ? 'Enviando…' : 'Enviar versão'}</button></div>
      </form>
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.t}</p>}
    </div>
  );
}

// ---------- pedidos ----------
const STATUS: Record<string, string> = { PENDING: 'Aguardando', PAID: 'Pago', EXPIRED: 'Expirado', CANCELLED: 'Cancelado', CREDITED: 'Virou saldo' };
// ---------- check-in diário ----------
function CheckinAdmin() {
  const cfg = useLoad<ACheckin | null>('/checkin', null);
  const [enabled, setEnabled] = useState(false);
  const [resetOnMiss, setResetOnMiss] = useState(true);
  const [days, setDays] = useState<string[]>([]);
  const [note, setNote] = useState<{ ok: boolean; t: string } | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!cfg.data) return;
    setEnabled(cfg.data.enabled); setResetOnMiss(cfg.data.resetOnMiss);
    setDays(cfg.data.rewardsCents.map(c => (c / 100).toFixed(2).replace('.', ',')));
  }, [cfg.data]);

  const save = async (e: React.FormEvent) => {
    e.preventDefault();
    const cents: number[] = [];
    for (const d of days) {
      const c = parseBRL(d);
      if (c === null) { setNote({ ok: false, t: 'Valor inválido: "' + d + '". Use o formato 0,50.' }); return; }
      cents.push(c);
    }
    setNote(null); setBusy(true);
    try { await acall('PUT', '/checkin', { enabled, rewardsCents: cents, resetOnMiss }); setNote({ ok: true, t: 'Configuração salva.' }); cfg.reload(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
    finally { setBusy(false); }
  };

  return (
    <form className="stack" onSubmit={save}>
      <h3>Check-in diário</h3>
      <p className="muted">O cliente resgata uma vez por dia (o dia vira à meia-noite, horário de Brasília) e o prêmio entra no saldo da carteira. O valor de cada dia vale para o N-º dia seguido; depois do último, a sequência de prêmios recomeça no dia 1. Vem desligado até você ativar aqui.</p>
      {cfg.err && <p className="notice bad" role="alert">{cfg.err}</p>}
      <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} style={{ width: 18, height: 18, accentColor: 'var(--accent)' }} />Check-in ativado</label>
      <label className="row" style={{ gap: 8 }}><input type="checkbox" checked={resetOnMiss} onChange={e => setResetOnMiss(e.target.checked)} style={{ width: 18, height: 18, accentColor: 'var(--accent)' }} />Faltar um dia zera a sequência (desmarcado: ela só para de crescer e continua de onde parou)</label>
      <div className="row">
        {days.map((d, i) => (
          <label className="f" key={i} style={{ flex: '0 1 110px' }}>Dia {i + 1} (R$)
            <input type="text" inputMode="decimal" value={d} onChange={e => setDays(ds => ds.map((x, j) => (j === i ? e.target.value : x)))} />
          </label>
        ))}
      </div>
      <div className="row">
        <button type="button" className="btn small" disabled={days.length >= 31} onClick={() => setDays(ds => [...ds, '0,10'])}>+ Dia</button>
        <button type="button" className="btn small" disabled={days.length <= 1} onClick={() => setDays(ds => ds.slice(0, -1))}>− Dia</button>
        <span className="muted">{days.length} {days.length === 1 ? 'dia' : 'dias'} (máx. 31) · cada prêmio de R$ 0,00 a R$ 500,00</span>
      </div>
      <div><button className="btn primary" disabled={busy || !cfg.ready}>{busy ? 'Salvando…' : 'Salvar'}</button></div>
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.t}</p>}
    </form>
  );
}

function Orders() {
  const { data, err, ready, reload } = useLoad<AOrder[]>('/orders', []);
  return (
    <div className="stack">
      <div className="row between"><h3>Pedidos</h3><button className="btn small" onClick={reload}>Atualizar</button></div>
      {err && <p className="notice bad" role="alert">{err}</p>}
      {ready && data.length === 0 && <p className="muted">Nenhum pedido ainda.</p>}
      {data.length > 0 && <div className="scroll"><table>
        <thead><tr><th>#</th><th>Cliente</th><th>Itens</th><th>Total</th><th>Status</th><th>Criado</th><th>Pago</th></tr></thead>
        <tbody>{data.map(o => (
          <tr key={o.id}><td>#{o.id}</td><td>{o.customerEmail}</td><td>{o.items.join(', ')}{o.couponCode ? <><br /><span className="muted">cupom {o.couponCode}{o.discountCents > 0 ? ' (− ' + brl(o.discountCents) + ')' : ''}{o.bonusCents > 0 ? ' (+ ' + brl(o.bonusCents) + ' de bônus)' : ''}</span></> : null}</td><td>{brl(o.totalCents)}{o.paidWithBalance ? <><br /><span className="muted">pago com saldo</span></> : o.balanceCents > 0 ? <><br /><span className="muted">+ {brl(o.balanceCents)} em saldo</span></> : null}</td>
            <td><span className={'status ' + o.status}>{STATUS[o.status] ?? o.status}</span></td><td>{fmtDate(o.createdAt)}</td><td>{fmtDate(o.paidAt)}</td></tr>))}</tbody>
      </table></div>}
    </div>
  );
}

// ---------- clientes ----------
function Customers() {
  const customers = useLoad<ACustomer[]>('/customers', []);
  const ents = useLoad<AEntitlement[]>('/entitlements', []);
  const products = useLoad<AProduct[]>('/products', []);
  const servers = useLoad<ServerView[]>('/servers', []);
  const [pick, setPick] = useState<Record<number, string>>({});
  const [note, setNote] = useState<{ ok: boolean; t: string } | null>(null);

  const grant = async (c: ACustomer) => {
    const productId = pick[c.id];
    if (!productId) return;
    try { await acall('POST', '/customers/' + c.id + '/entitlements', { productId }); setNote({ ok: true, t: 'Plugin liberado para ' + c.email + '.' }); ents.reload(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
  };
  const revoke = async (c: ACustomer, productId: string) => {
    const name = products.data.find(p => p.id === productId)?.name ?? productId;
    if (!window.confirm('Tirar o plugin "' + name + '" de ' + c.email + '?\n\nO cliente perde o acesso e o plugin é removido dos servidores dele. Para devolver, é preciso liberar de novo.')) return;
    try { await acall('DELETE', '/customers/' + c.id + '/entitlements/' + encodeURIComponent(productId)); setNote({ ok: true, t: 'Plugin removido de ' + c.email + '.' }); ents.reload(); servers.reload(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
  };
  const [adj, setAdj] = useState<Record<number, string>>({});
  const adjust = async (c: ACustomer, sign: 1 | -1) => {
    const cents = parseBRL(adj[c.id] ?? '');
    if (!cents) { setNote({ ok: false, t: 'Informe um valor maior que zero.' }); return; }
    const note = window.prompt((sign > 0 ? 'Creditar ' : 'Debitar ') + brl(cents) + ' em ' + c.email + '.\nMotivo (opcional, aparece no extrato do cliente):', '');
    if (note === null) return;
    try {
      await acall('POST', '/customers/' + c.id + '/balance', { amountCents: sign * cents, note });
      setNote({ ok: true, t: (sign > 0 ? 'Saldo creditado a ' : 'Saldo debitado de ') + c.email + '.' });
      setAdj({ ...adj, [c.id]: '' }); customers.reload();
    } catch (x) { setNote({ ok: false, t: emsg(x) }); }
  };
  const err = customers.err || ents.err || products.err || servers.err;
  return (
    <div className="stack">
      <h3>Clientes</h3>
      {err && <p className="notice bad" role="alert">{err}</p>}
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.t}</p>}
      {customers.ready && customers.data.length === 0 && <p className="muted">Nenhum cliente ainda.</p>}
      {customers.data.length > 0 && <div className="scroll"><table>
        <thead><tr><th>#</th><th>Cliente</th><th>Plugins</th><th>Servidores</th><th>Saldo</th><th>Liberar plugin (cortesia)</th></tr></thead>
        <tbody>{customers.data.map(c => {
          const mine = ents.data.filter(e => e.customerId === c.id).map(e => e.productId);
          return (
            <tr key={c.id}>
              <td>{c.id}</td><td>{c.name}<br /><span className="muted">{c.email}</span></td>
              <td>{mine.length ? (
                <div className="ent-list">{mine.map(id => (
                  <span className="ent-chip" key={id}>
                    {products.data.find(p => p.id === id)?.name ?? id}
                    <button type="button" onClick={() => revoke(c, id)} aria-label={'Tirar ' + id + ' de ' + c.email} title="Tirar plugin"><i className="fa-solid fa-xmark" aria-hidden="true" /></button>
                  </span>))}
                </div>) : '—'}</td>
              <td>{servers.data.filter(s => s.customerId === c.id).length}</td>
              <td>
                <strong>{brl(c.balanceCents)}</strong>
                <div className="row" style={{ marginTop: 6, flexWrap: 'nowrap' }}>
                  <input type="text" inputMode="decimal" aria-label={'Valor do ajuste para ' + c.email} placeholder="R$" value={adj[c.id] ?? ''} onChange={e => setAdj({ ...adj, [c.id]: e.target.value })} style={{ width: 84, padding: '5px 8px' }} />
                  <button className="icon-btn" title="Creditar saldo" aria-label={'Creditar saldo a ' + c.email} onClick={() => adjust(c, 1)}><i className="fa-solid fa-plus" aria-hidden="true" /></button>
                  <button className="icon-btn danger" title="Debitar saldo" aria-label={'Debitar saldo de ' + c.email} onClick={() => adjust(c, -1)}><i className="fa-solid fa-minus" aria-hidden="true" /></button>
                </div>
              </td>
              <td><div className="row">
                <select aria-label={'Plugin para ' + c.email} value={pick[c.id] ?? ''} onChange={e => setPick({ ...pick, [c.id]: e.target.value })} className="pick-select">
                  <option value="">Escolha…</option>
                  {products.data.filter(p => !mine.includes(p.id)).map(p => <option key={p.id} value={p.id}>{p.name}</option>)}
                </select>
                <button className="btn small" disabled={!pick[c.id]} onClick={() => grant(c)}>Liberar</button>
              </div></td>
            </tr>);
        })}</tbody>
      </table></div>}
    </div>
  );
}

// ---------- cupons ----------
const COUPON_KIND: Record<ACoupon['kind'], string> = { DISCOUNT: 'Desconto na compra', DEPOSIT_BONUS: 'Bônus de depósito' };
/** Data/hora local daqui a N dias, no formato do campo datetime-local. */
const inDays = (d: number) => { const t = new Date(Date.now() + d * 86400000); t.setMinutes(t.getMinutes() - t.getTimezoneOffset()); return t.toISOString().slice(0, 16); };
const couponValue = (c: ACoupon) => (c.valueType === 'PERCENT' ? c.value + '%' : brl(c.value));

function Coupons() {
  const { data, err, ready, reload } = useLoad<ACoupon[]>('/coupons', []);
  const [note, setNote] = useState<{ ok: boolean; t: string } | null>(null);
  const [f, setF] = useState({ code: '', kind: 'DISCOUNT', valueType: 'PERCENT', value: '', min: '', cap: '', maxUses: '', perCustomer: '1', expires: '' });
  const [busy, setBusy] = useState(false);
  const set = (k: keyof typeof f, v: string) => setF(prev => ({ ...prev, [k]: v }));

  const intOrNull = (t: string): number | null | undefined => { const x = t.trim(); if (!x) return null; return /^\d+$/.test(x) ? Number(x) : undefined; };

  const create = async (e: React.FormEvent) => {
    e.preventDefault();
    const value = f.valueType === 'PERCENT' ? intOrNull(f.value) : parseBRL(f.value);
    const min = f.min.trim() ? parseBRL(f.min) : 0;
    const cap = f.cap.trim() ? parseBRL(f.cap) : null;
    const capBad = f.cap.trim() !== '' && cap === null;
    const maxUses = intOrNull(f.maxUses);
    const perCustomer = intOrNull(f.perCustomer);
    if (!value) { setNote({ ok: false, t: 'Informe o valor do cupom (maior que zero).' }); return; }
    if (min === null || capBad || maxUses === undefined || perCustomer === undefined) { setNote({ ok: false, t: 'Confira os campos numéricos.' }); return; }
    setBusy(true); setNote(null);
    try {
      await acall('POST', '/coupons', {
        code: f.code, kind: f.kind, valueType: f.valueType, value, minCents: min, capCents: cap,
        maxUses, maxUsesPerCustomer: perCustomer, expiresAt: f.expires ? new Date(f.expires).toISOString() : null,
      });
      setNote({ ok: true, t: 'Cupom ' + f.code.trim().toUpperCase() + ' criado.' });
      setF({ ...f, code: '', value: '', min: '', cap: '', maxUses: '', expires: '' });
      reload();
    } catch (x) { setNote({ ok: false, t: emsg(x) }); }
    finally { setBusy(false); }
  };
  const toggle = async (c: ACoupon) => {
    try { await acall('PUT', '/coupons/' + c.id + '/active', { active: !c.active }); reload(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
  };
  const remove = async (c: ACoupon) => {
    if (!window.confirm('Excluir o cupom ' + c.code + '?\n\nPedidos que já usaram o cupom continuam com o desconto/bônus registrado. Para só parar de aceitar, desative em vez de excluir.')) return;
    try { await acall('DELETE', '/coupons/' + c.id); reload(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
  };

  return (
    <div className="stack">
      <h3>Cupons</h3>
      <form className="card" onSubmit={create} style={{ gap: 12 }}>
        <h3>Novo cupom</h3>
        <div className="row" style={{ alignItems: 'flex-end' }}>
          <label className="f">Código<input type="text" value={f.code} onChange={e => set('code', e.target.value)} maxLength={32} placeholder="BEMVINDO10" required style={{ textTransform: 'uppercase' }} /></label>
          <label className="f">Tipo
            <select className="pick-select" value={f.kind} onChange={e => set('kind', e.target.value)}>
              <option value="DISCOUNT">Desconto na compra</option><option value="DEPOSIT_BONUS">Bônus de depósito</option>
            </select></label>
          <label className="f">Forma
            <select className="pick-select" value={f.valueType} onChange={e => set('valueType', e.target.value)}>
              <option value="PERCENT">Percentual (%)</option><option value="FIXED">Valor fixo (R$)</option>
            </select></label>
          <label className="f">{f.valueType === 'PERCENT' ? 'Percentual' : 'Valor (R$)'}<input type="text" inputMode="decimal" value={f.value} onChange={e => set('value', e.target.value)} placeholder={f.valueType === 'PERCENT' ? '10' : '5,00'} required style={{ width: 110 }} /></label>
        </div>
        <div className="row" style={{ alignItems: 'flex-end' }}>
          <label className="f">{f.kind === 'DISCOUNT' ? 'Compra mínima (R$)' : 'Depósito mínimo (R$)'}<input type="text" inputMode="decimal" value={f.min} onChange={e => set('min', e.target.value)} placeholder="sem mínimo" style={{ width: 130 }} /></label>
          <label className="f">Teto do {f.kind === 'DISCOUNT' ? 'desconto' : 'bônus'} (R$)<input type="text" inputMode="decimal" value={f.cap} onChange={e => set('cap', e.target.value)} placeholder="sem teto" style={{ width: 130 }} /></label>
          <label className="f">Usos no total<input type="text" inputMode="numeric" value={f.maxUses} onChange={e => set('maxUses', e.target.value)} placeholder="ilimitado" style={{ width: 110 }} /></label>
          <label className="f">Usos por conta<input type="text" inputMode="numeric" value={f.perCustomer} onChange={e => set('perCustomer', e.target.value)} placeholder="1 (uso único)" style={{ width: 120 }} /></label>
          <label className="f">Validade<input type="datetime-local" value={f.expires} onChange={e => set('expires', e.target.value)} /></label>
          <div className="chips" role="group" aria-label="Validade rápida">
            {([['24 h', 1], ['7 dias', 7], ['30 dias', 30]] as const).map(([l, d]) => <button type="button" key={l} className="chip" onClick={() => set('expires', inDays(d))}>{l}</button>)}
            <button type="button" className="chip" onClick={() => set('expires', '')}>Sem validade</button>
          </div>
        </div>
        <div className="row"><button className="btn primary" disabled={busy}><i className="fa-solid fa-ticket" aria-hidden="true" /> {busy ? 'Criando…' : 'Criar cupom'}</button></div>
        <p className="muted">Desconto: abate o valor da compra (nunca passa do total). Bônus: soma saldo extra ao depósito do cliente. Se você não definir os usos por conta, o cupom vale <b>uma única vez por conta</b>.</p>
      </form>
      {err && <p className="notice bad" role="alert">{err}</p>}
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.t}</p>}
      {ready && data.length === 0 && <p className="muted">Nenhum cupom ainda.</p>}
      {data.length > 0 && <div className="scroll"><table>
        <thead><tr><th>Código</th><th>Tipo</th><th>Valor</th><th>Mínimo</th><th>Teto</th><th>Usos</th><th>Por conta</th><th>Validade</th><th>Ativo</th><th /></tr></thead>
        <tbody>{data.map(c => {
          const expired = c.expiresAt !== null && new Date(c.expiresAt).getTime() <= Date.now();
          return (
            <tr key={c.id}>
              <td><code className="cmd">{c.code}</code></td>
              <td>{COUPON_KIND[c.kind]}</td>
              <td>{couponValue(c)}</td>
              <td>{c.minCents > 0 ? brl(c.minCents) : '—'}</td>
              <td>{c.capCents !== null ? brl(c.capCents) : '—'}</td>
              <td>{c.usedCount}{c.maxUses !== null ? ' / ' + c.maxUses : ''}</td>
              <td>{c.maxUsesPerCustomer ?? 1}</td>
              <td>{c.expiresAt ? fmtDate(c.expiresAt) : '—'}{expired && <> <span className="status EXPIRED">vencido</span></>}</td>
              <td><label className="row" style={{ gap: 6, flexWrap: 'nowrap' }}><input type="checkbox" checked={c.active} onChange={() => toggle(c)} style={{ width: 18, height: 18, accentColor: 'var(--accent)' }} aria-label={'Cupom ' + c.code + ' ativo'} /></label></td>
              <td><button className="icon-btn danger" title="Excluir cupom" aria-label={'Excluir cupom ' + c.code} onClick={() => remove(c)}><i className="fa-solid fa-trash" aria-hidden="true" /></button></td>
            </tr>);
        })}</tbody>
      </table></div>}
    </div>
  );
}

// ---------- servidores ----------
function Servers() {
  const servers = useLoad<ServerView[]>('/servers', []);
  const customers = useLoad<ACustomer[]>('/customers', []);
  const [note, setNote] = useState<{ ok: boolean; t: string } | null>(null);
  const email = (id: number) => customers.data.find(c => c.id === id)?.email ?? '#' + id;

  const toggle = async (s: ServerView) => {
    try { await acall('POST', '/servers/' + s.id + (s.active ? '/revoke' : '/activate')); setNote({ ok: true, t: s.active ? 'Servidor suspenso. O servidor dele desliga após a carência.' : 'Servidor reativado.' }); servers.reload(); }
    catch (x) { setNote({ ok: false, t: emsg(x) }); }
  };
  return (
    <div className="stack">
      <div className="row between"><h3>Servidores</h3><button className="btn small" onClick={servers.reload}>Atualizar</button></div>
      {(servers.err || customers.err) && <p className="notice bad" role="alert">{servers.err || customers.err}</p>}
      {note && <p className={'notice' + (note.ok ? '' : ' bad')} role={note.ok ? 'status' : 'alert'}>{note.t}</p>}
      {servers.ready && servers.data.length === 0 && <p className="muted">Nenhum servidor cadastrado.</p>}
      {servers.data.length > 0 && <div className="scroll"><table>
        <thead><tr><th>#</th><th>Cliente</th><th>IP:porta</th><th>Plugins</th><th>Última conexão</th><th>Status</th><th /></tr></thead>
        <tbody>{servers.data.map(s => (
          <tr key={s.id}>
            <td>{s.id}</td><td>{email(s.customerId)}</td><td className="mono">{s.ip}:{s.port}{s.label ? <><br /><span className="muted">{s.label}</span></> : null}</td>
            <td>{s.products.length ? s.products.map(p => p.productId + (p.version ? ' ' + p.version : '')).join(', ') : '—'}</td>
            <td>{fmtDate(s.lastSeenAt)}</td>
            <td><span className={'status ' + (s.active ? 'PAID' : 'EXPIRED')}>{s.active ? 'Ativo' : 'Suspenso'}</span></td>
            <td><button className="btn small" onClick={() => toggle(s)}>{s.active ? 'Suspender' : 'Reativar'}</button></td>
          </tr>))}</tbody>
      </table></div>}
    </div>
  );
}
