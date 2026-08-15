import path from 'node:path';
import { MatchersV3, PactV3 } from '@pact-foundation/pact';
import { describe, expect, it } from 'vitest';
import { BettingApiClient } from '../src/api/BettingApiClient';

const { decimal, integer, like, regex } = MatchersV3;
const provider = new PactV3({
  consumer: 'betting-expo',
  provider: 'betting-api',
  dir: path.resolve(process.cwd(), 'pacts'),
});

describe('BettingApiClient contract', () => {
  it('places a HOME bet using the minimum response fields the screen needs', async () => {
    provider
      .given('game G-100 is open for betting')
      .uponReceiving('a valid HOME bet')
      .withRequest({
        method: 'POST',
        path: '/api/v1/bets',
        headers: {
          Authorization: like('Bearer contract-test-token'),
          'Content-Type': regex('application/json.*', 'application/json'),
        },
        body: { gameId: 'G-100', selection: 'HOME', stake: integer(100) },
      })
      .willRespondWith({
        status: 201,
        headers: { 'Content-Type': regex('application/json.*', 'application/json') },
        body: { betId: like('B-201'), odds: decimal(1.85), status: 'ACCEPTED' },
      });

    await provider.executeTest(async (mockServer) => {
      const client = new BettingApiClient(mockServer.url, async () => 'contract-test-token');
      const result = await client.placeBet({ gameId: 'G-100', selection: 'HOME', stake: 100 });
      expect(result.status).toBe('ACCEPTED');
      expect(result.betId).toBeTruthy();
    });
  });
});
