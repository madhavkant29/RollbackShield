/**
 * Cognito Hosted UI sign-in (Authorization Code + PKCE) for the control
 * room. Entirely client-side and configured by environment:
 *
 *   NEXT_PUBLIC_COGNITO_DOMAIN      e.g. https://<pool>.auth.<region>.amazoncognito.com
 *   NEXT_PUBLIC_COGNITO_CLIENT_ID   the app client id (public, no secret)
 *   NEXT_PUBLIC_COGNITO_REDIRECT_URI  optional; defaults to the app origin
 *   NEXT_PUBLIC_COGNITO_SCOPES      optional; defaults to "openid email profile"
 *
 * When DOMAIN/CLIENT_ID are unset (the local `npm run dev` case) the app
 * runs unauthenticated against the backend's `local` profile and none of
 * this code runs.
 */

const DOMAIN = process.env.NEXT_PUBLIC_COGNITO_DOMAIN?.replace(/\/+$/, '');
const CLIENT_ID = process.env.NEXT_PUBLIC_COGNITO_CLIENT_ID;
const SCOPES = process.env.NEXT_PUBLIC_COGNITO_SCOPES ?? 'openid email profile';
const EXPLICIT_REDIRECT_URI = process.env.NEXT_PUBLIC_COGNITO_REDIRECT_URI;

const VERIFIER_KEY = 'rs.pkce_verifier';
const TOKENS_KEY = 'rs.tokens';
const REFRESH_MARGIN_MS = 60_000;

export const authConfigured = Boolean(DOMAIN && CLIENT_ID);

interface Tokens {
  accessToken: string;
  refreshToken: string | null;
  idToken: string | null;
  expiresAt: number;
}

interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_in?: number;
}

function redirectUri(): string {
  if (EXPLICIT_REDIRECT_URI) return EXPLICIT_REDIRECT_URI;
  return typeof window === 'undefined' ? '/' : window.location.origin + '/';
}

function base64Url(bytes: Uint8Array): string {
  let binary = '';
  bytes.forEach((b) => {
    binary += String.fromCharCode(b);
  });
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/, '');
}

function randomVerifier(): string {
  const bytes = new Uint8Array(32);
  crypto.getRandomValues(bytes);
  return base64Url(bytes);
}

async function challengeFor(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest('SHA-256', new TextEncoder().encode(verifier));
  return base64Url(new Uint8Array(digest));
}

function loadTokens(): Tokens | null {
  if (typeof window === 'undefined') return null;
  const raw = window.sessionStorage.getItem(TOKENS_KEY);
  if (!raw) return null;
  try {
    return JSON.parse(raw) as Tokens;
  } catch {
    window.sessionStorage.removeItem(TOKENS_KEY);
    return null;
  }
}

function storeTokens(raw: TokenResponse, previous?: Tokens): Tokens {
  const tokens: Tokens = {
    accessToken: raw.access_token,
    refreshToken: raw.refresh_token ?? previous?.refreshToken ?? null,
    idToken: raw.id_token ?? previous?.idToken ?? null,
    expiresAt: Date.now() + (raw.expires_in ?? 3600) * 1000,
  };
  window.sessionStorage.setItem(TOKENS_KEY, JSON.stringify(tokens));
  return tokens;
}

export function signIn(): void {
  if (!authConfigured || typeof window === 'undefined') return;
  const verifier = randomVerifier();
  window.sessionStorage.setItem(VERIFIER_KEY, verifier);
  void challengeFor(verifier).then((challenge) => {
    const params = new URLSearchParams({
      response_type: 'code',
      client_id: CLIENT_ID as string,
      redirect_uri: redirectUri(),
      scope: SCOPES,
      code_challenge: challenge,
      code_challenge_method: 'S256',
    });
    window.location.assign(`${DOMAIN}/oauth2/authorize?${params.toString()}`);
  });
}

/** Completes the redirect back from Cognito, if this load is that redirect. */
export async function completeSignInIfCallback(): Promise<boolean> {
  if (!authConfigured || typeof window === 'undefined') return false;
  const url = new URL(window.location.href);
  const code = url.searchParams.get('code');
  if (!code) return false;

  const verifier = window.sessionStorage.getItem(VERIFIER_KEY);
  window.sessionStorage.removeItem(VERIFIER_KEY);
  if (!verifier) return false;

  const body = new URLSearchParams({
    grant_type: 'authorization_code',
    client_id: CLIENT_ID as string,
    code,
    redirect_uri: redirectUri(),
    code_verifier: verifier,
  });

  try {
    const response = await fetch(`${DOMAIN}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (!response.ok) return false;
    storeTokens((await response.json()) as TokenResponse);
    url.search = '';
    window.history.replaceState({}, '', url.toString());
    return true;
  } catch {
    return false;
  }
}

async function refreshTokens(tokens: Tokens): Promise<Tokens | null> {
  if (!tokens.refreshToken) return null;
  const body = new URLSearchParams({
    grant_type: 'refresh_token',
    client_id: CLIENT_ID as string,
    refresh_token: tokens.refreshToken,
  });
  try {
    const response = await fetch(`${DOMAIN}/oauth2/token`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
      body,
    });
    if (!response.ok) return null;
    return storeTokens((await response.json()) as TokenResponse, tokens);
  } catch {
    return null;
  }
}

/**
 * Returns a currently-valid access token, refreshing it if it is within a
 * minute of expiry. Returns null when unconfigured, signed out, or the
 * refresh failed (callers then simply send no `Authorization` header).
 */
export async function getAccessToken(): Promise<string | null> {
  if (!authConfigured || typeof window === 'undefined') return null;
  let tokens = loadTokens();
  if (!tokens) return null;
  if (tokens.expiresAt - Date.now() < REFRESH_MARGIN_MS) {
    const refreshed = await refreshTokens(tokens);
    if (!refreshed) {
      window.sessionStorage.removeItem(TOKENS_KEY);
      return null;
    }
    tokens = refreshed;
  }
  return tokens.accessToken;
}

export function isSignedIn(): boolean {
  return loadTokens() !== null;
}

export function signOut(): void {
  if (typeof window === 'undefined') return;
  window.sessionStorage.removeItem(TOKENS_KEY);
  if (!authConfigured) {
    window.location.reload();
    return;
  }
  const params = new URLSearchParams({
    client_id: CLIENT_ID as string,
    logout_uri: redirectUri(),
  });
  window.location.assign(`${DOMAIN}/logout?${params.toString()}`);
}
