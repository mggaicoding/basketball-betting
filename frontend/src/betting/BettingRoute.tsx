import { useEffect, useState } from 'react';
import { ActivityIndicator, Pressable, Text, View } from 'react-native';
import type { BettingApiClient } from '../api/BettingApiClient';
import type { Game } from '../api/contracts';
import { BetScreen } from './BetScreen';

type GameLoadState =
  | { kind: 'loading' }
  | { kind: 'ready'; game: Game }
  | { kind: 'failed'; message: string };

export function BettingRoute({ client }: { client: BettingApiClient }) {
  const [attempt, setAttempt] = useState(0);
  const [state, setState] = useState<GameLoadState>({ kind: 'loading' });

  useEffect(() => {
    let active = true;
    setState({ kind: 'loading' });
    client.getGame('G-100').then(
      (game) => active && setState({ kind: 'ready', game }),
      () => active && setState({ kind: 'failed', message: 'Unable to load the game' }),
    );
    return () => {
      active = false;
    };
  }, [client, attempt]);

  if (state.kind === 'loading') return <ActivityIndicator accessibilityLabel="Loading game" />;
  if (state.kind === 'failed') {
    return (
      <View>
        <Text accessibilityRole="alert">{state.message}</Text>
        <Pressable accessibilityRole="button" onPress={() => setAttempt((value) => value + 1)}>
          <Text>Retry</Text>
        </Pressable>
      </View>
    );
  }
  return <BetScreen game={state.game} client={client} />;
}
