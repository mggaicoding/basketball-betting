import { useState } from 'react';
import { BettingApiClient, BettingApiError } from '../api/BettingApiClient';
import type { PlaceBetRequest } from '../api/contracts';

export type PlaceBetState =
  | { kind: 'idle' }
  | { kind: 'submitting' }
  | { kind: 'succeeded'; betId: string }
  | { kind: 'invalid'; message: string }
  | { kind: 'unauthorized'; message: string }
  | { kind: 'network'; message: string }
  | { kind: 'failed'; message: string; traceId?: string };

export function usePlaceBet(client: BettingApiClient) {
  const [state, setState] = useState<PlaceBetState>({ kind: 'idle' });

  async function submit(request: PlaceBetRequest) {
    setState({ kind: 'submitting' });
    try {
      const response = await client.placeBet(request);
      setState({ kind: 'succeeded', betId: response.betId });
    } catch (error) {
      if (!(error instanceof BettingApiError)) {
        setState({ kind: 'failed', message: 'Unexpected client error' });
      } else if (error.status === 400) {
        setState({ kind: 'invalid', message: error.message });
      } else if (error.status === 401 || error.status === 403) {
        setState({ kind: 'unauthorized', message: error.message });
      } else if (error.code === 'NETWORK_ERROR') {
        setState({ kind: 'network', message: error.message });
      } else {
        setState({ kind: 'failed', message: error.message, traceId: error.traceId });
      }
    }
  }

  return { state, submit };
}
