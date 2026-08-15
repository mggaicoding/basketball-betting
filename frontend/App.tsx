import { SafeAreaView, StyleSheet } from 'react-native';
import { BettingApiClient } from './src/api/BettingApiClient';
import { classroomToken } from './src/auth/classroomToken';
import { BettingRoute } from './src/betting/BettingRoute';

const apiUrl = process.env.EXPO_PUBLIC_BETTING_API_URL ?? 'http://localhost:8080';
const client = new BettingApiClient(apiUrl, async () => classroomToken || null);

export default function App() {
  return (
    <SafeAreaView style={styles.safeArea}>
      <BettingRoute client={client} />
    </SafeAreaView>
  );
}

const styles = StyleSheet.create({
  safeArea: { flex: 1, backgroundColor: '#f8fafc' },
});
