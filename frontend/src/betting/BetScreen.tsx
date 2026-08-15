import { useState } from 'react';
import { ActivityIndicator, Pressable, StyleSheet, Text, TextInput, View } from 'react-native';
import type { BettingApiClient } from '../api/BettingApiClient';
import type { Game, Selection } from '../api/contracts';
import { type PlaceBetState, usePlaceBet } from './usePlaceBet';

export function BetScreen({ game, client }: { game: Game; client: BettingApiClient }) {
  const [selection, setSelection] = useState<Selection>('HOME');
  const [stakeText, setStakeText] = useState('100');
  const { state, submit } = usePlaceBet(client);
  const stake = Number(stakeText);
  const validationMessage =
    Number.isFinite(stake) && stake >= 0.01 && stake <= 1000
      ? null
      : 'Enter a stake from 0.01 to 1000';

  const placeBet = () => {
    if (!validationMessage) void submit({ gameId: game.gameId, selection, stake });
  };

  return (
    <View style={styles.screen}>
      <Text accessibilityRole="header" style={styles.title}>
        {game.homeTeam} vs {game.awayTeam}
      </Text>
      <Text>Starts: {new Date(game.startTime).toLocaleTimeString()}</Text>
      <View style={styles.row}>
        <SelectionButton label={`HOME ${game.homeOdds.toFixed(2)}`} selected={selection === 'HOME'} onPress={() => setSelection('HOME')} />
        <SelectionButton label={`AWAY ${game.awayOdds.toFixed(2)}`} selected={selection === 'AWAY'} onPress={() => setSelection('AWAY')} />
      </View>
      <Text>Stake</Text>
      <TextInput accessibilityLabel="Stake" inputMode="decimal" value={stakeText} onChangeText={setStakeText} style={styles.input} />
      {validationMessage && <Text style={styles.error}>{validationMessage}</Text>}
      <Pressable accessibilityRole="button" disabled={state.kind === 'submitting' || !!validationMessage} onPress={placeBet} style={styles.primary}>
        {state.kind === 'submitting' ? <ActivityIndicator accessibilityLabel="Placing bet" /> : <Text style={styles.primaryText}>Place Bet</Text>}
      </Pressable>
      <StatusMessage state={state} onRetry={placeBet} />
    </View>
  );
}

function SelectionButton({ label, selected, onPress }: { label: string; selected: boolean; onPress: () => void }) {
  return (
    <Pressable accessibilityRole="button" accessibilityState={{ selected }} onPress={onPress} style={[styles.selection, selected && styles.selected]}>
      <Text>{label}</Text>
    </Pressable>
  );
}

function StatusMessage({ state, onRetry }: { state: PlaceBetState; onRetry: () => void }) {
  if (state.kind === 'idle' || state.kind === 'submitting') return null;
  if (state.kind === 'succeeded') return <Text accessibilityRole="alert">Bet {state.betId} accepted</Text>;
  if (state.kind === 'network') {
    return <Pressable accessibilityRole="button" onPress={onRetry}><Text>{state.message}. Retry</Text></Pressable>;
  }
  return <Text accessibilityRole="alert">{state.message}</Text>;
}

const styles = StyleSheet.create({
  screen: { width: '100%', maxWidth: 640, alignSelf: 'center', padding: 24, gap: 16 },
  title: { fontSize: 24, fontWeight: '700' },
  row: { flexDirection: 'row', gap: 12 },
  selection: { flex: 1, minHeight: 48, justifyContent: 'center', alignItems: 'center', borderWidth: 1, borderColor: '#64748b', borderRadius: 8 },
  selected: { borderColor: '#0057b8', backgroundColor: '#dbeafe' },
  input: { minHeight: 48, borderWidth: 1, borderColor: '#64748b', borderRadius: 8, paddingHorizontal: 12 },
  primary: { minHeight: 48, justifyContent: 'center', alignItems: 'center', borderRadius: 8, backgroundColor: '#0057b8' },
  primaryText: { color: '#fff', fontWeight: '700' },
  error: { color: '#b42318' },
});
