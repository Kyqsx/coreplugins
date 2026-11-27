export interface CatalogItem { id: string; name: string; description: string | null; category: string | null; command: string | null; priceCents: number; version: string; images: number[]; youtubeId: string | null }
export interface Session { token: string; id: number; name: string; email: string }
export interface OrderItem { productId: string; name: string; priceCents: number }
export interface Order {
  id: number; status: 'PENDING' | 'PAID' | 'EXPIRED' | 'CANCELLED' | 'CREDITED'; totalCents: number;
  pixCode: string | null; pixQrBase64: string | null; createdAt: string; expiresAt: string; paidAt: string | null;
  items: OrderItem[]; simulated: boolean;
  kind: 'PURCHASE' | 'DEPOSIT'; subtotalCents: number; discountCents: number; bonusCents: number;
  couponCode: string | null; paidWithBalance: boolean; balanceCents: number;
}
export interface Quote { subtotalCents: number; discountCents: number; totalCents: number; couponCode: string | null; balanceCents: number; balanceUsedCents: number; pixCents: number }
export interface DepositQuote { amountCents: number; bonusCents: number; creditCents: number; couponCode: string | null }
export interface WalletTx { id: number; type: string; amountCents: number; description: string | null; orderId: number | null; createdAt: string }
export interface WalletView { balanceCents: number; transactions: WalletTx[] }
export interface Me { id: number; name: string; email: string; balanceCents: number }
export interface Grant { productId: string; expiresAt: string | null; version: string | null }
export interface VersionView { version: string; createdAt: string }
export interface LibraryItem extends Omit<CatalogItem, 'images' | 'youtubeId'> { versions: VersionView[] }
/** version vazia/null = acompanhar a mais recente */
export interface Selection { productId: string; version: string | null }
export interface CheckinView { enabled: boolean; claimedToday: boolean; streak: number; dayInCycle: number; rewardsCents: number[]; todayRewardCents: number }
export interface CheckinClaim { rewardCents: number; streak: number; dayInCycle: number; balanceCents: number }
export interface UpdateItem { productId: string; productName: string; category: string | null; version: string; changelog: string | null; createdAt: string; latest: boolean }
export interface LoaderInfo { available: boolean; sizeBytes: number; updatedAt: string | null }
export interface ServerView { id: number; customerId: number; ip: string; port: number; label: string | null; active: boolean; lastSeenAt: string | null; products: Grant[] }

/** URL pública de uma imagem da galeria do plugin (a primeira da lista `images` é a capa). */
export const imageUrl = (productId: string, imageId: number) => '/api/store/products/' + encodeURIComponent(productId) + '/images/' + imageId;

const KEY = 'coreplugins.session';

export function getSession(): Session | null {
  try { const s = localStorage.getItem(KEY); return s ? JSON.parse(s) as Session : null; } catch { return null; }
}
export function setSession(s: Session | null) {
  try { if (s) localStorage.setItem(KEY, JSON.stringify(s)); else localStorage.removeItem(KEY); } catch { /* sem storage */ }
}

export class ApiError extends Error {
  constructor(public status: number, message: string) { super(message); }
}

async function call<T>(method: string, path: string, body?: unknown): Promise<T> {
  const headers: Record<string, string> = {};
  if (body !== undefined) headers['Content-Type'] = 'application/json';
  const s = getSession();
  if (s) headers['Authorization'] = 'Bearer ' + s.token;
  let res: Response;
  try {
    res = await fetch('/api/store' + path, { method, headers, body: body === undefined ? undefined : JSON.stringify(body) });
  } catch {
    throw new ApiError(0, 'Não foi possível falar com o servidor da loja.');
  }
  let data: unknown = null;
  try { data = await res.json(); } catch { /* corpo vazio */ }
  if (!res.ok) {
    if (res.status === 401 && s) { setSession(null); window.dispatchEvent(new Event('coreplugins-logout')); }
    const msg = (data as { error?: string } | null)?.error ?? 'Erro inesperado (' + res.status + ').';
    throw new ApiError(res.status, msg);
  }
  return data as T;
}

