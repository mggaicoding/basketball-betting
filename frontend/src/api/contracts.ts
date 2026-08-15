export type Selection = 'HOME' | 'AWAY';

export type Game = {
  gameId: string;
  homeTeam: string;
  awayTeam: string;
  startTime: string;
  homeOdds: number;
  awayOdds: number;
};

export type PlaceBetRequest = {
  gameId: string;
  selection: Selection;
  stake: number;
};

export type BetResponse = {
  betId: string;
  odds: number;
  status: 'ACCEPTED';
};

export type ApiErrorBody = {
  code?: string;
  message?: string;
  traceId?: string;
};
