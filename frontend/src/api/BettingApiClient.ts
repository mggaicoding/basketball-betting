import type { AccessTokenProvider } from '../auth/AccessTokenProvider';
import type { ApiErrorBody, BetResponse, Game, PlaceBetRequest } from './contracts';

export class BettingApiError extends Error {
  constructor(
    readonly status: number,
    readonly code: string,
    message: string,
    readonly traceId?: string,
  ) {
    super(message);
  }
}

export class BettingApiClient {
  constructor(
    private readonly baseUrl: string,
    private readonly accessToken: AccessTokenProvider,
  ) {}

  getGame(gameId: string): Promise<Game> {
    return this.request<Game>(`/api/v1/games/${encodeURIComponent(gameId)}`, { method: 'GET' });
  }

  placeBet(request: PlaceBetRequest): Promise<BetResponse> {
    return this.request<BetResponse>('/api/v1/bets', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(request),
    });
  }

  private async request<T>(path: string, init: RequestInit): Promise<T> {
    const token = await this.accessToken();
    if (!token) throw new BettingApiError(401, 'UNAUTHENTICATED', 'Sign in required');

    let response: Response;
    try {
      response = await fetch(`${this.baseUrl}${path}`, {
        ...init,
        headers: { ...init.headers, Authorization: `Bearer ${token}` },
      });
    } catch {
      throw new BettingApiError(0, 'NETWORK_ERROR', 'Unable to reach service');
    }

    if (!response.ok) {
      const body = await readErrorBody(response);
      throw new BettingApiError(
        response.status,
        body.code ?? defaultCode(response.status),
        body.message ?? 'The request could not be completed',
        body.traceId,
      );
    }
    return (await response.json()) as T;
  }
}

async function readErrorBody(response: Response): Promise<ApiErrorBody> {
  try {
    const value: unknown = await response.json();
    return typeof value === 'object' && value !== null && !Array.isArray(value)
      ? (value as ApiErrorBody)
      : {};
  } catch {
    return {};
  }
}

function defaultCode(status: number): string {
  if (status === 400) return 'INVALID_REQUEST';
  if (status === 401) return 'UNAUTHENTICATED';
  if (status === 403) return 'FORBIDDEN';
  return 'SERVICE_ERROR';
}