export const api = {
  catalog: () => call<CatalogItem[]>('GET', '/catalog'),
  register: (name: string, email: string, password: string) => call<Session>('POST', '/auth/register', { name, email, password }),
  login: (email: string, password: string) => call<Session>('POST', '/auth/login', { email, password }),
  // Revoga no servidor todos os tokens da conta; chame ANTES de apagar a sessão local (o token sai dela).
  logout: () => call<{ ok: boolean }>('POST', '/logout'),
  me: () => call<Me>('GET', '/me'),
  checkout: (productIds: string[], couponCode: string | null, useBalance: boolean) => call<Order>('POST', '/orders', { productIds, couponCode, useBalance }),
  quote: (productIds: string[], couponCode: string | null) => call<Quote>('POST', '/quote', { productIds, couponCode }),
  wallet: () => call<WalletView>('GET', '/wallet'),
  depositQuote: (amountCents: number, couponCode: string | null) => call<DepositQuote>('POST', '/deposits/quote', { amountCents, couponCode }),
  deposit: (amountCents: number, couponCode: string | null) => call<Order>('POST', '/deposits', { amountCents, couponCode }),
  orders: () => call<Order[]>('GET', '/orders'),
  order: (id: number) => call<Order>('GET', '/orders/' + id),
  simulatePay: (id: number) => call<Order>('POST', '/orders/' + id + '/simulate-pay'),
  library: () => call<LibraryItem[]>('GET', '/library'),
  servers: () => call<ServerView[]>('GET', '/servers'),
  createServer: (ip: string, port: number, label: string) => call<ServerView>('POST', '/servers', { ip, port, label }),
  changeAddress: (id: number, ip: string, port: number) => call<ServerView>('PUT', '/servers/' + id + '/address', { ip, port }),
  setProducts: (id: number, products: Selection[]) => call<ServerView>('PUT', '/servers/' + id + '/products', { products }),
  checkin: () => call<CheckinView>('GET', '/checkin'),
  claimCheckin: () => call<CheckinClaim>('POST', '/checkin'),
  updates: () => call<UpdateItem[]>('GET', '/updates'),
  loaderInfo: () => call<LoaderInfo>('GET', '/loader'),
};

/**
 * Baixa o corePlugins.jar. A rota exige login (token no cabeçalho), então um <a href> não serve:
 * busca o arquivo com o token e entrega ao navegador como download.
 */
export async function downloadLoader(): Promise<void> {
  const s = getSession();
  let res: Response;
  try {
    res = await fetch('/api/store/loader/download', { headers: s ? { Authorization: 'Bearer ' + s.token } : {} });
  } catch {
    throw new ApiError(0, 'Não foi possível falar com o servidor da loja.');
  }
  if (!res.ok) {
    if (res.status === 401 && s) { setSession(null); window.dispatchEvent(new Event('coreplugins-logout')); }
    let text = 'Não foi possível baixar o arquivo (' + res.status + ').';
    try { const d = await res.json() as { error?: string }; if (d.error) text = d.error; } catch { /* sem corpo */ }
    throw new ApiError(res.status, text);
  }
  const url = URL.createObjectURL(await res.blob());
  const a = document.createElement('a');
  a.href = url; a.download = 'corePlugins.jar';
  document.body.appendChild(a); a.click(); a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 10000);
}

/** "29,90" ou "29.90" -> 2990 (centavos). null se inválido. */
export function parseBRL(t: string): number | null {
  let x = t.trim().replace(/^R\$\s*/, '');
  if (!/^\d[\d.,]*$/.test(x)) return null;
  x = x.includes(',') ? x.replace(/\./g, '').replace(',', '.') : x;
  const n = Number(x);
  return Number.isFinite(n) && n >= 0 ? Math.round(n * 100) : null;
}
export const MIN_DEPOSIT = 500;
export const MAX_DEPOSIT = 200000;

export const brl = (cents: number) => (cents / 100).toLocaleString('pt-BR', { style: 'currency', currency: 'BRL' });
